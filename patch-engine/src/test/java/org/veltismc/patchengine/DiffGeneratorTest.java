package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff generator and the diff applier must agree.
 *
 * <p>{@code rebuildVeltisPatches} is only useful if what it writes can be applied
 * again by the same engine, so the property under test is a round trip: generate
 * a diff from a before/after pair, apply it to the before text, and get the
 * after text back. Everything else here — determinism, the empty-diff case, the
 * refusal to emit a deletion — is a corollary of that.
 */
class DiffGeneratorTest {

    @TempDir
    Path tmp;

    /** Applies generated diff lines to {@code before} and returns the result. */
    private String roundTrip(String target, String before, String after) {
        return roundTrip(target, before, after, true);
    }

    /**
     * @param present whether {@code before} already exists on disk. A creating
     *                patch addresses a file that is not there, so that is the
     *                case worth exercising separately.
     */
    private String roundTrip(String target, String before, String after, boolean present) {
        var diff = DiffGenerator.diff(target, present ? before : null, after);
        var root = tmp.resolve("ws-" + Math.abs(diff.hashCode()) + "-" + diff.size()
            + "-" + present);
        try {
            Files.createDirectories(root);
            if (present) {
                var file = root.resolve(target);
                Files.createDirectories(file.getParent());
                Files.writeString(file, before);
            }
            new UnifiedDiffPatcher().applyPatchLines(diff, "generated.patch", root);
            return Files.readString(root.resolve(target));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void identicalTextsProduceNoDiff() {
        assertEquals(List.of(), DiffGenerator.diff("A.java", "a\nb\n", "a\nb\n"));
        assertEquals(List.of(), DiffGenerator.diff("A.java", null, null));
    }

    @Test
    void aSingleChangedLineRoundTrips() {
        var before = "package p;\n\nclass A {\n    int v = 1;\n}\n";
        var after = "package p;\n\nclass A {\n    int v = 2;\n}\n";
        assertEquals(after, roundTrip("A.java", before, after));
    }

    @Test
    void aCreatedFileRoundTrips() {
        var diff = DiffGenerator.diff("new/A.java", null, "class A {\n}\n");
        assertTrue(diff.get(0).startsWith("diff --git"), diff.toString());
        assertTrue(diff.contains("--- /dev/null"), diff.toString());
        assertEquals("class A {\n}\n", roundTrip("new/A.java", null, "class A {\n}\n", false),
            "the target did not exist, so the patch has to create it");
    }

    @Test
    void manySeparateEditsRoundTrip() {
        var before = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            before.append("    int v").append(i).append(" = ").append(i).append(";\n");
        }
        var after = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            after.append("    int v").append(i).append(" = ")
                .append(i % 17 == 0 ? i + 1000 : i).append(";\n");
        }
        var text = "class A {\n" + before + "}\n";
        var changed = "class A {\n" + after + "}\n";
        assertEquals(changed, roundTrip("A.java", text, changed));
    }

    @Test
    void adjacentEditsAreMergedIntoOneHunk() {
        var before = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\nl11\nl12\n";
        var after = "l1\nX2\nl3\nl4\nl5\nl6\nl7\nl8\nX9\nl10\nl11\nl12\n";
        var diff = DiffGenerator.diff("A.txt", before, after);
        var hunks = diff.stream().filter(l -> l.startsWith("@@")).count();
        assertEquals(1, hunks, "edits within one context window belong to one hunk:\n" + diff);
        assertEquals(after, roundTrip("A.txt", before, after));
    }

    @Test
    void distantEditsBecomeSeparateHunks() {
        var before = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            before.append("line").append(i).append('\n');
        }
        var after = new StringBuilder(before);
        after.setCharAt(0, 'L');
        after.setCharAt(after.length() - 2, 'X');
        var diff = DiffGenerator.diff("A.txt", before.toString(), after.toString());
        assertEquals(2, diff.stream().filter(l -> l.startsWith("@@")).count(),
            "far apart edits must not share context:\n" + diff);
        assertEquals(after.toString(), roundTrip("A.txt", before.toString(), after.toString()));
    }

    @Test
    void theSameInputAlwaysProducesTheSameBytes() {
        var before = "class A {\n    int a = 1;\n    int b = 2;\n}\n";
        var after = "class A {\n    int a = 9;\n    int b = 8;\n    int c = 7;\n}\n";
        assertEquals(DiffGenerator.diff("A.java", before, after),
            DiffGenerator.diff("A.java", before, after));
    }

    @Test
    void randomisedPairsAlwaysRoundTrip() {
        var random = new Random(20260929L);
        for (int iteration = 0; iteration < 40; iteration++) {
            var before = randomLines(random, 1 + random.nextInt(40));
            var after = mutate(before, random);
            assertEquals(after, roundTrip("A.txt", before, after),
                "round trip failed for:\n--- before\n" + before + "--- after\n" + after);
        }
    }

    @Test
    void aDiffThatWouldDeleteTheFileIsRefused() {
        var failure = assertThrows(PatchEngineException.class,
            () -> DiffGenerator.diff("A.txt", "a\nb\n", ""));
        assertTrue(failure.getMessage().contains("never delete"), failure.getMessage());
    }

    @Test
    void aGeneratedDiffIsConsumedByTheRealParser() {
        // The generated header counts must match the emitted lines exactly, which
        // is what the parser validates. A round trip through discovery proves it.
        var patches = tmp.resolve("Shulker");
        var before = "class A {\n    int v = 1;\n    int w = 2;\n}\n";
        var after = "class A {\n    int v = 3;\n    int w = 4;\n}\n";
        try {
            Files.createDirectories(patches.resolve("code"));
            Files.writeString(patches.resolve("code/001-A.patch"),
                String.join("\n", DiffGenerator.diff("A.java", before, after)) + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        var discovered = PatchDiscovery.discover(patches, "26.3", new PatchStats());
        assertEquals(List.of("A.java"), discovered.get(0).targets());
    }

    private static String randomLines(Random random, int count) {
        var sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(random.nextInt(6)).append('\n');
        }
        return sb.toString();
    }

    private static String mutate(String before, Random random) {
        var lines = new java.util.ArrayList<>(List.of(before.split("\n", -1)));
        var edits = 1 + random.nextInt(6);
        for (int i = 0; i < edits && !lines.isEmpty(); i++) {
            var at = random.nextInt(lines.size());
            switch (random.nextInt(3)) {
                case 0 -> lines.set(at, "edited" + i);
                case 1 -> lines.add(at, "inserted" + i);
                default -> lines.remove(at);
            }
        }
        var joined = String.join("\n", lines);
        return joined.endsWith("\n") ? joined : joined + "\n";
    }
}
