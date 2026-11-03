package org.veltismc.patchengine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * A patch file on disk, already read and parsed.
 *
 * <p>Discovery reads each patch exactly once, hashes it, and parses it once; the
 * patcher then only applies hunks. The public surface is deliberately small —
 * the parsed internals stay package-private so there is one obvious way to
 * consume a patch and no way to mutate one after discovery.
 */
public final class VeltisPatch {

    private final String name;
    private final PatchCategory category;
    private final Path file;
    private final String revision;
    private final List<String> lines;
    private final ParsedPatch parsed;

    VeltisPatch(String name, PatchCategory category, Path file, String revision,
                List<String> lines, ParsedPatch parsed) {
        this.name = name;
        this.category = category;
        this.file = file;
        this.revision = revision;
        this.lines = List.copyOf(lines);
        this.parsed = parsed;
    }

    /** The file name, which is also its identity inside a category. */
    public String name() {
        return name;
    }

    public PatchCategory category() {
        return category;
    }

    /** Absolute path of the patch file. */
    public Path file() {
        return file;
    }

    /** SHA-256 of the patch file's bytes; reported in every failure. */
    public String revision() {
        return revision;
    }

    /** The raw lines, kept for diff generation and diagnostics. */
    public List<String> lines() {
        return lines;
    }

    /**
     * The validated parse: per-file sections, in patch order.
     *
     * <p>Package-private and read-only, because exactly one caller outside
     * discovery needs it — {@link PatchRebuilder} replays a chain one patch at a
     * time to learn what each patch in it individually did, and that requires the
     * individual file sections rather than the flat list {@link #targets()}
     * returns.
     */
    ParsedPatch parsed() {
        return parsed;
    }

    /** Every workspace-relative target this patch addresses, in declaration order. */
    public List<String> targets() {
        return parsed.targets();
    }

    /** The first target this patch addresses; the common case is a single file. */
    public String primaryTarget() {
        var targets = targets();
        return targets.isEmpty() ? "" : targets.get(0);
    }

    /** A short human description such as {@code code/003-Improve-Logging.patch}. */
    public String describe() {
        return category.directoryName() + "/" + name;
    }

    @Override
    public String toString() {
        return describe();
    }

    // ------------------------------------------------------------------
    // Reading and parsing
    // ------------------------------------------------------------------

    /** Reads, hashes and parses a patch file. */
    static VeltisPatch load(Path file, PatchCategory category, String minecraftVersion) {
        var name = file.getFileName().toString();
        var lines = readLines(file);
        var revision = revisionOf(file);
        var parsed = parse(lines, name, category, revision, minecraftVersion);
        return new VeltisPatch(name, category, file.toAbsolutePath().normalize(),
            revision, lines, parsed);
    }

