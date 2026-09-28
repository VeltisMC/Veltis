package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure-Java unified diff application (no external tools, no git).
 *
 * <p>Application is organised as a read-process-write pipeline that touches the
 * filesystem once per target file:
 *
 * <pre>
 * read target once
 *       -&gt; parse every patch of the chain once
 *       -&gt; apply the chain in memory (deterministic order per file)
 *       -&gt; write once, and only when the content actually changed
 * </pre>
 *
 * <p>{@link RuntimePatchApplier} dispatches independent target files to parallel
 * workers while patches that touch the same file keep their deterministic order.
 * Phase timings and I/O counters are recorded in the {@link PatchStats} handed in
 * by the caller, so the benchmark can attribute time to discovery, parsing,
 * matching and file I/O separately.
 *
 * <p>The matching strategies (exact, whitespace-tolerant, blank-tolerant) and the
 * produced bytes are unchanged from the original implementation - the output is
 * protected by {@code PatchOutputRegressionTest} and the benchmark's
 * byte-for-byte oracle comparison.
 */
public class UnifiedDiffPatcher {

    /** Precompiled: used by the whitespace-tolerant fallback matcher. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // ------------------------------------------------------------------
    // Public single-patch API
    // ------------------------------------------------------------------

    public void applyPatch(Path patchFile, Path targetDir) throws PatchEngineException {
        try {
            var lines = Files.readAllLines(patchFile, StandardCharsets.UTF_8);
            applyPatchLines(lines, patchFile.getFileName().toString(), targetDir);
        } catch (PatchEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchEngineException("Failed to apply patch: " + patchFile, e);
        }
    }

    public void applyPatchLines(List<String> patchLines, String patchName, Path targetDir) {
        applyPatchLines(patchLines, patchName, targetDir, new PatchStats());
    }

    public void applyPatchLines(List<String> patchLines, String patchName, Path targetDir,
                                PatchStats stats) {
        applyGroup(targetKeyOf(patchLines),
            List.of(new RuntimePatchApplier.PatchContent(patchName, patchLines)),
            targetDir, stats);
    }

    // ------------------------------------------------------------------
    // One target file: read -> parse chain -> apply in memory -> write once
    // ------------------------------------------------------------------

    /**
     * Applies an ordered chain of patches to the single target file they all
     * address. The file is read once before the first patch and written at most
     * once after the last one, and only if the result differs from what is on disk.
     *
     * @param targetKey relative target path (forward slashes) shared by the chain
     * @param chain     patches in their deterministic application order
     */
    void applyGroup(String targetKey, List<RuntimePatchApplier.PatchContent> chain,
                    Path targetDir, PatchStats stats) {
        // 1) Parse every patch of the chain exactly once (recorded as "parsing").
        var parsed = new ArrayList<ParsedPatch>(chain.size());
        for (var patch : chain) {
            parsed.add(parse(patch.lines(), patch.name(), stats));
            stats.patchesApplied++;
        }

        // 2) Resolve and read the target once (recorded as "I/O").
        var target = resolveTarget(targetKey, targetDir, chain.get(0).name());
        String original;
        try {
            original = readTarget(target, stats);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to read source file: " + target, e);
        }

        // 3) Apply the chain in memory (recorded as "application").
        var file = original == null ? null : new SourceFile(original);
        var created = false;
        for (int i = 0; i < parsed.size(); i++) {
            var patch = parsed.get(i);
            if (patch.hunks.isEmpty()) {
                continue; // a patch without hunks is a no-op
            }
            if (patch.deletion) {
                throw new PatchEngineException("Patch '" + patch.name + "' deletes '"
                    + stripAPrefix(patch.oldTarget) + "' - file deletion is not supported");
            }
            if (patch.target == null) {
                throw new PatchEngineException(
                    "Could not determine target file from patch: " + patch.name);
            }
            if (file == null) {
                if (!patch.createsFile()) {
                    throw new PatchEngineException(
                        "Patch '" + patch.name + "' (" + (i + 1) + " of " + parsed.size()
                        + ") targets '" + targetKey + "' but the source file does not exist: "
                        + target + "\n  new files must be created with a '--- /dev/null' header");
                }
                // The synthesis IS the application of a creating patch (all added
                // lines, like the original writeNewFile did) - do not also run its
                // (insert-only) hunks against the just-created content.
                long t = System.nanoTime();
                var synthesized = newFileContent(patch);
                stats.writeNanos += System.nanoTime() - t;
                file = new SourceFile(synthesized);
                created = true;
                continue;
            }
            applyHunks(file, patch, stats, i + 1, parsed.size());
        }

        if (file == null) {
            return; // every patch was a no-op - do not touch the file
        }
        var updated = file.join();
        if (!created && updated.equals(original)) {
            stats.filesUnchanged++;
            return; // the chain did not change the file - do not rewrite it
        }
        try {
            writeTarget(target, updated, stats);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to write source file: " + target, e);
        }
    }

