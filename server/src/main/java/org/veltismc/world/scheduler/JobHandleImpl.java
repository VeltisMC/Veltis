package org.veltismc.world.scheduler;

import org.veltismc.world.api.JobHandle;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation + completion state of a scheduled job. */
public final class JobHandleImpl implements JobHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean done;

    @Override
    public void cancel() {
        cancelled.set(true);
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