    /**
     * Parses patch text into validated per-file sections.
     *
     * <p>Multi-file patches are supported: every {@code ---}/{@code +++} header
     * pair opens a new section and each section keeps its own hunks. Both the
     * plain {@code +++ b/<path>} form and git's C-quoted variant (which git emits
     * for paths containing spaces or backslashes) are accepted, as is
     * {@code --- /dev/null} for a new file and {@code +++ /dev/null} for a
     * deletion.
     */
    static ParsedPatch parse(List<String> lines, String patchName, PatchCategory category,
                              String revision, String minecraftVersion) {
        var files = new ArrayList<ParsedPatch.FileDiff>();

        // Current file section, opened by "---" and completed by the next "---" or EOF.
        boolean sectionOpen = false;
        String oldTarget = null;
        String target = null;
        boolean deletion = false;
        List<ParsedPatch.Hunk> hunks = List.of();

        ParsedPatch.Hunk current = null;
        int hunkNumber = 0;
        int oldRemaining = 0;
        int newRemaining = 0;

        for (int li = 0; li < lines.size(); li++) {
            var line = lines.get(li);

            if (line.startsWith("@@")) {
                if (current != null) {
                    requireHunkComplete(current, hunkNumber, oldRemaining + newRemaining,
                        patchName, li + 1, category, revision, minecraftVersion);
                    hunks.add(current);
                    current = null;
                }
                if (target == null) {
                    throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                        oldTarget == null
                            ? "hunk #" + (hunkNumber + 1)
                                + " appears before any '---'/'+++' header pair"
                            : "'--- " + oldTarget + "' has no matching '+++' target header");
                }
                var header = parseHunkHeader(line, patchName, li + 1, category, revision,
                    minecraftVersion);
                current = new ParsedPatch.Hunk(header[0], header[1], header[2]);
                oldRemaining = header[1];
                newRemaining = header[2];
                hunkNumber++;
                continue;
            }

            if (current != null && (oldRemaining > 0 || newRemaining > 0)) {
                if (line.startsWith("\\ No newline")) {
                    continue;   // metadata about the final newline, not content
                }
                if (line.isEmpty()) {
                    // Legacy generators emit a bare "" instead of " " for blank context.
                    oldRemaining--;
                    newRemaining--;
                    current.search.add("");
                    current.replace.add("");
                    current.markContext();
                    continue;
                }
                switch (line.charAt(0)) {
                    case ' ' -> {
                        requireCount(oldRemaining > 0 && newRemaining > 0, patchName, li + 1,
                            hunkNumber, line, category, revision, minecraftVersion);
                        oldRemaining--;
                        newRemaining--;
                        var text = line.substring(1);
                        current.search.add(text);
                        current.replace.add(text);
                        current.markContext();
                    }
                    case '-' -> {
                        requireCount(oldRemaining > 0, patchName, li + 1, hunkNumber, line,
                            category, revision, minecraftVersion);
                        oldRemaining--;
                        current.search.add(line.substring(1));
                    }
                    case '+' -> {
                        requireCount(newRemaining > 0, patchName, li + 1, hunkNumber, line,
                            category, revision, minecraftVersion);
                        newRemaining--;
                        current.replace.add(line.substring(1));
                    }
                    default -> throw malformed(patchName, li + 1, category, revision,
                        minecraftVersion, "hunk #" + hunkNumber
                            + " line does not start with ' ', '+' or '-': '" + truncate(line) + "'");
                }
                continue;
            }

            // Outside a hunk body: file headers and section metadata.
            if (line.startsWith("--- ")) {
                // A section is closed by the next header or by EOF, so the hunk
                // that was being read belongs to the section being closed. Without
                // this the last hunk of file N would be carried into file N+1.
                if (current != null) {
                    requireHunkComplete(current, hunkNumber, oldRemaining + newRemaining,
                        patchName, li + 1, category, revision, minecraftVersion);
                    hunks.add(current);
                    current = null;
                }
                if (sectionOpen) {
                    if (target == null) {
                        throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                            "'--- " + oldTarget + "' has no matching '+++' target header");
                    }
                    files.add(new ParsedPatch.FileDiff(target, oldTarget, deletion, hunks));
                }
                sectionOpen = true;
                oldTarget = unquotePath(line.substring(4));
                target = null;
                deletion = false;
                hunks = new ArrayList<>();
                oldRemaining = 0;
                newRemaining = 0;
            } else if (line.startsWith("+++ ")) {
                if (!sectionOpen) {
                    throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                        "'+++' header at line " + (li + 1) + " has no preceding '---' header");
                }
                var declared = unquotePath(line.substring(4));
                if (declared.equals("/dev/null")) {
                    if (oldTarget.equals("/dev/null")) {
                        throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                            "'--- /dev/null' followed by '+++ /dev/null' declares no file");
                    }
                    target = stripAPrefix(oldTarget);
                    deletion = true;
                } else if (declared.startsWith("b/")) {
                    target = declared.substring(2);
                } else {
                    throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                        "target header must be '+++ b/<path>' or '+++ /dev/null', found: '"
                            + truncate(line) + "'");
                }
            } else if (!line.isEmpty()
                    && (line.charAt(0) == ' ' || line.charAt(0) == '+' || line.charAt(0) == '-')) {
                throw malformed(patchName, li + 1, category, revision, minecraftVersion,
                    "unexpected content outside any hunk (hunk header counts do not match): '"
                        + truncate(line) + "'");
            }
            // Any other section line (diff --git, index, mode, ...) is ignored.
        }

        if (current != null) {
            requireHunkComplete(current, hunkNumber, oldRemaining + newRemaining,
                patchName, lines.size(), category, revision, minecraftVersion);
            hunks.add(current);
        }
        if (sectionOpen) {
            if (target == null) {
                throw malformed(patchName, lines.size(), category, revision, minecraftVersion,
                    "'--- " + oldTarget + "' has no matching '+++' target header");
            }
            files.add(new ParsedPatch.FileDiff(target, oldTarget, deletion, hunks));
        }
        if (files.isEmpty()) {
            throw malformed(patchName, lines.size(), category, revision, minecraftVersion,
                "the patch declares no file to change (no '---'/'+++' header pair)");
        }
        return new ParsedPatch(files);
    }

    /** Strips git's C-style quoting and escapes from a header path. */
    static String unquotePath(String path) {
        var value = path.trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\t", "\t");
    }

    private static void requireHunkComplete(ParsedPatch.Hunk hunk, int hunkNumber, int missing,
                                            String patchName, int lineNo, PatchCategory category,
                                            String revision, String minecraftVersion) {
        if (missing > 0) {
            throw malformed(patchName, lineNo, category, revision, minecraftVersion,
                "hunk #" + hunkNumber + " declares " + missing
                    + " more content line(s) than the patch provides");
        }
    }

    private static void requireCount(boolean ok, String patchName, int lineNo, int hunkNumber,
                                     String line, PatchCategory category, String revision,
                                     String minecraftVersion) {
        if (!ok) {
            throw malformed(patchName, lineNo, category, revision, minecraftVersion,
                "hunk #" + hunkNumber + " has more content lines than its header declares: '"
                    + truncate(line) + "'");
        }
    }

    static PatchEngineException malformed(String patchName, int lineNo, PatchCategory category,
                                          String revision, String minecraftVersion, String detail) {
        return PatchFailure.of(
                patchName,
                category == null ? PatchCategory.CODE : category,
                "<unknown>",
                "patch line " + lineNo,
                detail,
                minecraftVersion == null ? "<unknown>" : minecraftVersion,
                revision)
            .toException();
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
    private static int[] parseHunkHeader(String line, String patchName, int lineNo,
                                         PatchCategory category, String revision,
                                         String minecraftVersion) {
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
            scanNumber(line, i);   // newStart is advisory; matching searches by content
            i = scanNumberEnd(line, i);
            int newCount = 1;
            if (i < line.length() && line.charAt(i) == ',') {
                i++;
                newCount = scanNumber(line, i);
            }
            return new int[] {oldStart, oldCount, newCount};
        } catch (RuntimeException e) {
            throw malformed(patchName, lineNo, category, revision, minecraftVersion,
                "malformed hunk header: '" + truncate(line) + "'");
        }
    }

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

    private static String stripAPrefix(String path) {
        return path != null && path.startsWith("a/") ? path.substring(2) : path;
    }

    /** SHA-256 of the patch file's bytes, used as the patch revision. */
    static String revisionOf(Path patchFile) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(patchFile)));
        } catch (Exception e) {
            throw new PatchEngineException(
                "Failed to hash the patch file " + patchFile + ": " + e.getMessage(), e);
        }
    }

    static List<String> readLines(Path patchFile) {
        try {
            return Files.readAllLines(patchFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PatchEngineException(
                "Failed to read the patch file " + patchFile + ": " + e.getMessage(), e);
        }
    }
}
