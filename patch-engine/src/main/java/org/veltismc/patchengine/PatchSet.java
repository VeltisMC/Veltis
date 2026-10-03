package org.veltismc.patchengine;

import java.nio.file.Path;
import java.util.List;

/**
 * Finds the source patch set in a developer's checkout.
 *
 * <p>One source, one behaviour, and it is deliberately not the shape a running
 * server uses. The category order, the file-name order and the "a missing
 * category is legitimate, a missing patch root is not" rule all belong to the
 * development workflow, where Git is authoritative and a patch is a text file a
 * contributor edits and can read.
 *
 * <p>The packaged-source form this class once also offered — reading
 * {@code patches/index.txt} out of the launcher jar — was removed with the rest
 * of the runtime source-patch path. A server operator has no decompiler to
 * apply a text diff with and no compiler to run the result through, so shipping
 * a second patch representation would have meant two patch engines, one of them
 * unusable by anyone who receives it. What ships instead is the bytecode patch
 * set in {@link BytecodePatch}, which this class never touches.
 */
public final class PatchSet {

    private PatchSet() {
    }

    /**
     * Discovers the patch set from a directory, in category then name order.
     *
     * @see PatchDiscovery#discover(Path, String, PatchStats)
     */
    public static List<VeltisPatch> fromDirectory(Path patchesRoot, String minecraftVersion,
                                                  PatchStats stats) {
        return PatchDiscovery.discover(patchesRoot, minecraftVersion, stats);
    }
}
