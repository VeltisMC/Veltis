package org.veltismc.world.api;

/** Per-worker utilization snapshot. */
public record WorkerMetrics(
    long id,
    String name,
    boolean active,
    long jobsExecuted,
    long busyNanos,
    long idleNanos,
    int localQueueDepth,
    int ownedRegions,
    String currentJob
) {
    /** Fraction of observed time spent executing jobs, 0..1. */
    public double utilization() {
        long total = busyNanos + idleNanos;
        return total == 0 ? 0.0 : (double) busyNanos / (double) total;
    }
}
