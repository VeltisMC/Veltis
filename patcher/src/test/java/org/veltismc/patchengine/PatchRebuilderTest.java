package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rebuilding patches from an edited workspace, and applying the result again.
 *
 * <p>What makes that trustworthy is the closing of the loop — the regenerated patches must
 * reproduce the edited tree exactly. That is asserted here end to end, along with
 * the idempotent cases (no edits produces no diff, a reverted file loses its
 * patch), the chain case (several patches on one file are replayed, not merged),
 * and the numbering cases (a deletion pulls the series up and leaves no hole).
 */
class PatchRebuilderTest {

    @TempDir
    Path tmp;

    private static final String SERVER = "net/minecraft/server/dedicated/DedicatedServer.java";
    private static final String BOOT = "net/minecraft/server/Bootstrap.java";
    private static final String CONFIG = "data/minecraft/veltis/config.json";
    private static final String MODULE = "org/veltismc/modules/core/module.json";

    private static final String SERVER_PRISTINE = "class DedicatedServer {\n    int marker = 0;\n}\n";
    private static final String SERVER_PATCHED = "class DedicatedServer {\n    int marker = 1;\n}\n";
    private static final String BOOT_PRISTINE = "class Bootstrap {\n    int marker = 0;\n}\n";
    private static final String BOOT_PATCHED = "class Bootstrap {\n    int marker = 1;\n}\n";
    private static final String CONFIG_PRISTINE = "{\"enabled\": false}\n";
    private static final String CONFIG_PATCHED = "{\"enabled\": true}\n";
    private static final String MODULE_PRISTINE = "{\"name\": \"core\"}\n";
    private static final String MODULE_PATCHED = "{\"name\": \"core\", \"version\": 2}\n";

    /**
     * A patch set that is already in the exact shape {@code rebuildVeltisPatches}
     * generates, applied on top of the pristine tree.
     *
     * <p>That matters for the "no edits" case: the guarantee under test is that a
     * rebuild which changes nothing leaves the patch files byte-identical, and that
     * is only a meaningful statement when the starting patch set is canonical.
     * Building the fixture through {@link GitPatchEngine#diff} also means the test
     * exercises the round trip rather than a hand-written approximation of it.
     */
    private TestWorkspace baseFixture(String name) {
        return TestWorkspace.create(tmp.resolve(name))
            .source(SERVER, SERVER_PRISTINE)
            .source(BOOT, BOOT_PRISTINE)
            .source(CONFIG, CONFIG_PRISTINE)
            .source(MODULE, MODULE_PRISTINE)
            .patch(PatchCategory.CODE, "001-Server.patch",
                canonicalPatch(SERVER, SERVER_PRISTINE, SERVER_PATCHED))
            .patch(PatchCategory.CODE, "002-Bootstrap.patch",
                canonicalPatch(BOOT, BOOT_PRISTINE, BOOT_PATCHED))
            .patch(PatchCategory.DATA, "001-Config.patch",
                canonicalPatch(CONFIG, CONFIG_PRISTINE, CONFIG_PATCHED))
            .patch(PatchCategory.MODULES, "001-Module.patch",
                canonicalPatch(MODULE, MODULE_PRISTINE, MODULE_PATCHED))
            .apply(2);
    }

    /**
     * Exactly the bytes a rebuild would write for this change.
     *
     * <p>Rendered by the same Git the rebuild calls, so "is the rebuild a fixed
     * point of itself" is answered against Git's own output rather than against a
     * second implementation that happens to agree with it today.
     */
    private static String canonicalPatch(String target, String pristine, String patched) {
        return String.join("\n", GitPatchEngine.diff(target, pristine, patched)) + "\n";
    }

    /** Applies the patch set to a fresh mirror and returns the resulting tree. */
    private java.util.Map<String, String> reapply(TestWorkspace fixture) {
        VeltisPatcher.mirrorPristineSource(fixture.workspace().sourceDirectory(),
            fixture.patchedRoot());
        new VeltisPatcher(4, TestWorkspace.VERSION_ID)
            .apply(fixture.workspace(), fixture.discover());
        return fixture.patchedTree();
    }

