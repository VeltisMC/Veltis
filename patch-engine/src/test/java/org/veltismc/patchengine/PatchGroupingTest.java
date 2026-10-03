package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grouping decides the concurrency model, so it is tested on its own.
 *
 * <p>The claim the engine makes is structural rather than advisory: work is
 * grouped by target file, so two patches for the same file are in the same group
 * and cannot run at the same time, while distinct files land in distinct groups
 * and are independent. That property is what makes a parallel patch run safe
 * without a single lock, and it is worth pinning down directly rather than only
 * observing its effects.
 */
class PatchGroupingTest {

    @TempDir
    Path tmp;

    private static final String A = "net/minecraft/A.java";
    private static final String B = "net/minecraft/B.java";
    private static final String C = "net/minecraft/C.java";

    /**
     * Builds a patch set from {@code "<category> <file name> <target>"} specs.
     *
     * <p>Each patch creates its target, so the fixture is only about grouping and
     * discovery order — nothing here needs a pristine tree to patch against.
     */
    private List<VeltisPatch> patches(String name, String... specs) {
        var fixture = TestWorkspace.create(tmp.resolve(name));
        for (var spec : specs) {
            var parts = spec.split(" ", 3);
            var category = PatchCategory.ofDirectory(parts[0]);
            fixture.patch(category, parts[1], TestWorkspace.createPatch(parts[2], "content"));
        }
        fixture.materialize();
        return fixture.discover();
    }

    @Test
    void groupsKeepFirstSeenOrderAndChainWithinAGroup() {
        var discovered = patches("grouping",
            "code 001-A.patch " + A,
            "code 002-B.patch " + B,
            "code 003-A.patch " + A,
            "data 004-A.patch " + A);

        var groups = VeltisPatcher.groupByTarget(discovered);

        assertEquals(List.of(A, B), List.copyOf(groups.keySet()),
            "B was seen between the two A patches, so A stays first in the map");
        assertEquals(3, groups.get(A).size(),
            "all three patches for A form one chain, in discovery order");
        assertEquals(List.of("001-A.patch", "003-A.patch", "004-A.patch"),
            groups.get(A).stream().map(e -> e.patch().name()).toList(),
            "code before data, regardless of the file name");
        assertEquals(1, groups.get(B).size());
        assertEquals(A, groups.get(A).get(0).file().target());
    }

    @Test
    void everyPatchOfEveryChainIsAccountedFor() {
        var discovered = patches("accounting",
            "code 001-A.patch " + A,
            "code 002-B.patch " + B,
            "code 003-A.patch " + A,
            "modules 004-C.patch " + C);

        var groups = VeltisPatcher.groupByTarget(discovered);
        var entries = groups.values().stream().mapToInt(List::size).sum();

        assertEquals(discovered.size(), entries, "no patch may be dropped or double-counted");
        assertEquals(3, groups.size());
    }

    @Test
    void aMultiFilePatchContributesToEachGroupIndependently() {
        var fixture = TestWorkspace.create(tmp.resolve("multifile"))
            .patch(PatchCategory.CODE, "001-Multi.patch", String.join("\n",
                "--- a/" + A,
                "+++ b/" + A,
                "@@ -1,1 +1,1 @@",
                "-class A {}",
                "+class A { }",
                "--- a/" + B,
                "+++ b/" + B,
                "@@ -1,1 +1,1 @@",
                "-class B {}",
                "+class B { }",
                ""))
            .materialize();

        var groups = VeltisPatcher.groupByTarget(fixture.discover());

        assertEquals(List.of(A, B), List.copyOf(groups.keySet()));
        assertEquals(1, groups.get(A).size());
        assertEquals(1, groups.get(B).size());
    }

    @Test
    void groupsAreOrderedIdenticallyAcrossWorkerCounts() {
        // The group order is computed before any thread starts, so it cannot
        // depend on the pool size. Verified through the reported target order.
        List<String> first = null;
        for (var workers : new int[] {1, 4, 8}) {
            var fixture = TestWorkspace.create(tmp.resolve("order-" + workers))
                .source(A, "class A {}\n")
                .source(B, "class B {}\n")
                .source(C, "class C {}\n")
                .patch(PatchCategory.CODE, "001-C.patch", TestWorkspace.createPatch(C, "c"))
                .patch(PatchCategory.CODE, "002-A.patch", TestWorkspace.createPatch(A, "a"))
                .patch(PatchCategory.DATA, "003-B.patch", TestWorkspace.createPatch(B, "b"))
                .materialize();
            var seen = new ArrayList<>(VeltisPatcher.groupByTarget(fixture.discover()).keySet());
            if (first == null) {
                first = seen;
            } else {
                assertEquals(first, seen, "group order must not depend on the worker count");
            }
            assertEquals(List.of(C, A, B), seen);
        }
    }

    @Test
    void aFailedGroupIsReportedWithoutDiscardingTheWorkThatSucceeded() {
        var fixture = TestWorkspace.create(tmp.resolve("failure"))
            .source(A, "class A {}\n")
            .source(B, "class B {}\n")
            // Addresses A with context that does not exist in the file.
            .patch(PatchCategory.CODE, "001-Bad.patch", String.join("\n",
                "--- a/" + A,
                "+++ b/" + A,
                "@@ -1,1 +1,1 @@",
                "-this line is absent",
                "+replacement",
                ""))
            .patch(PatchCategory.CODE, "002-Good.patch", TestWorkspace.createPatch(B, "fine"));

        assertThrows(PatchEngineException.class, () -> fixture.apply(4),
            "the run must stop and report, not carry on silently");

        assertTrue(Files.exists(fixture.patchedRoot().resolve(B)),
            "an unrelated target in the same run may still complete");
        assertEquals("class A {}\n", fixture.patched(A), "the failing target is untouched");
    }

    @Test
    void statisticsAccountForExactlyTheWorkDone() {
        var fixture = TestWorkspace.create(tmp.resolve("stats"))
            .source(A, "class A {\n    int v = 1;\n}\n")
            .source(B, "class B {\n    int v = 1;\n}\n")
            .patch(PatchCategory.CODE, "001-A.patch", TestWorkspace.modifyPatch(A,
                "class A {\n    int v = 1;\n}\n", "class A {\n    int v = 2;\n}\n"))
            // A no-op patch: valid, changes nothing, so the file is not rewritten.
            .patch(PatchCategory.CODE, "002-Noop.patch", TestWorkspace.modifyPatch(B,
                "class B {\n    int v = 1;\n}\n", "class B {\n    int v = 1;\n}\n"));

        var stats = fixture.applyReporting(4);

        assertEquals(2, stats.patchesApplied);
        assertEquals(2, stats.filesRead);
        assertEquals(1, stats.filesWritten);
        assertEquals(1, stats.filesChanged);
        assertEquals(1, stats.filesUnchanged, "the no-op must not cause a write");
        assertEquals(Map.of(A, "class A {\n    int v = 2;\n}\n",
            B, "class B {\n    int v = 1;\n}\n"), fixture.patchedTree());
    }
}
