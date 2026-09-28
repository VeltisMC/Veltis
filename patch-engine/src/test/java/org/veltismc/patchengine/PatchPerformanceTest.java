package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural performance invariants of the patch pipeline.
 *
 * <p>These are deliberately not micro-benchmarks: wall-clock numbers belong to the
 * {@code benchmark} task, which is run and compared against a saved baseline. What
 * this test locks down is the <em>shape</em> of the work, because that is what
 * actually decides the cost of a run:
 *
 * <ul>
 *   <li>every target file is read at most once per run and written at most once;</li>
 *   <li>only files whose content changed are written at all;</li>
 *   <li>every discovered patch is applied exactly once;</li>
 *   <li>output is byte-identical run to run.</li>
 * </ul>
 *
 * <p>The single wall-clock assertion is a very generous ceiling (orders of magnitude
 * above the measured time) whose only job is to fail on a catastrophic regression,
 * such as reintroducing per-hunk rewrites or unbounded parallelism.
 */
class PatchPerformanceTest {

    /** 60 files / 120 patches: big enough to expose per-file I/O, fast enough for CI. */
    private static final Map<String, String> CORPUS = Map.of(
        "files", "60",
        "lines", "200",
        "patches", "120");

    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 4;

    /** Generous ceiling for a 120-patch run (measured: single-digit milliseconds). */
    private static final double MAX_WARM_TOTAL_MS = 1_500.0;

    @Test
    void readsEachTargetOnceAndWritesOnlyWhatChanged() throws Exception {
        var corpus = PatchBench.scaleCorpus(CORPUS);
        try {
            PatchBench.benchVeltis(corpus, WARMUP_RUNS);
            var result = PatchBench.benchVeltis(corpus, MEASURED_RUNS);

            assertTrue(result.verified, "output must be byte-identical across runs"
                + " and match the expected result (" + result.verifyFailures + " failure(s))");

            var last = result.counters.get(result.counters.size() - 1);
            var filesRead = last[0];
            var filesWritten = last[1];
            var filesChanged = last[2];
            var patchesApplied = last[3];
            var hunksParsed = last[4];
            var filesUnchanged = last[5];

            // Each target is read at most once per run (chain applied in memory).
            assertTrue(filesRead <= corpus.targets.size(),
                "read " + filesRead + " times for " + corpus.targets.size() + " targets");
            // One write per changed file, and nothing else is written.
            assertEquals(filesChanged, filesWritten, "every write must change the file");
            assertTrue(filesUnchanged >= 0);
            // Every patch is applied exactly once; nothing is silently skipped.
            assertEquals(corpus.patchCount, patchesApplied);
            assertTrue(hunksParsed >= patchesApplied, "each patch has at least one hunk");
            // The corpus touches every target, so read+unchanged accounting must close.
            assertEquals(corpus.targets.size(), filesWritten + filesUnchanged,
                "each target is either written once or explicitly skipped");

            // Every iteration must produce the same counters (deterministic plan).
            for (var counters : result.counters) {
                assertEquals(last[0], counters[0], "files read varies between runs");
                assertEquals(last[1], counters[1], "files written varies between runs");
                assertEquals(last[3], counters[3], "patches applied varies between runs");
            }

            var warmAvgTotal = result.phases
                .subList(1, result.phases.size())   // phases[0] is the cold run
                .stream()
                .mapToDouble(p -> p[4])
                .average()
                .orElseThrow();
            assertTrue(warmAvgTotal < MAX_WARM_TOTAL_MS,
                "warm average total " + warmAvgTotal + " ms exceeds ceiling "
                    + MAX_WARM_TOTAL_MS + " ms");
        } finally {
            PatchBench.deleteRecursively(corpus.workspace.getParent());
        }
    }

    @Test
    void reapplyingToAnAlreadyPatchedWorkspaceRewritesNothing() throws Exception {
        var corpus = PatchBench.scaleCorpus(Map.of(
            "files", "20", "lines", "60", "patches", "40"));
        try {
            var stats = new PatchStats();
            var patches = PatchDiscovery.fromDirectory(corpus.patchesDir, stats);
            corpus.restore();   // workspace starts empty; pristine inputs are the baseline
            new RuntimePatchApplier().applyPatches(patches, corpus.workspace, stats);

            assertEquals(corpus.patchCount, stats.patchesApplied, "first application");

            // Second application on the untouched result: context no longer matches,
            // so it must fail loudly rather than silently skip or corrupt files.
            var second = new PatchStats();
            boolean failed = false;
            try {
                new RuntimePatchApplier().applyPatches(patches, corpus.workspace, second);
            } catch (PatchEngineException e) {
                failed = true;
                assertFalse(e.getMessage() == null || e.getMessage().isBlank());
            }
            assertTrue(failed, "re-applying patched sources must fail, not be skipped");

            // The failed attempt may not have rewritten anything: hashes are unchanged.
            var hashes = corpus.hashes();
            for (var target : corpus.targets) {
                assertEquals(corpus.expected.get(target), hashes.normalized().get(target),
                    "target changed during the failed re-application: " + target);
            }
        } finally {
            PatchBench.deleteRecursively(corpus.workspace.getParent());
        }
    }
}
