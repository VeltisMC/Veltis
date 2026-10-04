package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-for-byte output regression for the patch pipeline.
 *
 * <p>The corpus below exercises the production path end to end: patch files on
 * disk in {@code Shulker/code} -> {@link PatchDiscovery} ->
 * {@link VeltisPatcher} -> Git applying the set -> written source files. It
 * covers:
 *
 * <ul>
 *   <li>two patches chained on one source file (deterministic order),</li>
 *   <li>independent files applied in one Git call,</li>
 *   <li>a new-file patch ({@code --- /dev/null}),</li>
 *   <li>context that must match the source byte for byte, spacing included,</li>
 *   <li>an empty context line (legacy diff generators emit "" instead of " "),</li>
 *   <li>the trailing line break of the file surviving the patch.</li>
 * </ul>
 *
 * <p>All inputs are built in code with explicit line terminators, so a git
 * checkout with {@code autocrlf} cannot change the fixture. Expected outputs
 * were captured from the engine; they must stay identical unless a semantic
 * change is deliberate and reviewed.
 *
 * <p>Targets are LF because the decompiled source is, and because
 * {@code .gitattributes} pins patch files to {@code eol=lf}. Under that
 * arrangement Git's exact matching is what keeps the output reproducible, and
 * {@link #aCrlfTargetIsRejectedRatherThanSilentlyRewritten} records the cost of
 * exactness for the case it does not cover.
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

    /**
     * LF, like every other target, and holding the blank line that
     * {@link #BLANK_PATCH} addresses in the legacy empty-context form.
     */
    static final String BLANK = String.join("\n",
        "package demo;",
        "",
        "class Blank {",
        "    int p = 1;",
        "    int q = 2;",
        "}",
        "");

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

    /**
     * Context must match the source byte for byte, spacing included.
     *
     * <p>The source genuinely has a double space there and the patch repeats it.
     * A patch that re-spaced the line would be editing a line it does not claim
     * to change, so Git refuses it instead of reformatting the file.
     */
    static final String BRAVO_PATCH = String.join("\n",
        "--- a/src/beta/Bravo.java",
        "+++ b/src/beta/Bravo.java",
        "@@ -3,5 +3,6 @@",
        " class Bravo {",
        "     int x = 1;",
        "     int  y = 2;",
        "+    // trailing context",
        "     int z = 3;",
        " }",
        "");

    /**
     * An empty context line — {@code ""} where a modern generator writes
     * {@code " "} — against a genuinely blank source line.
     *
     * <p>Legacy generators emit the empty form, Git accepts it, and a patch
     * written that way must still apply.
     */
    static final String BLANK_PATCH = String.join("\n",
        "--- a/src/Blank.java",
        "+++ b/src/Blank.java",
        "@@ -2,5 +2,5 @@",
        "",
        " class Blank {",
        "     int p = 1;",
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
    static final String EXPECTED_BLANK = String.join("\n",
        "package demo;",
        "",
        "class Blank {",
        "    int p = 1;",
        "    int q = 22;",
        "}",
        "");

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
        assertEquals(EXPECTED_BLANK, tree.get("src/Blank.java"));
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
            .source("src/Blank.java", BLANK)
            .patch(PatchCategory.CODE, "001-Alpha-first.patch", ALPHA_PATCH_1)
            .patch(PatchCategory.CODE, "002-Bravo-strategy.patch", BRAVO_PATCH)
            .patch(PatchCategory.CODE, "003-Alpha-second.patch", ALPHA_PATCH_2)
            .patch(PatchCategory.CODE, "004-Zulu-new.patch", ZULU_PATCH)
            .patch(PatchCategory.CODE, "005-Blank-empty-context.patch", BLANK_PATCH);
    }

    /**
     * The one case exact matching does not cover, pinned so the trade is visible.
     *
     * <p>Git is run with {@code core.autocrlf=false} so the patched bytes cannot
     * depend on whose machine ran the build, and at those settings an LF patch
     * does not match a CRLF target. That costs the tolerance the replaced applier
     * had; what it buys is that such a target is refused rather than quietly
     * rewritten to the other convention, which would change every line of a file
     * a developer then compiled.
     *
     * <p>It does not arise in production: the decompiled source is LF and
     * {@code .gitattributes} pins patch files to {@code eol=lf}.
     */
    @Test
    void aCrlfTargetIsRejectedRatherThanSilentlyRewritten() {
        var crlf = String.join("\r\n",
            "package demo;",
            "",
            "class Crlf {",
            "    int q = 2;",
            "}",
            "");
        var fixture = TestWorkspace.create(tmp.resolve("crlf-target"))
            .source("src/Crlf.java", crlf)
            .patch(PatchCategory.CODE, "001-Crlf.patch", String.join("\n",
                "--- a/src/Crlf.java",
                "+++ b/src/Crlf.java",
                "@@ -3,3 +3,3 @@",
                " class Crlf {",
                "-    int q = 2;",
                "+    int q = 22;",
                " }",
                ""));

        var failure = assertThrows(PatchEngineException.class, () -> fixture.apply(1));
        var message = failure.getMessage();
        assertTrue(message.contains("line ending"), message);
        assertTrue(message.contains("Patch: 001-Crlf.patch"), message);

        assertEquals(crlf, fixture.patched("src/Crlf.java"),
            "a target Git refuses must be left exactly as it was");
    }
}
