package org.veltismc.world.scheduler;

import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.Region;

/** Immutable execution context handed to jobs by the worker. */
public record JobContextImpl(Region region, JobHandleImpl handle) implements JobContext {

    @Override
    public boolean cancelled() {
        return handle.isCancelled();
    }
}
