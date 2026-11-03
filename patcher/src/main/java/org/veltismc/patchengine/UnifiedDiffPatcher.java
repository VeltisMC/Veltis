package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure-Java unified diff application. No git, no external tools, no temp files.
 *
 * <p>Work is organised as read-process-write per target file, so a file is
 * touched at most twice no matter how many patches address it:
 *
 * <pre>
 * read target once
 *   -&gt; apply every patch of its chain in memory, in order
 *   -&gt; verify the result
 *   -&gt; write once, atomically, and only when the content changed
 * </pre>
 *
 * <p>The three properties that matter:
 *
 * <ul>
 *   <li><b>Atomic replacement.</b> Content is staged on a fixed
 *       {@code .writing} sibling and moved into place, so a crash or a
 *       failure mid-write can never leave a half-written source file.</li>
 *   <li><b>Verified output.</b> After a patch is applied in memory the engine
 *       checks that the line count changed by exactly the sum of the hunk
 *       deltas. A hunk that matched the wrong place, or a matcher that silently
 *       did nothing, is caught before anything is written.</li>
 *   <li><b>Left alone when nothing changed.</b> A chain whose result equals the
 *       original content does not rewrite the file at all, so timestamps and
 *       downstream up-to-date checks stay meaningful.</li>
 * </ul>
 *
 * <p>Matching tries exact content first, then a whitespace-tolerant pass, then
 * a blank-skipping pass. This tolerance is what lets a patch written against a
 * lightly reformatted decompile still apply, without ever guessing: if all three
 * fail, the build stops with the expected context and the actual surrounding
 * lines.
 */
public final class UnifiedDiffPatcher {

    /** Precompiled: used by the whitespace-tolerant fallback matcher. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Sibling file used to stage content before the atomic move into place.
     *
     * <p>The suffix is fixed, not generated, so there is exactly one staging path
     * per target file and a crashed run leaves something a later run overwrites
     * rather than accumulating behind it. It exists because
     * {@link java.nio.file.Files#move} with {@code ATOMIC_MOVE} is the only way
     * to replace a file such that a reader never observes a half-written one;
     * writing the target directly would trade that guarantee for nothing.
     */
    private static final String STAGING_SUFFIX = ".writing";

    private final String minecraftVersion;

    public UnifiedDiffPatcher() {
        this("<unknown>");
    }

    public UnifiedDiffPatcher(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    // ------------------------------------------------------------------
    // Single-patch convenience API
    // ------------------------------------------------------------------

    /** Parses, applies and writes one patch's lines against {@code workspaceRoot}. */
    public void applyPatchLines(List<String> patchLines, String patchName, Path workspaceRoot) {
        applyPatchLines(patchLines, patchName, workspaceRoot, new PatchStats());
    }

    public void applyPatchLines(List<String> patchLines, String patchName, Path workspaceRoot,
                                PatchStats stats) {
        var parsed = VeltisPatch.parse(patchLines, patchName, PatchCategory.CODE,
            "<in-memory>", minecraftVersion);
        var patch = new VeltisPatch(patchName, PatchCategory.CODE, Path.of(patchName),
            "<in-memory>", patchLines, parsed);
        for (var file : parsed.files) {
            applyTarget(file.target(),
                List.of(new ChainEntry(patch, file)),
                workspaceRoot, stats);
        }
    }

    // ------------------------------------------------------------------
    // One target file: read -> apply the chain -> verify -> write once
    // ------------------------------------------------------------------

    /** One patch's contribution to one target file. */
    record ChainEntry(VeltisPatch patch, ParsedPatch.FileDiff file) {
    }

    /**
     * Applies an ordered chain of patches to the single target they all address.
     *
     * @param targetKey  workspace-relative path (forward slashes) shared by the chain
     * @param chain      patches in their deterministic application order
     */
    void applyTarget(String targetKey, List<ChainEntry> chain, Path workspaceRoot,
                     PatchStats stats) {
        var target = resolveTarget(targetKey, workspaceRoot, chain.get(0).patch());

        // 1) Read the target once. A file that does not exist yet is a creating
        //    patch's business, not an error.
        long readStart = System.nanoTime();
        String original;
        try {
            original = Files.isRegularFile(target)
                ? Files.readString(target, StandardCharsets.UTF_8)
                : null;
            stats.filesRead++;
        } catch (IOException e) {
            stats.readNanos += System.nanoTime() - readStart;
            throw PatchFailure.of(chain.get(0).patch(), targetKey, "read",
                "the target file could not be read: " + MojangMetadata.rootMessage(e),
                minecraftVersion).toException();
        }
        stats.readNanos += System.nanoTime() - readStart;

        // 2) Apply the whole chain in memory, verifying each patch's contribution.
        var result = applyChain(targetKey, original, chain, stats);
        if (result == null) {
            return;   // every patch in the chain was a no-op
        }
        var updated = result.content();
        if (!result.created() && updated.equals(original)) {
            stats.filesUnchanged++;
            return;   // the chain did not change the file, so do not rewrite it
        }

        // 3) Replace atomically: a failed write leaves the previous content intact.
        long writeStart = System.nanoTime();
        try {
            var parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);   // a new-file patch may add a package dir
            }
            var staging = target.resolveSibling(target.getFileName() + STAGING_SUFFIX);
            try {
                Files.writeString(staging, updated, StandardCharsets.UTF_8);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
            stats.filesWritten++;
            stats.filesChanged++;
        } catch (IOException e) {
            throw PatchFailure.of(chain.get(0).patch(), targetKey, "write",
                "the patched content could not be written: " + MojangMetadata.rootMessage(e)
                    + "; the file was left unchanged",
                minecraftVersion).toException();
        } finally {
            stats.writeNanos += System.nanoTime() - writeStart;
        }
    }

