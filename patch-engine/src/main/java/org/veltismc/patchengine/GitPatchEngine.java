package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the patch set with Git. Veltis still decides the order — discovery
 * gives {@code code}, then {@code data}, then {@code modules}, and file name
 * order inside each — but Git decides whether a patch applies and what bytes it
 * produces. The custom unified-diff applier is no longer the authority here.
 *
 * <p>Application runs in three steps:
 *
 * <ol>
 *   <li><b>Stage.</b> Every target is read once and written into a scratch
 *       directory. Reading here is the only read of the real targets the run
 *       performs, which is what makes {@link PatchStats#filesRead} a count of
 *       targets rather than of I/O.</li>
 *   <li><b>Apply.</b> One {@code git apply} call for the whole set, in
 *       discovery order. Git reads the patches cumulatively, so a later patch
 *       may build on an earlier one, and one process covers the entire set
 *       instead of one process per patch.</li>
 *   <li><b>Publish.</b> Only the staged files whose bytes actually differ are
 *       copied back into the patched workspace, so {@link PatchStats#filesWritten}
 *       means "written because it changed" and a target Git left alone is never
 *       touched on disk.</li>
 * </ol>
 *
 * <p>Staging is what makes a failure safe. Git does not roll back a batch that
 * fails partway through, so applying straight into the workspace would leave
 * earlier patches applied and later ones not. Because the batch runs against a
 * scratch copy, a rejected set leaves the workspace exactly as it was, and the
 * attribution pass below can re-run the set one patch at a time against a clean
 * copy to name the patch that actually failed.
 *
 * <p>Line endings are pinned on every invocation. Git is configured per machine
 * and a default of {@code core.autocrlf=true} would rewrite LF patches into
 * CRLF files on some checkouts and not others, which would make the patched
 * bytes — and therefore the compiled classes — a function of who ran the build.
 */
final class GitPatchEngine {

    /** A Git that hangs must fail the build rather than hang it. */
    private static final long TIMEOUT_SECONDS = 120;

    private static final String AUTOCLF = "core.autocrlf=false";
    private static final String EOL = "core.eol=lf";

    private final String minecraftVersion;

    GitPatchEngine(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    /**
     * Applies every patch to {@code patchedRoot}.
     *
     * @param patchedRoot the patched workspace; already holds a pristine mirror
     * @param patches     the discovered set, in the order they must be applied
     * @return counters for the run; every counter is a count this class made
     * @throws PatchEngineException a rendered {@code [VeltisPatch]} report naming
     *                              the patch, the target and Git's reason
     */
    PatchStats apply(Path patchedRoot, List<VeltisPatch> patches) {
        var stats = new PatchStats();
        stats.patchesDiscovered = patches.size();
        if (patches.isEmpty()) {
            return stats;
        }

        // Distinct targets in first-seen order, resolved once each. A path that
        // escapes the workspace is refused before Git is ever told about it.
        var resolved = new LinkedHashMap<String, Path>();
        for (var patch : patches) {
            for (var file : patch.parsed().files) {
                resolved.putIfAbsent(file.target(), resolve(file.target(), patchedRoot, patch));
            }
        }

        // Discovery has already parsed and validated every patch, so counting
        // the hunks Git is about to apply reads nothing new.
        var hunks = 0;
        for (var patch : patches) {
            for (var file : patch.parsed().files) {
                hunks += file.hunks().size();
            }
        }
        stats.hunksParsed = hunks;

        var snapshot = new LinkedHashMap<String, byte[]>();
        long readStart = System.nanoTime();
        try {
            for (var entry : resolved.entrySet()) {
                snapshot.put(entry.getKey(), readIfPresent(entry.getValue()));
                stats.filesRead++;
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to read the patched workspace\n"
                    + "  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        stats.readNanos += System.nanoTime() - readStart;

        Path stage = null;
        try {
            stage = Files.createTempDirectory("veltis-apply");
            writeStaging(stage, snapshot);

            var patchFiles = patches.stream().map(VeltisPatch::file).toList();
            long applyStart = System.nanoTime();
            var batch = git(stage, List.of("apply"), patchFiles);
            stats.matchNanos += System.nanoTime() - applyStart;

            if (batch.exitCode != 0) {
                // Git leaves the successfully applied prefix behind when a later
                // patch is rejected. Rewinding first is what makes the pass below
                // name the patch that failed rather than the one before it.
                writeStaging(stage, snapshot);
                throw attribute(stage, snapshot, patches, batch);
            }

            publish(stage, snapshot, resolved, stats);
            stats.patchesApplied = patches.size();
            return stats;
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to apply the patch set\n"
                    + "  Reason: " + MojangMetadata.rootMessage(e), e);
        } finally {
            if (stage != null) {
                VeltisWorkspace.deleteTree(stage);
            }
        }
    }

    /**
     * Finds which patch of the set Git refused, and reports it.
     *
     * <p>The batch already proved the set does not apply as a whole; replaying it
     * one patch at a time against a rewound copy makes the first patch that fails
     * the answer, so the report names that patch rather than the batch. The
     * workspace is not involved in either step.
     */
    private PatchEngineException attribute(Path stage, Map<String, byte[]> snapshot,
                                           List<VeltisPatch> patches, GitResult batch) {
        for (int i = 0; i < patches.size(); i++) {
            var patch = patches.get(i);
            var single = git(stage, List.of("apply"), List.of(patch.file()));
            if (single.exitCode != 0) {
                return report(patch, i + 1, patches.size(), stage, single.stderr);
            }
        }
        // Each patch applies alone but not together: the conflict is between two
        // of them, and Git's own message is the only description of it.
        var last = patches.get(patches.size() - 1);
        return report(last, patches.size(), patches.size(), stage, batch.stderr);
    }

    /**
     * Renders Git's rejection as a Veltis failure.
     *
     * <p>Git decides that the patch does not apply; this decides how to say so.
     * The extra detail — which patch of the set, which hunk, what the patch asked
     * for and what the file holds instead — is read from the patch's own parse
     * and from the file Git just looked at. It is a second opinion about the
     * <em>explanation</em>, never about the verdict.
     */
    private PatchEngineException report(VeltisPatch patch, int patchIndex, int patchCount,
                                        Path stage, String stderr) {
        var target = patch.primaryTarget();
        var line = -1;
        var gitReason = "";
        for (var raw : stderr.split("\\R")) {
            var text = raw.strip();
            var rejected = "error: patch failed: ";
            if (text.startsWith(rejected)) {
                var at = text.substring(rejected.length());
                var colon = at.lastIndexOf(':');
                var suffix = colon > 0 ? at.substring(colon + 1) : "";
                if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                    target = at.substring(0, colon);
                    line = Integer.parseInt(suffix);
                } else {
                    target = at;
                }
            } else if (text.startsWith("error: ")) {
                var detail = text.substring("error: ".length());
                var split = detail.indexOf(": ");
                var message = split >= 0 ? detail.substring(split + 2) : detail;
                if (!message.isBlank()) {
                    gitReason = message;
                }
            }
        }

        var section = sectionFor(patch, target);
        var hunk = hunkAt(section, line);
        var location = new StringBuilder("patch ").append(patchIndex).append(" of ")
            .append(patchCount);
        if (hunk != null) {
            location.append(", hunk #").append(section.hunks().indexOf(hunk) + 1);
        }

        var reason = describe(patch, stage, target, line, hunk, gitReason);
        return PatchFailure.of(patch, target, location.toString(), reason, minecraftVersion)
            .toException();
    }

    /** The section of {@code patch} that addresses {@code target}, if it has one. */
    private static ParsedPatch.FileDiff sectionFor(VeltisPatch patch, String target) {
        for (var file : patch.parsed().files) {
            if (file.target().equals(target)) {
                return file;
            }
        }
        return null;
    }

    /**
     * The hunk Git failed on, found by the line it reported.
     *
     * <p>Git names the line in the file as it stood when it stopped, and a hunk
     * header names the line it expects, so the two refer to the same numbering —
     * both are against the result of the patches before this one. Falling back to
     * the first hunk keeps the report useful when Git names no line at all.
     */
    private static ParsedPatch.Hunk hunkAt(ParsedPatch.FileDiff section, int line) {
        if (section == null || section.hunks().isEmpty()) {
            return null;
        }
        if (line > 0) {
            for (var hunk : section.hunks()) {
                var first = hunk.originalStart;
                var last = first + Math.max(hunk.search.size(), 1) - 1;
                if (line >= first && line <= last) {
                    return hunk;
                }
            }
        }
        return section.hunks().get(0);
    }

    /**
     * What the patch asked for, and what the file holds in its place.
     *
     * <p>A missing target is reported as such rather than as a mismatch: there is
     * no context to compare against, and telling a developer their line differs
     * from a file that is not there sends them looking in the wrong place. The
     * same reasoning applies to line endings — if the two sides disagree there,
     * no amount of quoting the patch's lines will explain the failure, because
     * the lines themselves are equal once the terminators are stripped.
     */
    private String describe(VeltisPatch patch, Path stage, String target, int line,
                            ParsedPatch.Hunk hunk, String gitReason) {
        byte[] targetBytes;
        try {
            targetBytes = Files.readAllBytes(staged(stage, target));
        } catch (NoSuchFileException absent) {
            return "the target does not exist in the patched workspace, so there is no file"
                + " for the patch to edit";
        } catch (IOException e) {
            return "the target could not be read back after Git rejected the patch ("
                + MojangMetadata.rootMessage(e) + ')';
        }

        var targetText = new String(targetBytes, java.nio.charset.StandardCharsets.UTF_8);
        var actual = List.of(targetText.split("\\R", -1));
        if (hasCrLf(targetBytes) && !hasCrLf(readPatch(patch))) {
            return "the target uses CRLF line endings while the patch uses LF; Git matches"
                + " context exactly, so the patch and the source must use the same line"
                + " endings. The decompiled source is written with LF and patch files are"
                + " pinned to eol=lf, so a CRLF file here means something rewrote it - the"
                + " target was left untouched";
        }

        if (hunk == null) {
            return gitReason.isBlank() ? "git apply rejected the patch" : gitReason;
        }

        var reason = new StringBuilder();
        reason.append("the file does not hold the context the patch requires; Git matches")
            .append(" context exactly, so the patch and the source must be identical")
            .append(" line for line, line endings included\n");
        reason.append("  expected context (from the patch):\n");
        cap(reason, hunk.search, "    - ", 12);

        var anchor = line > 0 ? line : hunk.originalStart;
        var position = Math.min(Math.max(anchor - 1, 0), Math.max(actual.size() - 1, 0));
        var from = Math.max(position - 3, 0);
        var to = Math.min(position + 6, actual.size());
        reason.append("  actual source lines ").append(from + 1).append('-').append(to)
            .append(" (around the expected position):\n");
        for (int i = from; i < to; i++) {
            reason.append("    + ").append(actual.get(i)).append('\n');
        }
        return reason.toString().stripTrailing();
    }

    /** Appends at most {@code cap} lines, then says how many were left out. */
    private static void cap(StringBuilder sb, List<String> lines, String prefix, int cap) {
        for (int i = 0; i < lines.size() && i < cap; i++) {
            sb.append(prefix).append(lines.get(i)).append('\n');
        }
        if (lines.size() > cap) {
            sb.append("    ... ").append(lines.size() - cap).append(" more line(s)\n");
        }
    }

    /** True when the bytes contain at least one CRLF pair. */
    private static boolean hasCrLf(byte[] bytes) {
        for (int i = 1; i < bytes.length; i++) {
            if (bytes[i] == '\n' && bytes[i - 1] == '\r') {
                return true;
            }
        }
        return false;
    }

    /** The patch file's bytes, or nothing when it cannot be read. */
    private static byte[] readPatch(VeltisPatch patch) {
        try {
            return Files.readAllBytes(patch.file());
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * Copies every file Git changed back into the workspace, and only those.
     *
     * <p>Compared against the snapshot rather than by mtime or size: a rewrite
     * that produces identical bytes is not a change and must not be written, or
     * a target would look edited to the next rebuild.
     */
    private void publish(Path stage, Map<String, byte[]> snapshot, Map<String, Path> resolved,
                         PatchStats stats) throws IOException {
        long writeStart = System.nanoTime();
        for (var entry : snapshot.entrySet()) {
            var target = entry.getKey();
            var before = entry.getValue();
            var staged = staged(stage, target);
            byte[] after;
            try {
                after = Files.readAllBytes(staged);
            } catch (NoSuchFileException absent) {
                after = null;
            }
            if (Arrays.equals(before, after)) {
                stats.filesUnchanged++;
                continue;
            }
            var destination = resolved.get(target);
            if (after == null) {
                Files.deleteIfExists(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.write(destination, after);
            }
            stats.filesChanged++;
            stats.filesWritten++;
        }
        stats.writeNanos += System.nanoTime() - writeStart;
    }

    // ------------------------------------------------------------------
    // Staging
    // ------------------------------------------------------------------

    /** Writes the snapshot into the scratch tree, removing what it does not hold. */
    private static void writeStaging(Path stage, Map<String, byte[]> snapshot) throws IOException {
        for (var entry : snapshot.entrySet()) {
            var staged = staged(stage, entry.getKey());
            var bytes = entry.getValue();
            if (bytes == null) {
                Files.deleteIfExists(staged);
                continue;
            }
            Files.createDirectories(staged.getParent());
            Files.write(staged, bytes);
        }
    }

    /** A target's path inside the scratch tree, refused if it escapes that tree. */
    private static Path staged(Path stage, String target) {
        var sep = stage.getFileSystem().getSeparator().charAt(0);
        var path = stage.resolve(target.replace('/', sep)).normalize();
        if (!path.startsWith(stage.normalize())) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to apply patch\n"
                    + "  Target: " + target + '\n'
                    + "  Reason: the target path escapes the patched workspace");
        }
        return path;
    }

    /** The target's path in the real workspace, refused if it escapes it. */
    private Path resolve(String relativeTarget, Path root, VeltisPatch patch) {
        var sep = root.getFileSystem().getSeparator().charAt(0);
        var path = root.resolve(relativeTarget.replace('/', sep)).normalize();
        if (!path.startsWith(root.normalize())) {
            throw PatchFailure.of(patch, relativeTarget, "target resolution",
                "the target path escapes the patched workspace: " + relativeTarget,
                minecraftVersion).toException();
        }
        return path;
    }

    private static byte[] readIfPresent(Path path) throws IOException {
        try {
            return Files.readAllBytes(path);
        } catch (NoSuchFileException absent) {
            return null;   // a patch may create the file rather than edit it
        }
    }

    // ------------------------------------------------------------------
    // Generating diffs (rebuild)
    // ------------------------------------------------------------------

    /**
     * Renders the difference between two versions of a file as a unified diff.
     *
     * <p>Both versions are written into a scratch directory at the target's own
     * relative path and compared with {@code git diff --no-index}. That is the
     * same Git that applies the set, so the engine that writes a patch and the
     * engine that judges it later cannot disagree about what a change looks
     * like; Veltis still decides which sections a patch holds, in what order,
     * and which slot each one is filed under.
     *
     * <p>What is normalised afterwards is shape, not content. Git's scratch
     * prefixes are rewritten back to {@code a/} and {@code b/}, the {@code index}
     * and mode lines are dropped because a Veltis patch records no blob hashes,
     * and the symbol Git appends to a hunk header is cut short so a header reads
     * {@code @@ -1,5 +1,6 @@} exactly as it does in every patch already in the
     * set. Leave those alone and the first rebuild after this change would
     * reword every patch in version control without a single line of code having
     * moved.
     *
     * @param target workspace-relative path of the file being diffed
     * @param before pristine content, {@code null} or empty when the file is new
     * @param after  patched content
     * @return the diff's lines with no trailing newline, empty when unchanged
     */
    static List<String> diff(String target, String before, String after) {
        if (after == null || (before != null && before.equals(after))) {
            return List.of();
        }
        if (after.isEmpty()) {
            // A patch that empties a file would drop it from the build, so it is
            // refused here rather than rendered into something applyable.
            throw new PatchEngineException(
                "[VeltisPatch] Failed to rebuild patch\n"
                    + "  Target: " + target
                    + "\n  Reason: the patched file is empty, so the diff would delete it;"
                    + " VeltisMC patches may only create or modify files, never delete"
                    + " them from the decompiled source");
        }

        var created = before == null || before.isEmpty();
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("veltis-diff");
            // Git takes /dev/null as a path of its own, which is how a creation
            // diff gets its --- /dev/null without a file existing to read.
            var oldSide = created ? "/dev/null" : place(scratch, "old", target, before);
            var newSide = place(scratch, "new", target, after);

            var result = git(scratch, List.of("--no-pager", "diff", "--no-index",
                "--src-prefix=a/", "--dst-prefix=b/", oldSide, newSide), List.of());
            if (result.exitCode == 0) {
                return List.of();   // nothing differs
            }
            // --no-index reports differences as 1 and every real problem from 2
            // up, but a path it cannot open also leaves an error on stderr at 1,
            // so the stream is the test rather than the code.
            if (result.exitCode != 1 || !result.stderr.isBlank()) {
                throw new PatchEngineException(
                    "[VeltisPatch] Failed to rebuild patch\n"
                        + "  Target: " + target
                        + "\n  Reason: " + (result.stderr.isBlank()
                            ? "git diff could not compare the two versions (exit "
                                + result.exitCode + ')'
                            : result.stderr.strip()));
            }
            return rewrite(target, result.stdout);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to rebuild patch\n"
                    + "  Target: " + target
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        } finally {
            if (scratch != null) {
                VeltisWorkspace.deleteTree(scratch);
            }
        }
    }

    /** Writes one side of a diff into the scratch tree and returns its Git path. */
    private static String place(Path scratch, String side, String target, String text)
        throws IOException {
        var path = scratch.resolve(side).resolve(target).normalize();
        if (!path.startsWith(scratch.resolve(side).normalize())) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to rebuild patch\n"
                    + "  Target: " + target
                    + "\n  Reason: the target path escapes the patched workspace");
        }
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return side + '/' + target;
    }

    /**
     * Turns Git's output into the layout the patch set already uses.
     *
     * <p>Only header lines are touched: rewriting a body line could rewrite the
     * very source text a patch is meant to carry.
     */
    private static List<String> rewrite(String target, String stdout) {
        var out = new ArrayList<String>();
        for (var raw : stdout.split("\\R")) {
            if (raw.isEmpty()) {
                continue;   // the single blank after a file's content, if any
            }
            var line = raw;
            if (line.startsWith("index ")
                || line.startsWith("old mode ")
                || line.startsWith("new mode ")
                || line.startsWith("new file mode ")
                || line.startsWith("deleted file mode ")
                || line.startsWith("similarity index ")) {
                continue;
            }
            if (line.startsWith("@@ ")) {
                // Cut "public class Commands {" and friends off the header. The
                // second "@@ " is where the header proper ends; with no trailing
                // symbol there is nothing to cut and the line stands.
                var close = line.indexOf("@@ ", 3);
                if (close > 0) {
                    line = line.substring(0, close + 2);
                }
            } else if (line.startsWith("diff --git ")) {
                line = line.replace("a/old/" + target, "a/" + target)
                    .replace("a/new/" + target, "a/" + target)
                    .replace("b/old/" + target, "b/" + target)
                    .replace("b/new/" + target, "b/" + target);
            } else if (line.startsWith("--- ")) {
                line = line.replace("a/old/" + target, "a/" + target)
                    .replace("a/new/" + target, "a/" + target);
            } else if (line.startsWith("+++ ")) {
                line = line.replace("b/old/" + target, "b/" + target)
                    .replace("b/new/" + target, "b/" + target);
            }
            out.add(line);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Running Git
    // ------------------------------------------------------------------

    /** Git's answer: exit code, both streams, and whether it was killed. */
    private record GitResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    /**
     * Runs one Git command with the scratch tree as the working directory.
     *
     * <p>Pinned {@code -c} settings come after {@code -C} and before the
     * subcommand so they apply to this invocation only: the machine's own Git
     * configuration must not be able to change what the build writes.
     */
    private static GitResult git(Path cwd, List<String> arguments, List<Path> files) {
        var command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.add("-c");
        command.add(AUTOCLF);
        command.add("-c");
        command.add(EOL);
        command.addAll(arguments);
        for (var file : files) {
            command.add(file.toString());
        }

        try {
            var process = new ProcessBuilder(command).start();
            var stderr = new AtomicReference<>(new byte[0]);
            var reader = Thread.ofPlatform().name("veltis-git-stderr").start(() -> {
                try (var stream = process.getErrorStream()) {
                    stderr.set(stream.readAllBytes());
                } catch (IOException ignored) {
                    // The exit code still says the command failed, and report()
                    // falls back to a generic reason when there is no text.
                }
            });

            byte[] stdout;
            try (var stream = process.getInputStream()) {
                stdout = stream.readAllBytes();
            }
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new GitResult(-1, new String(stdout, java.nio.charset.StandardCharsets.UTF_8),
                    "git did not finish within " + TIMEOUT_SECONDS + " seconds", true);
            }
            return new GitResult(process.exitValue(),
                new String(stdout, java.nio.charset.StandardCharsets.UTF_8),
                new String(stderr.get(), java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to apply patch\n"
                    + "  Target: <unknown>\n"
                    + "  Reason: Git could not be started (" + MojangMetadata.rootMessage(e)
                    + "); applying the patch set requires git on the PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PatchEngineException(
                "[VeltisPatch] Patch application was interrupted; the build was stopped", e);
        }
    }
}
