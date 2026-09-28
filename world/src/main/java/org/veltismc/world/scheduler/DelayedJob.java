package org.veltismc.world.scheduler;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/** A job scheduled to run at a future time (region tick cadence, autosave, ...). */
public record DelayedJob(JobEnvelope env, long scheduledNanos) implements Delayed {

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(scheduledNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        return Long.compare(scheduledNanos, ((DelayedJob) other).scheduledNanos);
    }
}