    // ------------------------------------------------------------------
    // In-memory application
    // ------------------------------------------------------------------

    /**
     * What a chain did to one target.
     *
     * @param created the chain produced the file from nothing, so content equal to
     *                the "unchanged" case is still a real change
     */
    record ChainResult(String content, boolean created) {
    }

    /**
     * Applies a chain to content held in memory, with no filesystem involved.
     *
     * <p>This is the whole of the patcher's semantics; {@link #applyTarget} is a
     * thin read/write wrapper around it. The split is not tidiness for its own
     * sake: {@link PatchRebuilder} has to know what each patch in a chain
     * <em>individually</em> did, and the only way to find out is to replay the
     * chain one patch at a time -- something a rebuild cannot do through the
     * filesystem, because the intermediate states do not exist on disk.
     *
     * @param original the target's current content, or {@code null} if it does not
     *                 exist yet
     * @return the patched content, or {@code null} if every patch was a no-op
     * @throws PatchEngineException naming the patch, the target, and which patch
     *                              of the chain disagreed
     */
    ChainResult applyChain(String targetKey, String original, List<ChainEntry> chain,
                           PatchStats stats) {
        SourceFile file = original == null ? null : new SourceFile(original);
        var created = false;
        for (int i = 0; i < chain.size(); i++) {
            var entry = chain.get(i);
            var patch = entry.patch();
            var diff = entry.file();
            if (diff.deletion()) {
                throw PatchFailure.of(patch, targetKey, "file header",
                    "the patch deletes this file; VeltisMC patches may only create or modify"
                        + " files, never remove them from the decompiled source",
                    minecraftVersion).toException();
            }
            if (diff.hunks().isEmpty()) {
                continue;   // a section without hunks changes nothing
            }
            // Counted here, not in applyHunks, so a patch that creates the file is
            // accounted for exactly like one that modifies it.
            stats.patchesApplied++;
            stats.hunksParsed += diff.hunks().size();
            if (file == null) {
                if (!diff.createsFile()) {
                    throw PatchFailure.of(patch, targetKey,
                        "patch " + (i + 1) + " of " + chain.size(),
                        "the target file does not exist, and this patch does not create it;"
                            + " new files must be declared with a '--- /dev/null' header",
                        minecraftVersion).toException();
                }
                file = new SourceFile(synthesizeNewFile(diff, patch, targetKey));
                created = true;
                continue;
            }
            applyHunks(file, diff, patch, targetKey, i + 1, chain.size(), stats);
        }
        return file == null ? null : new ChainResult(file.join(), created);
    }

    /** One target file's lines plus the line separator of the original content. */
    private static final class SourceFile {
        final List<String> lines;
        final String lineSep;

