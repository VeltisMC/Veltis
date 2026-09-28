package org.veltismc.world.scheduler;

import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionJob;

/** Scheduler-internal envelope: job + handle + target region + timing metadata. */
public final class JobEnvelope {

    /** Target region; {@code null} for migratable engine-level jobs. */
    public final Region region;
    public final RegionJob job;
    public final JobHandleImpl handle;
    public final long submittedNanos;
    public volatile long startNanos;

    /** Envelope for a migratable engine-level job. */
    public JobEnvelope(RegionJob job, JobHandleImpl handle) {
        this(null, job, handle);
    }

    /** Envelope for a region-bound job. */
    public JobEnvelope(Region region, RegionJob job, JobHandleImpl handle) {
        this.region = region;
        this.job = job;
        this.handle = handle;
        this.submittedNanos = System.nanoTime();
    }
}
