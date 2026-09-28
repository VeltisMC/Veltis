package org.veltismc.patchengine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Applies patches to a decompiled source directory.
 *
 * <p>Two phases, deliberately small:
 *
 * <ol>
 *   <li><b>Plan</b> - group patches by their target file (deterministic order:
 *       groups follow first-patch order, patches inside a group keep their
 *       discovery order). This is the "patch plan".</li>
 *   <li><b>Execute</b> - one worker per target file; each worker runs the whole
 *       chain for its file through {@link UnifiedDiffPatcher#applyGroup}
 *       (read once -> apply in memory -> write once if changed).</li>
 * </ol>
 *
 * <p>Parallelism is bounded by the number of independent target files and runs on
 * virtual threads (one per file, never one per patch); patches that touch the same
 * file are never parallelised, so ordering - and therefore the output - is
 * deterministic regardless of scheduling.
 */
public class RuntimePatchApplier {

    private final UnifiedDiffPatcher patcher;

    public RuntimePatchApplier() {
        this(new UnifiedDiffPatcher());
    }

    public RuntimePatchApplier(UnifiedDiffPatcher patcher) {
        this.patcher = patcher;
    }

    public List<String> applyPatches(List<PatchContent> patches, Path targetDir) {
        return applyPatches(patches, targetDir, new PatchStats());
    }

    public List<String> applyPatches(List<PatchContent> patches, Path targetDir, PatchStats stats) {
        // Plan: group by target file. LinkedHashMap keeps groups in first-patch
        // order, so planning and the returned file list are deterministic.
        long planStart = System.nanoTime();
        var byTarget = new LinkedHashMap<String, List<PatchContent>>();
        for (var patch : patches) {
            var targetFile = UnifiedDiffPatcher.targetKeyOf(patch.lines());
            byTarget.computeIfAbsent(targetFile, k -> new ArrayList<>()).add(patch);
        }
        var groups = new ArrayList<>(byTarget.entrySet());
        stats.discoveryNanos += System.nanoTime() - planStart;

        // One stats instance per group: workers never share mutable counters.
        var groupStats = new PatchStats[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            groupStats[i] = new PatchStats();
        }

        // Bounded parallelism: one virtual thread per target file, but at most
        // `permits` groups executing at once - unlimited concurrent groups only
        // create filesystem contention (measured), they do not reduce wall time.
        var permits = new java.util.concurrent.Semaphore(
            Math.max(1, Runtime.getRuntime().availableProcessors()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>(groups.size());
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                var localStats = groupStats[i];
                futures.add(executor.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new PatchEngineException("Patch application interrupted", e);
                    }
                    try {
                        patcher.applyGroup(group.getKey(), group.getValue(), targetDir, localStats);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (Exception e) {
            var cause = e.getCause();
            if (cause instanceof PatchEngineException pe) {
                throw pe;
            }
            throw new PatchEngineException("Failed to apply patches", cause != null ? cause : e);
        }
        for (var localStats : groupStats) {
            stats.merge(localStats);
        }
        return new ArrayList<>(byTarget.keySet());
    }

    public record PatchContent(String name, List<String> lines) {
    }
}
