package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Applies the patch set to the patched workspace.
 *
 * <p><b>Git applies the set; this class orders and reports it.</b> The set is
 * handed to {@link GitPatchEngine} as a whole and in discovery order —
 * {@code code}, then {@code data}, then {@code modules}, by file name inside
 * each — and Git decides whether every patch lands and what bytes come out.
 * Nothing here re-implements hunk matching: a patch that Git rejects is
 * reported, not worked around, so the development patch set cannot drift into
 * being understood by two different engines that disagree.
 *
 * <p><b>Failures are reported once, by the patch that caused them.</b> The
 * engine replays a rejected set to find the first patch that does not apply and
 * renders it as a {@link PatchFailure}, which names the patch, the category, the
 * target, the line and Git's reason. Because the set is applied as a unit, a
 * failure leaves the workspace exactly as the pristine mirror made it — the next
 * run starts from the same state as a fresh clone.
 *
 * <p><b>The worker count does not reach the result.</b> It is kept as a build
 * setting and reported by {@link #workers()}, but Git takes the set in one call,
 * so there is no pool to size and no completion order to race on: two runs of
 * the same set on the same mirror produce the same bytes whatever the count.
 */
public final class VeltisPatcher {

    private final int workers;
    private final String minecraftVersion;
    private final org.apache.logging.log4j.Logger log =
        org.apache.logging.log4j.LogManager.getLogger(VeltisPatcher.class);

    /**
     * @param workers         reported build setting; application itself is a
     *                        single Git call, so this cannot change the outcome
     * @param minecraftVersion reported in every failure message
     */
    public VeltisPatcher(int workers, String minecraftVersion) {
        this.workers = Math.max(1, workers);
        this.minecraftVersion = minecraftVersion;
    }

    public int workers() {
        return workers;
    }

    /**
     * Groups the patch set by target file, in first-seen order.
     *
     * <p>A patch that addresses several files contributes to each of them, so the
     * map answers "which patches touch this file, in what order" — the question
     * the rebuild and any per-file tooling ask. It is a property of the set, not
     * of how the set is applied: application no longer runs these groups.
     */
    public static Map<String, List<UnifiedDiffPatcher.ChainEntry>> groupByTarget(
            List<VeltisPatch> patches) {
        var groups = new LinkedHashMap<String, List<UnifiedDiffPatcher.ChainEntry>>();
        for (var patch : patches) {
            for (var file : patch.parsed().files) {
                groups.computeIfAbsent(file.target(), k -> new ArrayList<>())
                    .add(new UnifiedDiffPatcher.ChainEntry(patch, file));
            }
        }
        return groups;
    }

    /**
     * Applies every patch to {@code patchedRoot}.
     *
     * @param patchedRoot the patched workspace; must already hold a pristine mirror
     *                    of the decompiled source (see {@link #mirrorPristineSource})
     * @return aggregated counters and phase timings for the run
     * @throws PatchEngineException carrying the full {@code [VeltisPatch]} report
     */
    public PatchStats apply(VeltisWorkspace workspace, List<VeltisPatch> patches) {
        var total = new PatchStats();
        writeTargetManifest(workspace, patches);
        writeAppliedPatches(workspace, patches);
        if (patches.isEmpty()) {
            return total;
        }
        var patchedRoot = workspace.patchedDirectory();
        log.info("[Veltis] Applying {} Shulker patches...", patches.size());

        // Git takes the whole set in one call, in the order discovery produced
        // it, so application is neither grouped by target nor spread across the
        // pool. The worker count therefore cannot reach the result — there is no
        // ordering left for it to perturb.
        total.merge(new GitPatchEngine(minecraftVersion).apply(patchedRoot, patches));

        // Reached only when every patch applied: a failure throws before here, so
        // the count is the number of patches that actually landed. This is the
        // one line the apply phase reports, worded exactly.
        log.info("[Veltis] Vanilla code has been kidnapped successfully and replaced with"
            + " {} Veltis Patches!!!", patches.size());
        return total;
    }

    /**
     * Records which files the patch set addresses, and in which category.
     *
     * <p>Written before any patching starts, so the build knows what to compile
     * even if the run then fails. Sorted, so two runs produce identical bytes.
     * The category is what lets the build attach {@code .java} targets to the
     * compile input and resource targets to the resource input without guessing
     * from a file extension.
     */
    private void writeTargetManifest(VeltisWorkspace workspace, List<VeltisPatch> patches) {
        var lines = new ArrayList<String>();
        for (var patch : patches) {
            for (var target : patch.targets()) {
                lines.add(patch.category().directoryName() + "\t" + target);
            }
        }
        lines.sort(String::compareTo);
        var file = workspace.patchTargetsFile();
        try {
            Files.createDirectories(file.getParent());
            MinecraftDownloader.writeMarker(file, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to record the patch targets in " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Records which patches this run actually applied, with the SHA-256 each had
     * at the moment of application.
     *
     * <p>Written beside the target manifest for the reason documented on
     * {@link VeltisWorkspace#appliedPatchesFile()}: {@code patched/} alone cannot
     * tell a rebuild that a patch file was deleted, because the deleted patch's
     * changes are still sitting in the tree waiting to be diffed straight back
     * into a new file. Sorted, so two runs produce identical bytes, and rewritten
     * by a successful rebuild so a mere renumber never looks like a deletion.
     */
    private void writeAppliedPatches(VeltisWorkspace workspace, List<VeltisPatch> patches) {
        var lines = new ArrayList<String>();
        for (var patch : patches) {
            var targets = new ArrayList<String>(patch.targets());
            targets.sort(String::compareTo);
            lines.add(patch.category().directoryName() + "\t" + patch.name()
                + "\t" + patch.revision() + "\t" + String.join(";", targets));
        }
        lines.sort(String::compareTo);
        var file = workspace.appliedPatchesFile();
        try {
            Files.createDirectories(file.getParent());
            MinecraftDownloader.writeMarker(file, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to record the applied patches in " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------
    // Preparing the patched workspace
    // ------------------------------------------------------------------

    /**
     * Makes {@code patchedRoot} an exact, independent copy of {@code sourceRoot}.
     *
     * <p>Deleting the old mirror first is what makes this idempotent and
     * self-healing: a run that failed halfway leaves no residue, and the next run
     * starts from the state a fresh clone would have.
     *
     * <p>The copy is a real copy, not a hard link, and that is deliberate. The
     * patched tree is the one a developer edits in their IDE, and an IDE saves by
     * truncating and rewriting the file in place. With hard links that write would
     * go through the shared inode and silently edit the pristine baseline as
     * well, after which {@code rebuildVeltisPatches} would diff the file against
     * itself and report "no changes" — losing the work with no error anywhere.
     * Two independent trees are the only arrangement where editing one cannot
     * change the other.
     *
     * @return the number of files mirrored
     */
    public static int mirrorPristineSource(Path sourceRoot, Path patchedRoot) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new PatchEngineException(
                "[VeltisMC] No decompiled source at " + sourceRoot
                    + "\n  Reason: run the decompile step before applying patches"
                    + " (./gradlew decompileMinecraft)");
        }
        VeltisWorkspace.deleteTree(patchedRoot);
        try {
            Files.createDirectories(patchedRoot);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMC] Failed to create the patched workspace " + patchedRoot, e);
        }

        var mirrored = new int[1];
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    var relative = sourceRoot.relativize(dir);
                    Files.createDirectories(patchedRoot.resolve(relative.toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (file.getFileName().toString().equals(VeltisWorkspace.DECOMPILE_MARKER)) {
                        return FileVisitResult.CONTINUE;   // build state, not source
                    }
                    var relative = sourceRoot.relativize(file);
                    // Attributes are copied so the mirror has the baseline's
                    // timestamps: a freshly mirrored tree must not look edited.
                    Files.copy(file, patchedRoot.resolve(relative.toString()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    mirrored[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMC] Failed to prepare the patched workspace at " + patchedRoot
                    + "\n  Source: " + sourceRoot
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        return mirrored[0];
    }

    /**
     * Mirrors only the files the patch set addresses, instead of the whole
     * decompile.
     *
     * <p>Used by a server installation, which never keeps a source tree: there
     * the decompile exists for exactly as long as one build and is read by
     * exactly two steps — the patcher and the compiler — neither of which wants
     * anything but the files the patch set names. Mirroring all ~15 000 files to
     * use six of them is where the tens of seconds a "patch step" used to cost
     * actually went; it was never the diffing.
     *
     * <p>A checkout keeps {@link #mirrorPristineSource}: its {@code patched/} is
     * the IDE source root, and an IDE that can only open the six patched files
     * cannot Ctrl+Click into anything else.
     *
     * @return the number of files mirrored
     */
    public static int mirrorPristineTargets(Path sourceRoot, Path patchedRoot,
                                            Collection<String> targets) {
        if (!Files.isDirectory(sourceRoot)) {
            throw new PatchEngineException(
                "[VeltisMC] No decompiled source at " + sourceRoot
                    + "\n  Reason: run the decompile step before applying patches"
                    + " (./gradlew decompileMinecraft)");
        }
        VeltisWorkspace.deleteTree(patchedRoot);
        var mirrored = 0;
        for (var target : new TreeSet<>(targets)) {
            if (target.startsWith("/") || target.startsWith("\\") || target.contains("..")) {
                throw new PatchEngineException(
                    "[VeltisPatch] Refusing a patch target outside the source tree: " + target);
            }
            var from = sourceRoot.resolve(target);
            if (!Files.isRegularFile(from)) {
                // A patch that creates a file has no baseline to mirror; the
                // patcher writes it from the patch itself.
                continue;
            }
            var to = patchedRoot.resolve(target);
            try {
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
                mirrored++;
            } catch (IOException e) {
                throw new PatchEngineException(
                    "[VeltisMC] Failed to mirror the patch target " + target
                        + "\n  Source: " + from
                        + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
            }
        }
        return mirrored;
    }

}
