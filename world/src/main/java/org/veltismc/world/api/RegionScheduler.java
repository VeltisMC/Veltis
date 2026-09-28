package org.veltismc.world.api;

/**
 * Schedules jobs across workers and regions.
 *
 * <p>Region-bound jobs are routed into the owning region's inbox. Migratable jobs
 * enter priority queues and are picked up by any idle worker (work stealing).
 * The scheduler never busy-waits: workers park and are unparked on new work.
 */
public interface RegionScheduler {

    /** Schedules a migratable job on the global priority queues. */
    JobHandle schedule(RegionJob job);

    /** Schedules a job to run on the given region's owning worker. */
    JobHandle schedule(Region region, RegionJob job);

    /** Total number of queued jobs (global queues, delay queue, region inboxes). */
    int pendingJobs();

    /** Number of workers that are currently processing work. */
    int activeWorkers();

    /** Cancels all queued jobs and prevents further scheduling. */
    void shutdown();
}