    @Test
    void theRebuiltPatchSetReproducesTheEditedTreeExactly() throws Exception {
        var fixture = baseFixture("roundtrip");
        // Edit two files directly, as a developer would.
        Files.writeString(fixture.patchedRoot().resolve(SERVER),
            "class A {\n    int marker = 99;\n    int extra = 1;\n}\n");
        Files.writeString(fixture.patchedRoot().resolve(CONFIG),
            "{\"enabled\": true, \"level\": 3}\n");

        var edited = fixture.patchedTree();
        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(2, result.regenerated(), "only the two edited patches change");
        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        assertEquals(4, result.targets(), "the whole tree is covered");

        // The loop is closed: re-applying the regenerated patches yields the edit.
        assertEquals(edited, reapply(fixture));
        assertTrue(Files.readString(fixture.patchedRoot().resolve(SERVER))
            .contains("int extra = 1;"));
    }

    @Test
    void aRebuildWithNoEditsProducesNoVersionControlDiff() throws Exception {
        var fixture = baseFixture("no-edits");
        var before = readPatches(fixture.workspace().shulkerDirectory());

        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(before, readPatches(fixture.workspace().shulkerDirectory()),
            "a rebuild that changes nothing must not rewrite a patch file");
        assertEquals(0, result.regenerated());
        assertEquals(0, result.created());
        assertEquals(0, result.removed());
    }

    @Test
    void aRevertedFileLosesItsPatch() throws Exception {
        var fixture = baseFixture("reverted");
        // Put the file back exactly as the decompile has it.
        Files.write(fixture.patchedRoot().resolve(SERVER),
            fixture.pristine(SERVER).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var patchFile = fixture.workspace().shulkerDirectory().resolve("code/001-Server.patch");

        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(1, result.removed());
        assertFalse(Files.exists(patchFile), "a patch nothing needs must be deleted, not emptied");

        var tree = reapply(fixture);
        assertEquals(4, tree.size(), "every source file survives the round trip");
        assertEquals(SERVER_PRISTINE, tree.get(SERVER), "the reverted file needs no patch");
        assertEquals(BOOT_PATCHED, tree.get(BOOT), "the other patches still apply");
    }

    @Test
    void aFileNoPatchAddressedGetsItsOwnPatchInTheRightCategory() throws Exception {
        var fixture = baseFixture("new-file");
        Files.createDirectories(fixture.patchedRoot().resolve("net/minecraft/util"));
        Files.writeString(fixture.patchedRoot().resolve("net/minecraft/util/Helper.java"),
            "class Helper {}\n");

        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(1, result.created());
        var patches = fixture.discover();
        assertTrue(patches.stream().anyMatch(p -> p.category() == PatchCategory.CODE
            && p.targets().equals(List.of("net/minecraft/util/Helper.java"))),
            "a .java file outside any patch belongs in code/: "
                + patches.stream().map(VeltisPatch::describe).toList());
    }

    @Test
    void aChainOfPatchesOnOneFileIsReplayedRatherThanRefused() throws Exception {
        var markerOne = "class DedicatedServer {\n    int marker = 1;\n}\n";
        var markerTwo = "class DedicatedServer {\n    int marker = 2;\n}\n";
        var markerThree = "class DedicatedServer {\n    int marker = 3;\n}\n";
        var fixture = TestWorkspace.create(tmp.resolve("chain"))
            .source(SERVER, SERVER_PRISTINE)
            .patch(PatchCategory.CODE, "001-A.patch",
                canonicalPatch(SERVER, SERVER_PRISTINE, markerOne))
            .patch(PatchCategory.CODE, "002-B.patch",
                canonicalPatch(SERVER, markerOne, markerTwo))
            .apply(2);

        // With no edits the chain regenerates byte-identically: replaying it
        // recovers exactly the states the patches already described.
        var before = readPatches(fixture.workspace().shulkerDirectory());
        var untouched = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);
        assertEquals(0, untouched.regenerated(), "a rebuild with no edits changes nothing");
        assertEquals(0, untouched.removed());
        assertEquals(1, untouched.targets(), "the file is still covered, by two patches");
        assertEquals(before, readPatches(fixture.workspace().shulkerDirectory()));

        // An edit lands in the LAST patch of the chain, because that is the only
        // position from which the edited tree is reachable. The earlier patch
        // keeps its bytes, so an edit never rewrites a patch nobody touched.
        Files.writeString(fixture.patchedRoot().resolve(SERVER), markerThree);
        var edited = fixture.patchedTree();

        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(1, result.regenerated(), "only the patch carrying the edit is rewritten");
        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        var after = readPatches(fixture.workspace().shulkerDirectory());
        assertEquals(before.get("code/001-A.patch"), after.get("code/001-A.patch"),
            "the earlier patch in the chain keeps its content exactly");
        assertEquals(canonicalPatch(SERVER, markerOne, markerThree),
            after.get("code/002-B.patch"),
            "the edit is expressed from the state the previous patch left behind");

        assertEquals(edited, reapply(fixture),
            "the replayed chain still reproduces the edited tree exactly");
    }

