package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-for-byte output regression for the patch pipeline.
 *
 * <p>The corpus below exercises the production path end to end: patch files on
 * disk in {@code patches/code} -> {@link PatchDiscovery} ->
 * {@link VeltisPatcher} (grouping, ordering, parallel application) -> written
 * source files. It covers:
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
        "    int  y = 2;",
        "    // trailing context",
        "    int z = 3;",
        "}",
        "");

    // The file ends with a newline, so the trailing line break is part of the
    // content and patching must not eat it.
    static final String EXPECTED_CRLF = ("package demo;\r\n"
        + "\r\n"
        + "class Crlf {\r\n"
        + "    int p = 1;\r\n"
        + "\r\n"
        + "    int q = 22;\r\n"
        + "}\r\n");

    static final String EXPECTED_ZULU = String.join("\n",
        "class ZuluNew {",
        "    int n = 1;",
        "}",
        "");

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void patchedOutputMatchesGoldenFiles() {
        var fixture = corpus();
        var stats = fixture.applyReporting(4);
        assertEquals(5, stats.patchesApplied, "discovery must find all five patches");

        var tree = fixture.patchedTree();
        assertEquals(EXPECTED_ALPHA, tree.get("src/Alpha.java"));
        assertEquals(EXPECTED_BRAVO, tree.get("src/beta/Bravo.java"));
        assertEquals(EXPECTED_CRLF, tree.get("src/Crlf.java"));
        assertEquals(EXPECTED_ZULU, tree.get("src/ZuluNew.java"));
        assertEquals(4, tree.size(), "unexpected output file count");
    }

    @Test
    void everyWorkerCountProducesIdenticalBytes() {
        Map<String, String> reference = null;
        for (var workers : new int[] {1, 4, 8}) {
            var fixture = corpus();
            fixture.apply(workers);
            var tree = fixture.patchedTree();
            if (reference == null) {
                reference = tree;
            } else {
                assertEquals(reference, tree, "output differs with " + workers + " workers");
            }
        }
    }

    private TestWorkspace corpus() {
        return TestWorkspace.create(tmp.resolve("regression-" + System.nanoTime()))
            .source("src/Alpha.java", ALPHA)
            .source("src/beta/Bravo.java", BRAVO)
            .source("src/Crlf.java", CRLF)
            .patch(PatchCategory.CODE, "001-Alpha-first.patch", ALPHA_PATCH_1)
            .patch(PatchCategory.CODE, "002-Bravo-strategy.patch", BRAVO_PATCH)
            .patch(PatchCategory.CODE, "003-Alpha-second.patch", ALPHA_PATCH_2)
            .patch(PatchCategory.CODE, "004-Zulu-new.patch", ZULU_PATCH)
            .patch(PatchCategory.CODE, "005-Crlf-empty-context.patch", CRLF_PATCH);
    }
}
