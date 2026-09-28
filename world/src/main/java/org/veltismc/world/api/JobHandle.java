package org.veltismc.world.api;

/**
 * Handle to a scheduled job, used for cancellation and status queries.
 */
public interface JobHandle {

    /** Cancels the job if it has not started (or as soon as it checks the flag). */
    void cancel();

    boolean isCancelled();

    boolean isDone();
}