    // ------------------------------------------------------------------
    // File I/O helpers (timed into PatchStats)
    // ------------------------------------------------------------------

    /** @return the file content, or {@code null} when the file does not exist. */
    private static String readTarget(Path target, PatchStats stats) throws IOException {
        long t = System.nanoTime();
        try {
            if (!Files.isRegularFile(target)) {
                return null;
            }
            var content = Files.readString(target, StandardCharsets.UTF_8);
            stats.filesRead++;
            return content;
        } finally {
            stats.readNanos += System.nanoTime() - t;
        }
    }

    private static void writeTarget(Path target, String content, PatchStats stats)
            throws IOException {
        long t = System.nanoTime();
        try {
            var parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent); // a new-file patch may introduce a package dir
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            stats.filesWritten++;
            stats.filesChanged++;
        } finally {
            stats.writeNanos += System.nanoTime() - t;
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private ParsedPatch parse(List<String> patchLines, String patchName, PatchStats stats) {
        long t = System.nanoTime();
        ParsedPatch patch;
        try {
            patch = parsePatch(patchLines, patchName);
        } finally {
            stats.parseNanos += System.nanoTime() - t;
        }
        stats.hunksParsed += patch.hunks.size();
        return patch;
    }

    /**
     * Parses a unified diff into hunks, validating as it goes:
     * hunk header counts are honoured so that headers, stray content and
     * truncated hunks are detected instead of silently corrupting the source.
     */
    private static ParsedPatch parsePatch(List<String> lines, String patchName) {
        var hunks = new ArrayList<Hunk>();
        String target = null;     // relative path from "+++ b/..."
        String oldTarget = null;  // raw path from "--- ..." ("/dev/null" for new files)
        boolean deletion = false;

        Hunk current = null;
        int hunkNumber = 0;
        int oldRemaining = 0;
        int newRemaining = 0;

        for (int li = 0; li < lines.size(); li++) {
            var line = lines.get(li);
            if (line.startsWith("@@")) {
                if (current != null) {
                    if (oldRemaining > 0 || newRemaining > 0) {
                        throw malformed(patchName, li + 1,
                            "hunk #" + hunkNumber + " declares " + (oldRemaining + newRemaining)
                            + " more content line(s) than the patch provides");
                    }
                    hunks.add(current);
                }
                var header = parseHunkHeader(line, patchName, li + 1);
                current = new Hunk();
                current.originalStart = header[0];
                oldRemaining = header[1];
                newRemaining = header[2];
                hunkNumber++;
                continue;
            }

            if (current != null && (oldRemaining > 0 || newRemaining > 0)) {
                // ---- inside a hunk: every line is content ----
                if (line.startsWith("\\ No newline")) {
                    continue; // metadata about the final newline, not content
                }
                if (line.isEmpty()) {
                    // legacy generators emit a bare "" instead of " " for blank context
                    oldRemaining--;
                    newRemaining--;
                    current.search.add("");
                    current.replace.add("");
                    continue;
                }
                switch (line.charAt(0)) {
                    case ' ' -> {
                        requireCount(oldRemaining > 0 && newRemaining > 0, patchName, li + 1, hunkNumber, line);
                        oldRemaining--;
                        newRemaining--;
                        var text = line.substring(1);
                        current.search.add(text);
                        current.replace.add(text);
                    }
                    case '-' -> {
                        requireCount(oldRemaining > 0, patchName, li + 1, hunkNumber, line);
                        oldRemaining--;
                        current.search.add(line.substring(1));
                    }
                    case '+' -> {
                        requireCount(newRemaining > 0, patchName, li + 1, hunkNumber, line);
                        newRemaining--;
                        current.replace.add(line.substring(1));
                    }
                    default -> throw malformed(patchName, li + 1,
                        "hunk #" + hunkNumber + " line does not start with ' ', '+' or '-': '"
                        + truncate(line) + "'");
                }
                continue;
            }

            // ---- outside a hunk: file headers and section metadata ----
            if (line.startsWith("--- ")) {
                oldTarget = line.substring(4);
            } else if (line.startsWith("+++ b/")) {
                var t = line.substring(6);
                if (target == null) {
                    target = t;
                } else if (!target.equals(t)) {
                    throw new PatchEngineException("Patch '" + patchName + "' modifies multiple "
                        + "files ('" + target + "' and '" + t + "'); one patch must target "
                        + "exactly one file");
                }
            } else if (line.startsWith("+++ /dev/null")) {
                deletion = true;
            } else if (!line.isEmpty()
                    && (line.charAt(0) == ' ' || line.charAt(0) == '+'
                        || line.charAt(0) == '-')) {
                throw malformed(patchName, li + 1,
                    "unexpected content outside any hunk (hunk header counts do not match): '"
                    + truncate(line) + "'");
            }
            // any other section line (diff --git, index, modes, ...) is ignored
        }

        if (current != null) {
            if (oldRemaining > 0 || newRemaining > 0) {
                throw malformed(patchName, lines.size(),
                    "hunk #" + hunkNumber + " declares " + (oldRemaining + newRemaining)
                    + " more content line(s) than the patch provides");
            }
            hunks.add(current);
        }
        return new ParsedPatch(patchName, target, oldTarget, deletion, hunks);
    }

    private static void requireCount(boolean ok, String patchName, int lineNo, int hunkNumber,
                                     String line) {
        if (!ok) {
            throw malformed(patchName, lineNo,
                "hunk #" + hunkNumber + " has more content lines than its header declares: '"
                + truncate(line) + "'");
        }
    }

    private static PatchEngineException malformed(String patchName, int lineNo, String detail) {
        return new PatchEngineException(
            "Patch '" + patchName + "': malformed patch at line " + lineNo + " - " + detail);
    }

    private static String truncate(String line) {
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    /**
     * Parses {@code @@ -oldStart[,oldCount] +newStart[,newCount] @@} without
     * allocating intermediate arrays for splitting.
     *
     * @return {oldStart, oldCount, newCount}
     */
    private static int[] parseHunkHeader(String line, String patchName, int lineNo) {
        try {
            int i = 2; // skip "@@"
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            if (i >= line.length() || line.charAt(i) != '-') {
                throw new IllegalArgumentException("expected '-'");
            }
            i++;
            int oldStart = scanNumber(line, i);
            i = scanNumberEnd(line, i);
            int oldCount = 1;
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                oldCount = scanNumber(line, i);
                i = scanNumberEnd(line, i);
            }
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            if (i >= line.length() || line.charAt(i) != '+') {
                throw new IllegalArgumentException("expected '+'");
            }
            i++;
            scanNumber(line, i); // newStart is not used
            i = scanNumberEnd(line, i);
            int newCount = 1;
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                newCount = scanNumber(line, i);
            }
            return new int[] {oldStart, oldCount, newCount};
        } catch (RuntimeException e) {
            throw new PatchEngineException("Patch '" + patchName
                + "': malformed hunk header at line " + lineNo + ": '" + truncate(line) + "'", e);
        }
    }

