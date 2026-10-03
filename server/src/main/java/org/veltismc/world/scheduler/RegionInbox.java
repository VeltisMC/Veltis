package org.veltismc.world.scheduler;

import org.veltismc.world.api.RegionPos;
import org.veltismc.world.util.LockFreeQueue;

/**
 * The region-bound job queue of a region, implemented by the region package.
 *
 * <p>Producers (any thread) call {@code inbox().add(...)}; the single consumer is
 * the worker that owns the region. Ownership is the only access rule the queue needs:
 * it is a lock-free MPSC queue, so posting a message never blocks.
 */
public interface RegionInbox {

    RegionPos pos();

    LockFreeQueue<JobEnvelope> inbox();
}
