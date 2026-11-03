package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery is where determinism is decided.
 *
 * <p>If discovery returned patches in filesystem order, the same clone would
 * produce a different build on a different machine. These tests pin the two
 * rules that make it deterministic — category order, then file-name order — and
 * check that the things which are supposed to be errors are errors rather than
 * silently-empty patch sets.
 */
class PatchDiscoveryTest {

    @TempDir
    Path tmp;

    private static final String TARGET = "net/minecraft/server/Server.java";
    private static final String SOURCE = "class Server {\n    int v = 0;\n}\n";

    private Path patchesRoot(String name) {
        var root = tmp.resolve(name);
        try {
            Files.createDirectories(root.resolve("code"));
            Files.createDirectories(root.resolve("data"));
            Files.createDirectories(root.resolve("modules"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return root;
    }

    /** Discovery of an explicit patch-set directory, with its own counters. */
    private static List<VeltisPatch> discover(Path patchesRoot) {
        return PatchDiscovery.discover(patchesRoot, "26.3", new PatchStats());
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String patchFor(String target) {
        return "--- a/" + target + "\n+++ b/" + target + "\n@@ -1,3 +1,3 @@\n"
            + " class Server {\n-    int v = 0;\n+    int v = 1;\n }\n";
    }

    @Test
    void returnsCodeThenDataThenModulesEachSortedByName() {
        var root = patchesRoot("order");
        write(root.resolve("modules/002-z.patch"), TestWorkspace.createPatch("m.txt", "z"));
        write(root.resolve("code/010-b.patch"), patchFor(TARGET));
        write(root.resolve("data/002-b.patch"), TestWorkspace.createPatch("d.json", "{}"));
        write(root.resolve("code/002-a.patch"), patchFor("net/minecraft/server/A.java"));
        write(root.resolve("code/002-B.patch"), patchFor("net/minecraft/server/B.java"));

        var patches = discover(root);

        assertEquals(List.of(
            "code/002-B.patch",
            "code/002-a.patch",
            "code/010-b.patch",
            "data/002-b.patch",
            "modules/002-z.patch"),
            patches.stream().map(VeltisPatch::describe).toList(),
            "ordering is by name within a category, and name comparison is"
                + " case-sensitive ('B' < 'a') and independent of the filesystem's"
                + " own ordering, in which 002-a.patch was written last");
        assertEquals(64, patches.get(0).revision().length(), "each patch carries its SHA-256");
    }

    @Test
    void aMissingCategoryDirectoryIsNotAnError() {
        var root = tmp.resolve("code-only");
        write(root.resolve("code/001-A.patch"), patchFor(TARGET));

        var patches = discover(root);
        assertEquals(1, patches.size());
        assertEquals(PatchCategory.CODE, patches.get(0).category());
    }

    @Test
    void aMissingPatchesDirectoryIsAnError() {
        var failure = assertThrows(PatchEngineException.class,
            () -> PatchDiscovery.discover(tmp.resolve("nowhere"), "26.3"));
        var msg = failure.getMessage();
        assertTrue(msg.startsWith("[VeltisPatch]"), msg);
        assertTrue(msg.contains("No patch directory"), msg);
        assertTrue(msg.contains("unpatched server"), msg);
    }

    @Test
    void nonPatchFilesAreIgnored() {
        var root = patchesRoot("filtering");
        write(root.resolve("code/001-A.patch"), patchFor(TARGET));
        write(root.resolve("code/README.md"), "notes about the patch, not a patch");
        write(root.resolve("code/nested/002-B.patch"), patchFor(TARGET));

        var patches = discover(root);
        assertEquals(List.of("001-A.patch"),
            patches.stream().map(VeltisPatch::name).toList(),
            "only direct *.patch children are patches");
    }

    @Test
    void aMalformedPatchFailsDuringDiscoveryBeforeAnythingIsWritten() {
        var root = patchesRoot("malformed");
        write(root.resolve("code/001-Bad.patch"), "--- a/x.java\n@@ -1,1 +1,1 @@\n-a\n+b\n");

        var failure = assertThrows(PatchEngineException.class, () -> discover(root));
        var msg = failure.getMessage();
        assertTrue(msg.contains("001-Bad.patch"), msg);
        assertTrue(msg.contains("Category: code"), msg);
        assertTrue(msg.contains("Patch revision:"), msg);
    }

    @Test
    void gitSectionHeadersAndQuotedPathsAreAccepted() {
        var root = patchesRoot("gitdialect");
        write(root.resolve("code/001-A.patch"), String.join("\n",
            "diff --git a/net/minecraft/server/Server.java b/net/minecraft/server/Server.java",
            "index 1234567..89abcde 100644",
            "--- a/net/minecraft/server/Server.java",
            "+++ b/net/minecraft/server/Server.java",
            "@@ -1,3 +1,3 @@",
            " class Server {",
            "-    int v = 0;",
            "+    int v = 1;",
            " }",
            ""));

        var patches = discover(root);
        assertEquals(List.of(TARGET), patches.get(0).targets());
    }

    @Test
    void aPatchAddressingSeveralFilesReportsAllOfItsTargets() {
        var root = patchesRoot("multi");
        write(root.resolve("code/001-A.patch"), String.join("\n",
            "--- a/One.java",
            "+++ b/One.java",
            "@@ -1,1 +1,1 @@",
            "-class One {}",
            "+class One { }",
            "--- a/Two.java",
            "+++ b/Two.java",
            "@@ -1,1 +1,1 @@",
            "-class Two {}",
            "+class Two { }",
            ""));

        var patches = discover(root);
        assertEquals(List.of("One.java", "Two.java"), patches.get(0).targets());
        assertEquals("One.java", patches.get(0).primaryTarget());
    }

    @Test
    void quotedPathsAreUnquoted() {
        var parsed = VeltisPatch.parse(List.of(
            "--- \"a/net/minecraft/server/Server One.java\"",
            "+++ \"b/net/minecraft/server/Server One.java\"",
            "@@ -1,1 +1,1 @@",
            "-a",
            "+b"),
            "quoted.patch", PatchCategory.CODE, "rev", "26.3");

        assertEquals(List.of("net/minecraft/server/Server One.java"), parsed.targets());
    }
}