    /** Reads the decimal number at {@code from} (throws when there is none). */
    private static int scanNumber(String line, int from) {
        int i = from;
        while (i < line.length() && line.charAt(i) >= '0' && line.charAt(i) <= '9') {
            i++;
        }
        if (i == from) {
            throw new IllegalArgumentException("expected a number");
        }
        return Integer.parseInt(line, from, i, 10);
    }

    private static int scanNumberEnd(String line, int from) {
        int i = from;
        while (i < line.length() && line.charAt(i) >= '0' && line.charAt(i) <= '9') {
            i++;
        }
        return i;
    }

    /** Relative target path of a patch (first {@code +++ b/} line), or {@code "unknown"}. */
    static String targetKeyOf(List<String> patchLines) {
        for (var line : patchLines) {
            if (line.startsWith("+++ b/")) {
                return line.substring(6);
            }
        }
        return "unknown";
    }

    /** Resolves a patch's relative target path and refuses paths escaping the target dir. */
    static Path resolveTarget(String relativeTarget, Path targetDir, String patchName) {
        var sep = targetDir.getFileSystem().getSeparator().charAt(0);
        var normalized = targetDir.resolve(relativeTarget.replace('/', sep)).normalize();
        if (!normalized.startsWith(targetDir.normalize())) {
            throw new PatchEngineException("Patch '" + patchName
                + "' target escapes the target directory: " + relativeTarget);
        }
        return normalized;
    }