    @Test
    void renumberingPullsTheSeriesUpAfterADeletion() throws Exception {
        var playerList = "net/minecraft/server/PlayerList.java";
        var playerPristine = "class PlayerList {\n    int marker = 0;\n}\n";
        var playerPatched = "class PlayerList {\n    int marker = 1;\n}\n";
        var fixture = TestWorkspace.create(tmp.resolve("renumber"))
            .source(SERVER, SERVER_PRISTINE)
            .source(BOOT, BOOT_PRISTINE)
            .source(playerList, playerPristine)
            .patch(PatchCategory.CODE, "001-Server.patch",
                canonicalPatch(SERVER, SERVER_PRISTINE, SERVER_PATCHED))
            .patch(PatchCategory.CODE, "002-Bootstrap.patch",
                canonicalPatch(BOOT, BOOT_PRISTINE, BOOT_PATCHED))
            .patch(PatchCategory.CODE, "016-PlayerList.patch",
                canonicalPatch(playerList, playerPristine, playerPatched))
            .apply(3);

        // Deleting a patch changes the intended final state, so the workspace is
        // re-applied first — that is the workflow CONTRIBUTING documents, and it
        // is also what keeps the applied-patch record truthful.
        Files.delete(fixture.workspace().shulkerDirectory().resolve("code/001-Server.patch"));
        reapply(fixture);

        var result = new PatchRebuilder().rebuild(fixture.workspace(),
            fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);

        assertEquals(2, result.regenerated(), "both survivors keep their content and move");
        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        var patches = readPatches(fixture.workspace().shulkerDirectory());
        assertEquals(
            java.util.Set.of("code/001-Bootstrap.patch", "code/002-PlayerList.patch"),
            patches.keySet(),
            "numbers are contiguous, descriptions survive, and 016 leaves no hole");
        assertFalse(patches.containsKey("code/016-PlayerList.patch"));
        assertEquals(canonicalPatch(BOOT, BOOT_PRISTINE, BOOT_PATCHED),
            patches.get("code/001-Bootstrap.patch"), "content is untouched by a renumber");

        // The round trip still closes after the renumber.
        assertEquals(fixture.patchedTree(), reapply(fixture));
    }

    @Test
    void aPatchDeletedWithoutReapplyingIsRefusedByNameAndTarget() throws Exception {
        var fixture = baseFixture("stale-delete");
        var expected = fixture.patchedTree();
        Files.delete(fixture.workspace().shulkerDirectory().resolve("code/001-Server.patch"));

        var failure = assertThrows(PatchEngineException.class,
            () -> new PatchRebuilder().rebuild(fixture.workspace(),
                fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID));

        var msg = failure.getMessage();
        assertTrue(msg.contains("code/001-Server.patch"), msg);
        assertTrue(msg.contains(SERVER), msg);
        assertTrue(msg.contains("applyPatches"), msg);
        assertTrue(msg.contains("nothing was written"), msg);
        assertTrue(Files.exists(fixture.workspace().shulkerDirectory().resolve("code/002-Bootstrap.patch")),
            "the rebuild must not have touched any surviving patch");
        assertEquals(expected, fixture.patchedTree(), "and not the workspace either");
    }

