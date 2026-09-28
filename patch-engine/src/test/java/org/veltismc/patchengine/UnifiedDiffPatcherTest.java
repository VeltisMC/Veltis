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
    }

    // ------------------------------------------------------------------
    // Validation: malformed, incomplete, multi-file and unsafe patches
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
        assertTrue(failure.getMessage().contains("missing-source.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Missing.java"), failure.getMessage());
        assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
        assertFalse(Files.exists(dir.resolve("Missing.java")));
    }

    @Test
    void patchTouchingTwoFilesIsRejected() throws Exception {
        Files.writeString(dir.resolve("One.java"), "class One {}\n");
        Files.writeString(dir.resolve("Two.java"), "class Two {}\n");

        var patch = List.of(
            "--- a/One.java",
            "+++ b/One.java",
            "--- a/Two.java",
            "+++ b/Two.java",
            "@@ -1,1 +1,1 @@",
            "-class One {}",
            "+class One { }");

        var failure = assertThrows(PatchEngineException.class,
            () -> new UnifiedDiffPatcher().applyPatchLines(patch, "multi-file.patch", dir));
        assertTrue(failure.getMessage().contains("multi-file.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("multiple"), failure.getMessage());
        // Neither file may be touched when the patch is rejected.
        assertEquals("class One {}\n", Files.readString(dir.resolve("One.java")));
        assertEquals("class Two {}\n", Files.readString(dir.resolve("Two.java")));
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
        assertTrue(failure.getMessage().contains("truncated.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("malformed"), failure.getMessage());
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
        assertTrue(failure.getMessage().contains("badcounts.patch"), failure.getMessage());
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
        assertTrue(failure.getMessage().contains("delete.patch"), failure.getMessage());
        assertTrue(failure.getMessage().contains("deletion"), failure.getMessage());
        assertTrue(Files.exists(dir.resolve("Demo.java")));
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
        assertTrue(msg.contains("reason:"), msg);
        // The first patch's result survives the failed second patch.
        assertEquals("first\n2nd\n", Files.readString(target));
    }
}
