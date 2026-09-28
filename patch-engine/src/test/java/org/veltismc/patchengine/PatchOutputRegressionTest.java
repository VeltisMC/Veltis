package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-for-byte output regression for the patch pipeline.
 *
 * <p>The corpus below exercises the production path end to end: patch files on
 * disk -> {@link PatchDiscovery} -> {@link RuntimePatchApplier} (grouping,
 * ordering, parallel application) -> written source files. It covers:
 *
 * <ul>
 *   <li>two patches chained on one source file (deterministic order),</li>
 *   <li>independent files applied in parallel,</li>
 *   <li>a new-file patch ({@code --- /dev/null}),</li>
 *   <li>CRLF preservation,</li>
 *   <li>the whitespace-tolerant and blank-skipping fallback matchers,</li>
 *   <li>an empty context line (legacy diff generators emit "" instead of " ").</li>
 * </ul>
 *
 * <p>All inputs are built in code with explicit line terminators, so a git
 * checkout with {@code autocrlf} cannot change the fixture. Expected outputs
 * were captured from the engine; they must stay identical unless a semantic
 * change is deliberate and reviewed.
 */
class PatchOutputRegressionTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // Fixture (explicit \n everywhere - immune to checkout line endings)
    // ------------------------------------------------------------------

    static final String ALPHA = String.join("\n",
        "package demo;",
        "",
        "class Alpha {",
        "    int a = 1;",
        "    int b = 2;",
        "    int c = 3;",
        "    int d = 4;",
        "    int e = 5;",
        "    int f = 6;",
        "    int g = 7;",
        "    int h = 8;",
        "}",
        "");

    static final String BRAVO = String.join("\n",
        "package demo;",
        "",
        "class Bravo {",
        "    int x = 1;",
        "    int  y = 2;",
        "    int z = 3;",
        "}",
        "");

    /** Built LF, converted to CRLF before writing - line endings must survive patching. */
    static final String CRLF = String.join("\n",
        "package demo;",
        "",
        "class Crlf {",
        "    int p = 1;",
        "    int q = 2;",
        "}",
        "").replace("\n", "\r\n");

    // Two patches on Alpha: applied in canonical name order (Alpha-first < Alpha-second).
    static final String ALPHA_PATCH_1 = String.join("\n",
        "--- a/src/Alpha.java",
        "+++ b/src/Alpha.java",
        "@@ -3,7 +3,7 @@",
        " class Alpha {",
        "     int a = 1;",
        "     int b = 2;",
        "-    int c = 3;",
        "+    int c = 30;",
        "     int d = 4;",
        "     int e = 5;",
        "     int f = 6;",
        "");

    static final String ALPHA_PATCH_2 = String.join("\n",
        "--- a/src/Alpha.java",
        "+++ b/src/Alpha.java",
        "@@ -8,5 +8,6 @@",
        "     int e = 5;",
        "     int f = 6;",
        "     int g = 7;",
        "-    int h = 8;",
        "+    int h = 80;",
        "+    int i = 9;",
        " }",
        "");

    /** Context line has single spacing, file has double - exercises the whitespace fallback. */
    static final String BRAVO_PATCH = String.join("\n",
        "--- a/src/beta/Bravo.java",
        "+++ b/src/beta/Bravo.java",
        "@@ -3,5 +3,6 @@",
        " class Bravo {",
        "     int x = 1;",
        "     int y = 2;",
        "+    // trailing context",
        "     int z = 3;",
        " }",
        "");

    /** Empty context line ("") plus blank-skipping fallback against the CRLF file. */
    static final String CRLF_PATCH = String.join("\n",
        "--- a/src/Crlf.java",
        "+++ b/src/Crlf.java",
        "@@ -3,5 +3,5 @@",
        " class Crlf {",
        "     int p = 1;",
        "",
        "-    int q = 2;",
        "+    int q = 22;",
        " }",
        "");

    static final String ZULU_PATCH = String.join("\n",
        "--- /dev/null",
        "+++ b/src/ZuluNew.java",
        "@@ -0,0 +1,3 @@",
        "+class ZuluNew {",
        "+    int n = 1;",
        "+}",
        "");

    // ------------------------------------------------------------------
    // Golden outputs (captured from the engine)
    // ------------------------------------------------------------------

    static final String EXPECTED_ALPHA = String.join("\n",
        "package demo;",
        "",
        "class Alpha {",
        "    int a = 1;",
        "    int b = 2;",
        "    int c = 30;",
        "    int d = 4;",
        "    int e = 5;",
        "    int f = 6;",
        "    int g = 7;",
        "    int h = 80;",
        "    int i = 9;",
        "}",
        "");

    static final String EXPECTED_BRAVO = String.join("\n",
        "package demo;",
        "",
        "class Bravo {",
        "    int x = 1;",
        "    int y = 2;",
        "    // trailing context",
        "    int z = 3;",
        "}",
        "");

    static final String EXPECTED_CRLF = ("package demo;\r\n"
        + "\r\n"
        + "class Crlf {\r\n"
        + "    int p = 1;\r\n"
        + "\r\n"
        + "    int q = 22;\r\n"
        + "}");

    static final String EXPECTED_ZULU = String.join("\n",
        "class ZuluNew {",
        "    int n = 1;",
        "}",
        "");

    // ------------------------------------------------------------------
    // Test
    // ------------------------------------------------------------------

    @Test
    void patchedOutputMatchesGoldenFiles() throws Exception {
        var first = runPipeline();   // fresh workspace
        var second = runPipeline();  // fresh workspace again - determinism

        for (var entry : first.entrySet()) {
            var a = entry.getValue();
            var b = second.get(entry.getKey());
            assertArrayEquals(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8),
                "non-deterministic output for " + entry.getKey());
        }

        assertEquals(EXPECTED_ALPHA, first.get("src/Alpha.java"));
        assertEquals(EXPECTED_BRAVO, first.get("src/beta/Bravo.java"));
        assertEquals(EXPECTED_CRLF, first.get("src/Crlf.java"));
        assertEquals(EXPECTED_ZULU, first.get("src/ZuluNew.java"));
        assertEquals(4, first.size(), "unexpected output file count");
    }

    /**
     * Writes pristine sources + patch files, then runs the production
     * discovery + application path. Returns relative path -> exact file content.
     */
    private java.util.Map<String, String> runPipeline() throws Exception {
        var root = Files.createTempDirectory(tmp, "regression-");
        var pristine = root.resolve("pristine");
        var workspace = root.resolve("workspace");
        var patchesDir = root.resolve("patches");
        Files.createDirectories(workspace);
        Files.createDirectories(patchesDir);

        write(pristine.resolve("src").resolve("Alpha.java"), ALPHA);
        write(pristine.resolve("src").resolve("beta").resolve("Bravo.java"), BRAVO);
        write(pristine.resolve("src").resolve("Crlf.java"), CRLF);
        Files.createDirectories(workspace.resolve("src").resolve("beta"));
        Files.copy(pristine.resolve("src").resolve("Alpha.java"),
            workspace.resolve("src").resolve("Alpha.java"));
        Files.copy(pristine.resolve("src").resolve("beta").resolve("Bravo.java"),
            workspace.resolve("src").resolve("beta").resolve("Bravo.java"));
        Files.copy(pristine.resolve("src").resolve("Crlf.java"),
            workspace.resolve("src").resolve("Crlf.java"));

        write(patchesDir.resolve("001-Alpha-first.patch"), ALPHA_PATCH_1);
        write(patchesDir.resolve("003-Alpha-second.patch"), ALPHA_PATCH_2);
        write(patchesDir.resolve("002-Bravo-strategy.patch"), BRAVO_PATCH);
        write(patchesDir.resolve("005-Crlf-empty-context.patch"), CRLF_PATCH);
        write(patchesDir.resolve("004-Zulu-new.patch"), ZULU_PATCH);

        var stats = new PatchStats();
        var patches = PatchDiscovery.fromDirectory(patchesDir, stats);
        assertEquals(5, patches.size(), "discovery must find all five patches");

        var targets = new RuntimePatchApplier().applyPatches(patches, workspace, stats);
        assertEquals(List.of(
            "src/Alpha.java",
            "src/beta/Bravo.java",
            "src/Crlf.java",
            "src/ZuluNew.java"), targets,
            "groups must be ordered by first patch and never merge distinct files");

        var result = new java.util.TreeMap<String, String>();
        try (var walk = Files.walk(workspace)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var rel = workspace.relativize(file).toString().replace('\\', '/');
                result.put(rel, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        assertTrue(result.containsKey("src/ZuluNew.java"), "new file must be created");
        return result;
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