    @Test
    void patchNameNamesTheSingleNewPatchARebuildCreates() throws Exception {
        var fixture = baseFixture("patch-name");
        Files.createDirectories(fixture.patchedRoot().resolve("net/minecraft/util"));
        Files.writeString(fixture.patchedRoot().resolve("net/minecraft/util/Helper.java"),
            "class Helper {}\n");

        System.setProperty(PatchRebuilder.PATCH_NAME_PROPERTY, "007-Improve Helper Logging");
        try {
            var result = new PatchRebuilder().rebuild(fixture.workspace(),
                fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID);
            assertEquals(1, result.created());
            assertTrue(Files.exists(
                    fixture.workspace().shulkerDirectory().resolve("code/003-Improve-Helper-Logging.patch")),
                "the number comes from the order, the description from the property; "
                    + "readPatches=" + readPatches(fixture.workspace().shulkerDirectory()).keySet());
        } finally {
            System.clearProperty(PatchRebuilder.PATCH_NAME_PROPERTY);
        }
    }

    @Test
    void patchNameIsRefusedRatherThanGuessedAtWhenSeveralPatchesWouldBeNamed() throws Exception {
        var fixture = baseFixture("patch-name-ambiguous");
        Files.createDirectories(fixture.patchedRoot().resolve("net/minecraft/util"));
        Files.writeString(fixture.patchedRoot().resolve("net/minecraft/util/Helper.java"),
            "class Helper {}\n");
        Files.createDirectories(fixture.patchedRoot().resolve("net/minecraft/util"));
        Files.writeString(fixture.patchedRoot().resolve("net/minecraft/util/Other.java"),
            "class Other {}\n");

        System.setProperty(PatchRebuilder.PATCH_NAME_PROPERTY, "Whatever");
        try {
            var failure = assertThrows(PatchEngineException.class,
                () -> new PatchRebuilder().rebuild(fixture.workspace(),
                    fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID));
            var msg = failure.getMessage();
            assertTrue(msg.contains("-PpatchName"), msg);
            assertTrue(msg.contains("net/minecraft/util/Helper.java"), msg);
            assertTrue(msg.contains("net/minecraft/util/Other.java"), msg);
            assertTrue(msg.contains("nothing was written"), msg);
            assertFalse(Files.exists(
                    fixture.workspace().shulkerDirectory().resolve("code/003-Whatever.patch")),
                "an ambiguous name must not half-apply");
        } finally {
            System.clearProperty(PatchRebuilder.PATCH_NAME_PROPERTY);
        }
    }

    @Test
    void rebuildingWithoutAPatchedWorkspaceSaysWhichTaskToRun() {
        var fixture = TestWorkspace.create(tmp.resolve("no-patched"))
            .source(SERVER, "class A {}\n")
            .patch(PatchCategory.CODE, "001-A.patch", markerPatch(SERVER, "DedicatedServer", 0, 1))
            .materialize();
        VeltisWorkspace.deleteTree(fixture.patchedRoot());

        var failure = assertThrows(PatchEngineException.class,
            () -> new PatchRebuilder().rebuild(fixture.workspace(),
                fixture.workspace().shulkerDirectory(), TestWorkspace.VERSION_ID));

        assertTrue(failure.getMessage().contains("applyPatches"),
            failure.getMessage());
    }

    @Test
    void categoryInferenceIsStatedOnceSoEveryRebuildAgrees() {
        assertEquals(PatchCategory.CODE, PatchRebuilder.inferCategory("net/minecraft/A.java"));
        assertEquals(PatchCategory.MODULES,
            PatchRebuilder.inferCategory("org/veltismc/Module.java"));
        assertEquals(PatchCategory.DATA,
            PatchRebuilder.inferCategory("data/minecraft/veltis/config.json"));
        assertEquals(PatchCategory.DATA, PatchRebuilder.inferCategory("version.json"));
    }

    private static java.util.Map<String, String> readPatches(Path patchesRoot) throws Exception {
        var map = new java.util.TreeMap<String, String>();
        try (var walk = Files.walk(patchesRoot)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                map.put(patchesRoot.relativize(file).toString().replace('\\', '/'),
                    Files.readString(file));
            }
        }
        return map;
    }

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
