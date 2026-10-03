package org.veltismc.world.api;

/**
 * A unit of work executed by a worker.
 *
 * <p>Region-bound jobs (e.g. region tick, messages) may only be executed by the
 * worker that owns the target region. Migratable jobs (e.g. lighting, generation,
 * save batches) may run on any worker; their exclusivity is enforced by chunk
 * state transitions (CAS) instead of ownership.
 */
public interface RegionJob {

    /** Stable name used for metrics grouping. */
    String name();

    /** Scheduling priority; higher-priority jobs drain first. */
    JobPriority priority();

    /** Whether this job may only run on the owner of {@link JobContext#region()}. */
    boolean regionBound();

    /** Whether this job has been cancelled before execution. */
    default boolean isCancelled() {
        return false;
    }

    /** Requests cancellation. Only honored before/at execution boundaries. */
    default void cancel() {
    }

    /** Executes the job body. Throwing is not fatal: the error is reported and recorded. */
    void execute(JobContext ctx) throws Exception;
}