        SourceFile(String content) {
            // Detected once from the original content: patch lines never contain
            // CR, so re-detecting per hunk cannot change the result.
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
     * <p>{@code \R} matches LF, CRLF, CR, VT, FF, NEL, LS and PS; the trailing
     * empty segment is kept so {@link SourceFile#join()} round-trips the
     * original content. Equivalent to the regex form, without allocating a
     * {@link java.util.regex.Matcher} per target file; the equivalence is
     * locked down by {@code LineSplitTest}, which fuzzes both implementations
     * against each other.
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

    private void applyHunks(SourceFile file, ParsedPatch.FileDiff diff, VeltisPatch patch,
                            String targetKey, int patchIndex, int patchCount, PatchStats stats) {
        var before = file.lines.size();
        var expectedDelta = 0;
        long matchStart = System.nanoTime();
        for (int h = 0; h < diff.hunks().size(); h++) {
            expectedDelta += applyHunk(file, diff.hunks().get(h), h + 1, diff, patch, targetKey,
                patchIndex, patchCount);
        }
        stats.matchNanos += System.nanoTime() - matchStart;

        // Post-apply invariant: a hunk either replaced exactly the lines it
        // matched or failed loudly above, so the count must move by exactly the
        // sum of the declared deltas. Anything else means the result is not what
        // the patch asked for, and it must not reach the disk.
        if (file.lines.size() != before + expectedDelta) {
            throw PatchFailure.of(patch, targetKey,
                "hunks 1-" + diff.hunks().size() + " of patch " + patchIndex + " of " + patchCount,
                "the patched file has " + file.lines.size() + " lines but the patch requires "
                    + (before + expectedDelta) + "; the file was not modified",
                minecraftVersion).toException();
        }
    }

    /**
     * Applies one hunk by splicing its replacement into the in-memory line list.
     *
     * <p>Added lines come from the patch. Context lines come from the file. The
     * distinction is not cosmetic: a hunk is allowed to have matched modulo
     * whitespace or with blank lines skipped, and in that case the patch's copy of
     * a context line is not byte-identical to the source. Writing it back would
     * silently reformat lines the patch never meant to touch — the same thing git
     * avoids by using context only to locate the hunk.
     *
     * @return the net change this hunk made to the line count
     */
    private int applyHunk(SourceFile file, ParsedPatch.Hunk hunk, int hunkNumber,
                          ParsedPatch.FileDiff diff, VeltisPatch patch, String targetKey,
                          int patchIndex, int patchCount) {
        if (hunk.search.isEmpty()) {
            // Pure insertion at the declared position, clamped to the file.
            var insertPos = Math.min(Math.max(0, hunk.originalStart), file.lines.size());
            file.lines.addAll(insertPos, hunk.replace);
            return hunk.replace.size();
        }

        var match = locate(file.lines, hunk.search);
        if (match == null) {
            throw PatchFailure.of(patch, targetKey,
                "patch " + patchIndex + " of " + patchCount + ", hunk #" + hunkNumber,
                describeMismatch(file, hunk, hunkNumber, patchIndex, patchCount),
                minecraftVersion).toException();
        }

        var replacement = new ArrayList<String>(hunk.replace.size());
        for (int i = 0; i < hunk.replace.size(); i++) {
            var from = hunk.contextSearchIndex(i);
            replacement.add(from >= 0 && match.positions[from] >= 0
                ? file.lines.get(match.positions[from])
                : hunk.replace.get(i));
        }
        file.lines.subList(match.start, match.start + match.span).clear();
        file.lines.addAll(match.start, replacement);
        return replacement.size() - match.span;
    }

    /**
     * Where a hunk's search lines landed in the file.
     *
     * @param start     first file line the hunk consumes
     * @param span      how many consecutive file lines it consumes
     * @param positions file index of each search line, -1 where a strategy skipped it
     */
    private record Match(int start, int span, int[] positions) {
    }

    /**
     * Locates the search lines, trying exact content, then whitespace-collapsed,
     * then blank-skipping. Returns null when none of the three matches, which is
     * the only case where the caller has to report a failure: nothing here ever
     * guesses a position.
     */
    private static Match locate(List<String> lines, List<String> search) {
        var exact = scan(lines, search, false, false);
        if (exact != null) {
            return exact;
        }
        var collapsed = scan(lines, search, true, false);
        if (collapsed != null) {
            return collapsed;
        }
        return scan(lines, search, true, true);
    }

    private static Match scan(List<String> lines, List<String> search,
                              boolean collapseInternal, boolean skipBlankSearchLines) {
        // Under the blank-skipping strategy only the non-blank search lines are
        // matched, and they must still be consecutive in the file; a strategy that
        // could skip file lines too would happily match in the wrong place.
        int[] wanted = null;
        if (skipBlankSearchLines) {
            var nonBlank = new ArrayList<Integer>(search.size());
            for (int i = 0; i < search.size(); i++) {
                if (!search.get(i).isBlank()) {
                    nonBlank.add(i);
                }
            }
            if (nonBlank.isEmpty() || nonBlank.size() == search.size()) {
                return null;   // nothing this strategy can add
            }
            wanted = nonBlank.stream().mapToInt(Integer::intValue).toArray();
        }
        var width = wanted == null ? search.size() : wanted.length;

        outer:
        for (int i = 0; i + width <= lines.size(); i++) {
            var positions = new int[search.size()];
            java.util.Arrays.fill(positions, -1);
            for (int k = 0; k < width; k++) {
                var s = wanted == null ? k : wanted[k];
                if (!normalizeEquals(lines.get(i + k), search.get(s), collapseInternal)) {
                    continue outer;
                }
                positions[s] = i + k;
            }
            return new Match(i, width, positions);
        }
        return null;
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

    /** Content of a file created by a {@code --- /dev/null} section: every added line. */
    private static String synthesizeNewFile(ParsedPatch.FileDiff diff, VeltisPatch patch,
                                            String targetKey) {
        var lines = new ArrayList<String>();
        for (var hunk : diff.hunks()) {
            for (var line : hunk.replace) {
                lines.add(line);
            }
        }
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    /**
     * Describes a hunk that matched nothing: what the patch asked for and what
     * the file actually holds around the expected position. Kept as a string so
     * the caller decides how to wrap it.
     */
    private static String describeMismatch(SourceFile file, ParsedPatch.Hunk hunk,
                                           int hunkNumber, int patchIndex, int patchCount) {
        var reason = new StringBuilder();
        reason.append("hunk #").append(hunkNumber)
              .append(" (near original line ").append(hunk.originalStart)
              .append(") found no matching context: tried exact, whitespace-tolerant and "
                      + "blank-tolerant comparison\n");
        reason.append("  expected context (from the patch):\n");
        appendCapped(reason, hunk.search, "    - ", 12);
        var pos = Math.min(Math.max(hunk.originalStart - 1, 0),
            Math.max(file.lines.size() - 1, 0));
        var from = Math.max(pos - 3, 0);
        var to = Math.min(pos + 6, file.lines.size());
        reason.append("  actual source lines ").append(from + 1).append('-').append(to)
              .append(" (around the expected position):\n");
        for (int i = from; i < to; i++) {
            reason.append("    + ").append(file.lines.get(i)).append('\n');
        }
        return reason.toString().stripTrailing();
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

    /** Resolves a patch's relative target path and refuses paths escaping the workspace. */
    Path resolveTarget(String relativeTarget, Path workspaceRoot, VeltisPatch patch) {
        var sep = workspaceRoot.getFileSystem().getSeparator().charAt(0);
        var normalized = workspaceRoot.resolve(relativeTarget.replace('/', sep)).normalize();
        if (!normalized.startsWith(workspaceRoot.normalize())) {
            throw PatchFailure.of(patch, relativeTarget, "target resolution",
                "the target path escapes the patched workspace: " + relativeTarget,
                minecraftVersion).toException();
        }
        return normalized;
    }

    // ------------------------------------------------------------------
    // Diff generation (rebuild)
    // ------------------------------------------------------------------

    /**
     * Produces a git-style unified diff between two texts, in the exact format
     * this engine parses. Used by {@code rebuildVeltisPatches} so a regenerated
     * patch is applied by the same code path that consumes a hand-written one.
     */
    public static List<String> generate(String target, String oldText, String newText) {
        return DiffGenerator.diff(target, oldText, newText);
    }
}
