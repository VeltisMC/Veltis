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
 * The diff engine's contract, one case at a time.
 *
 * <p>Everything here runs through {@link UnifiedDiffPatcher#applyPatchLines},
 * which is the single-patch convenience form of the production path: parse,
 * apply, verify, write once. What is being pinned down is the behaviour a patch
 * author depends on — that context survives an edit, that a new file is created,
 * that a patch which does not match changes nothing on disk, and that every way
 * of being wrong produces a message naming the patch, the target and the reason.
 */
class UnifiedDiffPatcherTest {

    @TempDir
    Path dir;

    @Test
    void appliesModificationHunk() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, """
            package demo;
            class Demo {
                int value = 1;
            }
            """);

        var patch = List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -1,4 +1,4 @@",
            " package demo;",
            " class Demo {",
            "-    int value = 1;",
            "+    int value = 2;",
            " }");

        new UnifiedDiffPatcher().applyPatchLines(patch, "test.patch", dir);

        var result = Files.readString(target);
        assertTrue(result.contains("int value = 2;"), result);
        assertFalse(result.contains("int value = 1;"), result);
        // Untouched context survives the edit.
        assertTrue(result.contains("package demo;"), result);
    }

    @Test
    void createsFileFromNewFilePatch() throws Exception {
        var patch = List.of(
            "--- /dev/null",
            "+++ b/generated/New.java",
            "@@ -0,0 +1,3 @@",
            "+class New {",
            "+    int x = 1;",
            "+}");

        new UnifiedDiffPatcher().applyPatchLines(patch, "new.patch", dir);

        assertEquals("class New {\n    int x = 1;\n}\n",
            Files.readString(dir.resolve("generated").resolve("New.java")));
    }

    @Test
    void appliesAPatchThatAddressesSeveralFiles() throws Exception {
        Files.writeString(dir.resolve("One.java"), "class One {\n    int a = 1;\n}\n");
        Files.writeString(dir.resolve("Two.java"), "class Two {\n    int b = 2;\n}\n");

        var patch = List.of(
            "--- a/One.java",
            "+++ b/One.java",
            "@@ -1,3 +1,3 @@",
            " class One {",
            "-    int a = 1;",
            "+    int a = 11;",
            " }",
            "--- a/Two.java",
            "+++ b/Two.java",
            "@@ -1,3 +1,3 @@",
            " class Two {",
            "-    int b = 2;",
            "+    int b = 22;",
            " }");

        var patcher = new UnifiedDiffPatcher();
        patcher.applyPatchLines(patch, "multi-file.patch", dir);

        assertTrue(Files.readString(dir.resolve("One.java")).contains("int a = 11;"));
        assertTrue(Files.readString(dir.resolve("Two.java")).contains("int b = 22;"));
    }

    @Test
    void reportsMismatchedHunkInsteadOfCorrupting() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, "unrelated content\n");

        var patch = List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -1,2 +1,2 @@",
            "-int expectedElsewhere = 1;",
            "+int expectedElsewhere = 2;");

        assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "broken.patch", dir));
        assertEquals("unrelated content\n", Files.readString(target));
    }

    @Test
    void patchWithoutTargetFileIsRejected() {
        var patch = List.of(
            "--- a/nowhere.java",
            "@@ -1,1 +1,1 @@",
            "-old",
            "+new");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "targetless.patch", dir));
        assertTrue(failure.getMessage().contains("targetless.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no matching '+++' target header"),
            failure.getMessage());
    }

    // ------------------------------------------------------------------
    // Validation: malformed, incomplete and unsafe patches
    // ------------------------------------------------------------------

    @Test
    void missingSourceFileIsRejectedWithPatchNameAndTarget() throws Exception {
        var patch = List.of(
            "--- a/Missing.java",
            "+++ b/Missing.java",
            "@@ -1,1 +1,1 @@",
            "-old",
            "+new");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "missing-source.patch", dir));
        var msg = failure.getMessage();
        assertTrue(msg.contains("missing-source.patch"), msg);
        assertTrue(msg.contains("Missing.java"), msg);
        assertTrue(msg.contains("does not exist"), msg);
        assertFalse(Files.exists(dir.resolve("Missing.java")));
    }

    @Test
    void truncatedHunkIsRejectedAsMalformed() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, "line one\nline two\nline three\nline four\n");

        // Header declares four old/four new lines but only two content lines follow.
        var patch = List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -1,4 +1,4 @@",
            " line one",
            "-line two",
            "+line 2");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "truncated.patch", dir));
        var msg = failure.getMessage();
        assertTrue(msg.contains("truncated.patch"), msg);
        assertTrue(msg.contains("hunk #1 declares 4 more content line(s)"), msg);
        assertEquals("line one\nline two\nline three\nline four\n", Files.readString(target));
    }

    @Test
    void hunkHeaderCountsAreHonouredInsteadOfSwallowingFollowingLines() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, "one\ntwo\nthree\nfour\nfive\nsix\n");

        // Header claims one old / one new line, so " three" and " four" are not part
        // of the hunk and must not be silently consumed as content.
        var patch = List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -1,1 +1,1 @@",
            "-one",
            "+1",
            " three",
            " four");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "badcounts.patch", dir));
        var msg = failure.getMessage();
        assertTrue(msg.contains("badcounts.patch"), msg);
        assertTrue(msg.contains("outside any hunk"), msg);
        assertEquals("one\ntwo\nthree\nfour\nfive\nsix\n", Files.readString(target));
    }

    @Test
    void targetEscapingTheSourceDirectoryIsRejected() {
        var patch = List.of(
            "--- a/../../escape.java",
            "+++ b/../../escape.java",
            "@@ -1,1 +1,1 @@",
            "-old",
            "+new");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "escape.patch", dir));
        assertTrue(failure.getMessage().contains("escape.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("escapes"), failure.getMessage());
    }

    @Test
    void fileDeletionIsRejected() throws Exception {
        Files.writeString(dir.resolve("Demo.java"), "keep me\n");

        var patch = List.of(
            "--- a/Demo.java",
            "+++ /dev/null",
            "@@ -1,1 +0,0 @@",
            "-keep me");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "delete.patch", dir));
        var msg = failure.getMessage();
        assertTrue(msg.contains("delete.patch"), msg);
        assertTrue(msg.contains("deletes this file"), msg);
        assertTrue(Files.exists(dir.resolve("Demo.java")));
        assertEquals("keep me\n", Files.readString(dir.resolve("Demo.java")));
    }

    @Test
    void unchangedResultIsNotRewritten() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, "class Demo {\n    int value = 1;\n}\n");

        // Replacement of a line with itself: valid, but the file must not be rewritten.
        var patch = List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -2,1 +2,1 @@",
            "-    int value = 1;",
            "+    int value = 1;");

        var stats = new PatchStats();
        new UnifiedDiffPatcher().applyPatchLines(patch, "noop.patch", dir, stats);

        assertEquals(1, stats.filesUnchanged, "unchanged file should be counted, not rewritten");
        assertEquals(0, stats.filesWritten, "unchanged file must not be written");
        assertEquals(0, stats.filesChanged);
        assertEquals("class Demo {\n    int value = 1;\n}\n", Files.readString(target));
    }

    @Test
    void crlfLineEndingsSurvivePatching() throws Exception {
        var target = dir.resolve("Crlf.java");
        Files.writeString(target, "one\r\ntwo\r\nthree\r\n");

        var patch = List.of(
            "--- a/Crlf.java",
            "+++ b/Crlf.java",
            "@@ -1,3 +1,3 @@",
            " one",
            "-two",
            "+TWO",
            " three");

        new UnifiedDiffPatcher().applyPatchLines(patch, "crlf.patch", dir);

        assertEquals("one\r\nTWO\r\nthree\r\n", Files.readString(target));
    }

    @Test
    void chainReportsRichDiagnosticsNamingTheFailingPatch() throws Exception {
        var target = dir.resolve("Demo.java");
        Files.writeString(target, "first\nsecond\n");

        var patcher = new UnifiedDiffPatcher();
        patcher.applyPatchLines(List.of(
            "--- a/Demo.java",
            "+++ b/Demo.java",
            "@@ -1,2 +1,2 @@",
            " first",
            "-second",
            "+2nd"), "01-First.patch", dir);

        var failure = assertThrows(PatchEngineException.class,
            () -> patcher.applyPatchLines(List.of(
                "--- a/Demo.java",
                "+++ b/Demo.java",
                "@@ -1,2 +1,2 @@",
                " first",
                "-not in the file",
                "+x"), "02-Second.patch", dir));

        var msg = failure.getMessage();
        assertTrue(msg.contains("02-Second.patch"), msg);
        assertTrue(msg.contains("Demo.java"), msg);
        assertTrue(msg.contains("hunk #1"), msg);
        assertTrue(msg.contains("expected context"), msg);
        assertTrue(msg.contains("actual source lines"), msg);
        assertTrue(msg.contains("Reason:"), msg);
        assertTrue(msg.contains("Minecraft: <unknown>"), msg);
        // The first patch's result survives the failed second patch.
        assertEquals("first\n2nd\n", Files.readString(target));
    }
}
