package org.veltismc.world.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable diagnostics snapshot of the whole engine. Numbers are point-in-time.
 */
public record WorldMetrics(
    long startedAtMillis,
    long uptimeMillis,
    int workerCount,
    int activeWorkers,
    long totalJobsExecuted,
    long totalJobNanos,
    Map<String, JobTypeStats> jobStats,
    Map<JobPriority, Integer> pendingJobsByPriority,
    Map<RegionPos, String> regionOwners,
    Map<String, PoolStats> pools,
    int pendingChunkLoads,
    int pendingChunkSaves,
    int pendingChunkUnloads,
    int lightingQueueDepth,
    int generationQueueDepth,
    long regionMigrations,
    long deadlockDetections,
    long longRunningJobs,
    long jobErrors,
    List<WorkerMetrics> workers
) {
}