    private static String stripAPrefix(String path) {
        return path != null && path.startsWith("a/") ? path.substring(2) : String.valueOf(path);
    }

    // ------------------------------------------------------------------
    // In-memory application
    // ------------------------------------------------------------------

    /** One target file's lines plus the line separator of the original content. */
    private static final class SourceFile {
        final List<String> lines;
        final String lineSep;

        SourceFile(String content) {
            // Detected once from the original content: patch lines never contain CR,
            // so re-detecting per hunk (as the old code did) cannot change the result.
            lineSep = content.contains("\r\n") ? "\r\n" : "\n";
            lines = splitLines(content);
        }

        String join() {
            return String.join(lineSep, lines);
        }
    }

    /**
     * Splits content into lines exactly like {@code content.split("\\R", -1)}.
     *
     * <p>A JFR profile of the benchmark ({@code ./gradlew :patch-engine:benchmark -Pjfr})
     * showed this regex to be the hottest frame in the application phase - one
     * {@link java.util.regex.Matcher} allocated per target file - so the equivalent
     * single pass runs here instead. The equivalence is locked down by
     * {@code LineSplitTest}, which fuzzes both implementations against each other.
     *
     * <p>{@code \R} matches LF, CRLF, CR, VT, FF, NEL, LS and PS; the trailing empty
     * segment is kept so that {@link #join()} round-trips the original content.
     */
    static List<String> splitLines(String content) {
        var out = new ArrayList<String>();
        var len = content.length();
        if (len == 0) {
            out.add("");   // String.split returns the input itself when nothing matches
            return out;
        }
        int start = 0;
        int i = 0;
        while (i < len) {
            var c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(content.substring(start, i));
                if (c == '\r' && i + 1 < len && content.charAt(i + 1) == '\n') {
                    i++;   // CRLF is one line break, not two
                }
                i++;
                start = i;
            } else if (c == '\u000b' || c == '\f' || c == '\u0085'
                       || c == '\u2028' || c == '\u2029') {
                out.add(content.substring(start, i));
                i++;
                start = i;
            } else {
                i++;
            }
        }
        out.add(content.substring(start, len));   // trailing segment, even when empty
        return out;
    }

    private void applyHunks(SourceFile file, ParsedPatch patch, PatchStats stats,
                            int patchIndex, int patchCount) {
        long t = System.nanoTime();
        try {
            for (int h = 0; h < patch.hunks.size(); h++) {
                applyHunk(file, patch.hunks.get(h), h + 1, patch, patchIndex, patchCount);
            }
        } finally {
            stats.matchNanos += System.nanoTime() - t;
        }
    }

    /**
     * Applies one hunk by splicing its replacement into the in-memory line list.
     * The matched region is rebuilt from the patch's own lines (context included),
     * exactly like the original per-hunk string rebuild did.
     */
    private static void applyHunk(SourceFile file, Hunk hunk, int hunkNumber,
                                  ParsedPatch patch, int patchIndex, int patchCount) {
        if (hunk.search.isEmpty()) {
            // Pure insertion: same clamp semantics as the original insertAt().
            var insertPos = Math.min(Math.max(0, hunk.originalStart), file.lines.size());
            file.lines.addAll(insertPos, hunk.replace);
            return;
        }

        // Strategy 1: strip leading/trailing whitespace only
        var matchIdx = findSequence(file.lines, hunk.search, false);
        // Strategy 2: also collapse internal whitespace sequences
        if (matchIdx < 0) {
            matchIdx = findSequence(file.lines, hunk.search, true);
        }
        // Strategy 3: skip blank lines when comparing
        if (matchIdx < 0) {
            matchIdx = findSequenceSkippingBlanks(file.lines, hunk.search, true);
        }
        if (matchIdx < 0) {
            throw mismatch(file, hunk, hunkNumber, patch, patchIndex, patchCount);
        }

        var matched = file.lines.subList(matchIdx, matchIdx + hunk.search.size());
        matched.clear();
        file.lines.addAll(matchIdx, hunk.replace);
    }

    private static int findSequence(List<String> lines, List<String> search,
                                    boolean collapseInternal) {
        outer:
        for (int i = 0; i <= lines.size() - search.size(); i++) {
            for (int j = 0; j < search.size(); j++) {
                if (!normalizeEquals(lines.get(i + j), search.get(j), collapseInternal)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int findSequenceSkippingBlanks(List<String> lines, List<String> search,
                                                  boolean collapseInternal) {
        var nonBlankSearch = new ArrayList<String>(search.size());
        for (var l : search) {
            if (!l.isBlank()) {
                nonBlankSearch.add(l);
            }
        }
        if (nonBlankSearch.isEmpty() || nonBlankSearch.size() == search.size()) {
            return -1;
        }

        outer:
        for (int i = 0; i <= lines.size() - nonBlankSearch.size(); i++) {
            for (int j = 0; j < nonBlankSearch.size(); j++) {
                if (!normalizeEquals(lines.get(i + j), nonBlankSearch.get(j), collapseInternal)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean normalizeEquals(String a, String b, boolean collapseInternal) {
        var na = a.strip();
        var nb = b.strip();
        if (collapseInternal) {
            na = WHITESPACE_RUN.matcher(na).replaceAll(" ");
            nb = WHITESPACE_RUN.matcher(nb).replaceAll(" ");
        }
        return na.equals(nb);
    }

    /** Content of a file created by a {@code --- /dev/null} patch (all added lines). */
    private static String newFileContent(ParsedPatch patch) {
        var sb = new StringBuilder();
        for (var hunk : patch.hunks) {
            for (var line : hunk.replace) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Builds the full failure report: patch name, position in the chain, target
     * file, hunk, expected context, actual surrounding lines and the reason.
     */
    private static PatchEngineException mismatch(SourceFile file, Hunk hunk, int hunkNumber,
                                                 ParsedPatch patch, int patchIndex, int patchCount) {
        var sb = new StringBuilder();
        sb.append("Patch '").append(patch.name).append("' (").append(patchIndex)
          .append(" of ").append(patchCount).append(") failed on '").append(patch.target)
          .append("': hunk #").append(hunkNumber)
          .append(" context mismatch (near original line ").append(hunk.originalStart)
          .append(")\n");
        sb.append("  expected context (from the patch):\n");
        appendCapped(sb, hunk.search, "    - ", 12);
        var pos = Math.min(Math.max(hunk.originalStart - 1, 0),
            Math.max(file.lines.size() - 1, 0));
        var from = Math.max(pos - 3, 0);
        var to = Math.min(pos + 6, file.lines.size());
        sb.append("  actual source lines ").append(from + 1).append('-').append(to)
          .append(" (around the expected position):\n");
        for (int i = from; i < to; i++) {
            sb.append("    + ").append(file.lines.get(i)).append('\n');
        }
        sb.append("  reason: no matching context found (tried exact, whitespace-tolerant "
            + "and blank-tolerant comparison)");
        return new PatchEngineException(sb.toString());
    }

    private static void appendCapped(StringBuilder sb, List<String> lines, String prefix,
                                     int cap) {
        for (int i = 0; i < lines.size() && i < cap; i++) {
            sb.append(prefix).append(lines.get(i)).append('\n');
        }
        if (lines.size() > cap) {
            sb.append("    ... ").append(lines.size() - cap).append(" more line(s)\n");
        }
    }

    // ------------------------------------------------------------------
    // Parsed representation
    // ------------------------------------------------------------------

    /** One parsed patch: its target plus validated hunks. */
    private static final class ParsedPatch {
        final String name;
        final String target;    // relative path from "+++ b/...", or null
        final String oldTarget; // raw "--- ..." path, or null
        final boolean deletion; // "+++ /dev/null"
        final List<Hunk> hunks;

        ParsedPatch(String name, String target, String oldTarget, boolean deletion,
                    List<Hunk> hunks) {
            this.name = name;
            this.target = target;
            this.oldTarget = oldTarget;
            this.deletion = deletion;
            this.hunks = hunks;
        }

        /** True when the patch's own header says it creates the target file. */
        boolean createsFile() {
            return oldTarget != null && oldTarget.equals("/dev/null");
        }
    }

    /** One hunk: what to find and what to put in its place. */
    private static final class Hunk {
        int originalStart;
        final List<String> search = new ArrayList<>();
        final List<String> replace = new ArrayList<>();
    }
}
