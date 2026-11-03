package org.veltismc.patchengine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the patch set for a run, in a fixed and reproducible order.
 *
 * <p>Exactly three directories are consulted, in category order:
 * {@code server/Shulker/code}, {@code server/Shulker/data}, {@code server/Shulker/modules}. Within a
 * directory, patch files are applied in file-name order. There is no discovery
 * by classpath scan, no search of well-known locations, and no
 * numbered-name-renaming: the file name is the patch's identity, so
 * {@code 016-Foo.patch} and {@code 005-Bar.patch} apply in that order because
 * that is what sorting says, and it says the same thing on every platform.
 *
 * <p>Nothing here is derived from filesystem iteration order. {@code File.list()}
 * returns entries in whatever order the filesystem feels like; every result is
 * sorted explicitly before it is returned.
 *
 * <p>A missing category directory is not an error — a project may legitimately
 * have no data patches. A missing {@code server/Shulker/} root is an error, because that
 * means the build was pointed at the wrong directory and silently applying
 * nothing would produce a server that is quietly just vanilla.
 */
public final class PatchDiscovery {

    /**
     * The patch set's leaf directory name: under the project root it lives at
     * {@code server/Shulker}.
     */
    public static final String SHULKER_DIRECTORY = "Shulker";

    private PatchDiscovery() {
    }

    /**
     * Reads and parses the whole patch set.
     *
     * <p>Each patch file is read exactly once and parsed once, here, so the
     * workers that apply them never touch the filesystem for patch data at all.
     * That also means a malformed patch fails here, before a single source file
     * has been written.
     *
     * @param patchesRoot      the patch-set directory, {@code server/Shulker/} in a
     *                         checkout
     * @param minecraftVersion reported in any failure
     * @return patches in application order: code, then data, then modules
     */
    public static List<VeltisPatch> discover(Path patchesRoot, String minecraftVersion,
                                             PatchStats stats) {
        var started = System.nanoTime();
        if (!Files.isDirectory(patchesRoot)) {
            throw new PatchEngineException(
                "[VeltisPatch] No patch directory at " + patchesRoot
                    + "\n  Reason: the patch set is missing, so the build would silently"
                    + " produce an unpatched server; expected a 'Shulker' directory"
                    + " holding 'code', 'data' and/or 'modules'");
        }

        var patches = new ArrayList<VeltisPatch>();
        var parseNanos = 0L;
        for (var category : PatchCategory.values()) {
            var directory = patchesRoot.resolve(category.directoryName());
            if (!Files.isDirectory(directory)) {
                continue;   // a category with no patches is legitimate
            }
            for (var file : sortedPatchFiles(directory)) {
                long readStart = System.nanoTime();
                patches.add(VeltisPatch.load(file, category, minecraftVersion));
                parseNanos += System.nanoTime() - readStart;
            }
        }

        stats.parseNanos += parseNanos;
        // Everything this step did that was not parsing: the directory checks and
        // the sorted listing. Kept separate so the reported discovery and parsing
        // phases mean what they say.
        stats.discoveryNanos += System.nanoTime() - started - parseNanos;
        stats.patchesDiscovered = patches.size();
        return List.copyOf(patches);
    }

    /** Discovers the patch set under {@code projectDirectory/server/Shulker}. */
    public static List<VeltisPatch> discover(Path projectDirectory, String minecraftVersion) {
        return discover(projectDirectory.resolve("server").resolve(SHULKER_DIRECTORY),
            minecraftVersion, new PatchStats());
    }

    /**
     * Direct {@code *.patch} children of one category directory, sorted by file
     * name. Only direct children: a patch addresses a file in the Minecraft
     * workspace, and nesting patch files inside each other has no meaning.
     */
    private static List<Path> sortedPatchFiles(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries
                .filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".patch"))
                .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                .toList();
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to read the patch directory " + directory
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }
}
