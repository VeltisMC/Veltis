package org.veltismc.world.scheduler;

import org.veltismc.world.api.JobHandle;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation + completion state of a scheduled job. */
public final class JobHandleImpl implements JobHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean done;

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Attempts to mark this job as started. Succeeds only if the job has not been
     * cancelled and has not already started. This provides an atomic gate to prevent
     * execution of cancelled jobs.
     *
     * @return true if the job was successfully claimed for execution, false if it was
     *         cancelled or already started
     */
    public boolean tryStart() {
        return !cancelled.get() && started.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public boolean isDone() {
        return done;
    }

    public void markDone() {
        done = true;
    }
}
