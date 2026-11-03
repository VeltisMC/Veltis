package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the patcher guarantees beyond "it applies the hunks".
 *
 * <p>The ordering and concurrency rules are the part that is easy to get subtly
 * wrong and impossible to notice from a single successful build, so each one gets
 * a test: category order, name order inside a category, strict sequencing of a
 * chain that targets one file, parallelism across distinct files, and byte
 * equality at 1, 4 and 8 workers. The atomicity tests are the other half — a
 * failing patch must leave the target exactly as it was.
 */
class VeltisPatcherTest {

    @TempDir
    Path tmp;

    private static final String SERVER = "net/minecraft/server/dedicated/DedicatedServer.java";
    private static final String BOOT = "net/minecraft/server/Bootstrap.java";
    private static final String WORLD = "net/minecraft/server/WorldServer.java";

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    void patchesApplyInCategoryOrderThenNameOrder() {
        var fixture = TestWorkspace.create(tmp.resolve("order"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            // Declared out of order, and with a name that outranks "003-" if the
            // names alone were sorted: category order has to be what decides.
            .patch(PatchCategory.CODE, "500-D.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .patch(PatchCategory.MODULES, "001-Mod.patch", markerPatch(SERVER, "DedicatedServer", 1, 2))
            .patch(PatchCategory.DATA, "001-Data.patch", markerPatch(SERVER, "DedicatedServer", 2, 3))
            .patch(PatchCategory.CODE, "003-A.patch", markerPatch(SERVER, "DedicatedServer", 3, 4))
            .materialize();

        assertEquals(List.of(
            "code/003-A.patch",
            "code/500-D.patch",
            "data/001-Data.patch",
            "modules/001-Mod.patch"),
            fixture.discover().stream().map(VeltisPatch::describe).toList());
    }

    @Test
    void aChainOnOneFileIsAppliedInOrderRegardlessOfWorkerCount() {
        for (var workers : new int[] {1, 4, 8}) {
            // Five patches on one file, each replacing the previous marker. The
            // final value can only be 5 if every patch ran in order.
            var fixture = TestWorkspace.create(tmp.resolve("chain-" + workers))
                .source(SERVER, serverSource("DedicatedServer", 0))
                .materialize();
            for (var i = 0; i < 5; i++) {
                fixture.patch(PatchCategory.CODE, String.format("00%d-%d.patch", i + 1, i),
                    markerPatch(SERVER, "DedicatedServer", i, i + 1)).materialize();
            }

            var groups = VeltisPatcher.groupByTarget(fixture.discover());
            assertEquals(1, groups.size(),
                "one file is one group, so its chain can never be split across threads");

            fixture.apply(workers);
            assertTrue(fixture.patched(SERVER).contains("int marker = 5;"),
                "the chain ran out of order with " + workers + " workers");
        }
    }

    @Test
    void independentTargetsAreGroupedSeparatelyAndCanRunInParallel() {
        var fixture = TestWorkspace.create(tmp.resolve("parallel"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            .source(BOOT, serverSource("Bootstrap", 0))
            .source(WORLD, serverSource("WorldServer", 0))
            .patch(PatchCategory.CODE, "001-A.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .patch(PatchCategory.CODE, "002-B.patch", markerPatch(BOOT, "Bootstrap", 0, 1))
            .patch(PatchCategory.CODE, "003-C.patch", markerPatch(WORLD, "WorldServer", 0, 1))
            .materialize();

        var groups = VeltisPatcher.groupByTarget(fixture.discover());
        assertEquals(List.of(SERVER, BOOT, WORLD), List.copyOf(groups.keySet()),
            "groups follow first-seen order, which is discovery order");

        var stats = fixture.applyReporting(4);
        assertEquals(3, stats.filesChanged);
        assertEquals(3, stats.filesRead, "each target is read exactly once, never once per patch");
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    void workerCountDoesNotChangeTheResultOrTheCounters() {
        // Eight independent targets, so the 8-worker run really does start eight
        // threads. VeltisPatcher caps parallelism at the number of target groups,
        // so a smaller fixture would silently run the "8 worker" case on fewer
        // threads and prove nothing about concurrency.
        final int targets = 8;
        var counters = new ArrayList<String>();
        Map<String, String> reference = null;
        for (var workers : new int[] {1, 4, 8}) {
            var fixture = TestWorkspace.create(tmp.resolve("determinism-" + workers));
            for (int i = 0; i < targets; i++) {
                var target = "net/minecraft/gen/Target" + i + ".java";
                fixture.source(target, serverSource("Target" + i, 0))
                    .patch(PatchCategory.CODE, String.format("%03d-T%d.patch", i, i),
                        markerPatch(target, "Target" + i, 0, 1));
            }

            var stats = fixture.applyReporting(workers);
            counters.add(stats.filesRead + "/" + stats.filesWritten + "/" + stats.filesChanged
                + "/" + stats.patchesApplied);

            var tree = fixture.patchedTree();
            if (reference == null) {
                reference = tree;
            } else {
                assertEquals(reference, tree, "output differs with " + workers + " workers");
            }
        }
        assertEquals(List.of(
            targets + "/" + targets + "/" + targets + "/" + targets,
            targets + "/" + targets + "/" + targets + "/" + targets,
            targets + "/" + targets + "/" + targets + "/" + targets), counters,
            "the same work must be done at every worker count");
    }

    @Test
    void thePipelineProducesNoTemporaryOrThrowawayDirectories() throws Exception {
        var fixture = TestWorkspace.create(tmp.resolve("no-temp"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            .patch(PatchCategory.CODE, "001-A.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .apply(4);

        // `resources/` is part of the declared layout, not a stray: it holds the
        // delta that the data and module patches changed, so the runtime
        // classpath can prefer a patched resource over the vanilla copy in the
        // widened jar without a second full copy of Minecraft's resources.
        //
        // `source/` is deliberately absent from the list: since the split
        // layout the pristine decompile lives beside the workspace at
        // minecraft/<version>/ rather than inside it, so the workspace root
        // carries products only and a source/ copy reappearing under it would
        // be a second tree to keep in step.
        var dirs = fixture.directoriesUnderRoot();
        assertEquals(List.of(
            "", "build", "classes", "libraries", "metadata", "patched",
            "patched/net",
            "patched/net/minecraft",
            "patched/net/minecraft/server",
            "patched/net/minecraft/server/dedicated",
            "resources",
            "vanilla"),
            dirs, "the workspace layout must be exactly the declared one");

        // The half the workspace walk cannot see: the source the patches apply
        // against must exist, and must not be under the root the assertion
        // above just enumerated — outside it is the whole point of the split.
        var workspace = fixture.workspace();
        assertFalse(workspace.sourceDirectory().startsWith(workspace.root()),
            "the pristine decompile must stay outside the workspace root: "
                + workspace.sourceDirectory());
        assertTrue(Files.isRegularFile(workspace.sourceDirectory().resolve(SERVER)),
            "the pristine source the patches applied against must still exist"
                + " outside the root");

        // One staging name across the whole pipeline. The point is that it is
        // derived from the target file rather than generated, so a crashed run
        // leaves one predictable path instead of a growing pile of temporaries.
        try (Stream<Path> walk = Files.walk(fixture.patchedRoot())) {
            var strays = walk.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".writing"))
                .toList();
            assertEquals(List.of(), strays, "staging files must never survive a successful run");
        }

        // Nothing anywhere in the workspace may still use the retired staging
        // names. They are checked by name because each was a real past problem:
        // a leftover file a `clean` did not remove, and a second convention for
        // what is really the same operation.
        try (Stream<Path> walk = Files.walk(fixture.workspace().root())) {
            var retired = walk.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".part") || n.endsWith(".veltis-tmp")
                        || n.endsWith(".rebuild-staging"))
                .toList();
            assertEquals(List.of(), retired,
                "a retired staging suffix is still in use; the pipeline has exactly"
                    + " one staging convention and it is .writing");
        }
    }

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    @Test
    void theTargetManifestRecordsEveryCategoryAndIsSorted() throws Exception {
        var fixture = TestWorkspace.create(tmp.resolve("manifest"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            .patch(PatchCategory.CODE, "001-A.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .patch(PatchCategory.DATA, "001-Data.patch",
                TestWorkspace.createPatch("data/minecraft/veltis/cfg.json", "{\"veltis\": true}"))
            .patch(PatchCategory.MODULES, "001-Mod.patch",
                TestWorkspace.createPatch("org/veltismc/module.txt", "core"))
            .apply(2);

        var manifest = fixture.workspace().patchTargetsFile();
        assertTrue(Files.isRegularFile(manifest));
        var lines = Files.readAllLines(manifest);
        assertEquals(List.of(
            "code\t" + SERVER,
            "data\tdata/minecraft/veltis/cfg.json",
            "modules\torg/veltismc/module.txt"), lines);
    }

    @Test
    void categoriesDeclareTheirOrderAndSourceSet() {
        assertEquals(List.of(PatchCategory.CODE, PatchCategory.DATA, PatchCategory.MODULES),
            List.of(PatchCategory.values()),
            "PatchCategory.values() order is the application order");
        assertTrue(PatchCategory.CODE.order() < PatchCategory.DATA.order());
        assertTrue(PatchCategory.DATA.order() < PatchCategory.MODULES.order());
        assertEquals("minecraft", PatchCategory.CODE.sourceSet());
        assertEquals("minecraftResources", PatchCategory.DATA.sourceSet());
        assertEquals("minecraftModules", PatchCategory.MODULES.sourceSet());
        assertEquals(PatchCategory.MODULES, PatchCategory.ofDirectory("MODULES"));
        assertEquals(PatchCategory.CODE, PatchCategory.ofDirectory("code"));
    }

    // ------------------------------------------------------------------
    // Failure: the target must be untouched
    // ------------------------------------------------------------------

    @Test
    void aFailingPatchNamesThePatchCategoryTargetAndReason() {
        var original = serverSource("DedicatedServer", 0);
        var fixture = TestWorkspace.create(tmp.resolve("failure"))
            .source(SERVER, original)
            .patch(PatchCategory.CODE, "001-Ok.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .patch(PatchCategory.CODE, "002-Bad.patch",
                "--- a/" + SERVER + "\n"
                    + "+++ b/" + SERVER + "\n"
                    + "@@ -1,1 +1,1 @@\n"
                    + "-class NeverPresent {\n"
                    + "+class Renamed {\n")
            .materialize();

        var failure = assertThrows(PatchEngineException.class, () -> fixture.apply(4));
        var msg = failure.getMessage();
        assertTrue(msg.startsWith("[VeltisPatch] Failed to apply patch"), msg);
        assertTrue(msg.contains("Patch: 002-Bad.patch"), msg);
        assertTrue(msg.contains("Category: code"), msg);
        assertTrue(msg.contains("Target: " + SERVER), msg);
        assertTrue(msg.contains("Location: patch 2 of 2, hunk #1"), msg);
        assertTrue(msg.contains("expected context"), msg);
        assertTrue(msg.contains("actual source lines"), msg);
        assertTrue(msg.contains("Minecraft: 26.3"), msg);
        assertTrue(msg.contains("Patch revision:"), msg);

        // The baseline is untouched, even though patch 001 succeeded.
        assertEquals(original, fixture.pristine(SERVER),
            "patching must never reach the pristine decompile");
    }

    @Test
    void aChainThatFailsHalfwayWritesNothing() {
        var fixture = TestWorkspace.create(tmp.resolve("chain-atomic"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            .patch(PatchCategory.CODE, "001-First.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .patch(PatchCategory.CODE, "002-Second.patch", markerPatch(SERVER, "DedicatedServer", 1, 2))
            .patch(PatchCategory.CODE, "003-Broken.patch", markerPatch(SERVER, "DedicatedServer", 99, 100))
            .materialize();

        var failure = assertThrows(PatchEngineException.class,
            () -> new VeltisPatcher(1, "26.3").apply(fixture.workspace(), fixture.discover()));
        assertTrue(failure.getMessage().contains("Patch: 003-Broken.patch"), failure.getMessage());

        assertEquals(serverSource("DedicatedServer", 0), fixture.patched(SERVER),
            "the chain is applied in memory and written once, so a failure in patch 3"
                + " must leave the results of patches 1 and 2 unwritten too");
    }

    @Test
    void aPatchAddressingAFileThatDoesNotExistIsRejected() {
        var fixture = TestWorkspace.create(tmp.resolve("missing"))
            .patch(PatchCategory.CODE, "001-Ghost.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .materialize();

        var failure = assertThrows(PatchEngineException.class, () -> fixture.apply(1));
        assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
        assertFalse(Files.exists(fixture.patchedRoot().resolve(SERVER)));
    }

    @Test
    void applyingToAnAlreadyPatchedTreeFailsRatherThanSkippingQuietly() {
        var fixture = TestWorkspace.create(tmp.resolve("reapply"))
            .source(SERVER, serverSource("DedicatedServer", 0))
            .patch(PatchCategory.CODE, "001-A.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .apply(1);

        assertTrue(fixture.patched(SERVER).contains("int marker = 1;"));
        assertThrows(PatchEngineException.class,
            () -> new VeltisPatcher(1, "26.3").apply(fixture.workspace(), fixture.discover()));
        assertTrue(fixture.patched(SERVER).contains("int marker = 1;"),
            "the failed re-application must not have touched the file");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String serverSource(String className, int marker) {
        return "class " + className + " {\n    int marker = " + marker + ";\n}\n";
    }

    /** A whole-file replacement of {@code int marker = <from>;} with {@code <to>}. */
    private static String markerPatch(String target, String className, int from, int to) {
        return "--- a/" + target + "\n"
            + "+++ b/" + target + "\n"
            + "@@ -1,3 +1,3 @@\n"
            + " class " + className + " {\n"
            + "-    int marker = " + from + ";\n"
            + "+    int marker = " + to + ";\n"
            + " }\n";
    }
}
