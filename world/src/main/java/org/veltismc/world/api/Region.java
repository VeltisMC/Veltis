package org.veltismc.world.api;

import java.util.List;

/**
 * A world region: the ownership boundary of the engine.
 *
 * <p>Each region is owned by exactly one worker. Only that worker may mutate the
 * region's chunks, entities, and simulation state. All cross-region interaction
 * happens through message passing: work is posted into a region's inbox and
 * executed by its owner.
 */
public interface Region {

    RegionPos pos();

    World world();

    RegionScheduler scheduler();

    /** Whether the given chunk position lies within this region. */
    boolean owns(ChunkPos pos);

    /** Snapshot of all loaded chunks owned by this region. */
    List<ChunkHandle> loadedChunks();

    /** Number of queued messages/jobs waiting for this region's owner. */
    int pendingMessages();

    /** Name of the worker currently owning this region. */
    String ownerName();

    /** Whether simulation is active for this region (false while paused). */
    boolean isActive();

    /**
     * Posts a region-bound job into this region's inbox. It is executed by the
     * owning worker only.
     */
    JobHandle submit(RegionJob job);
}
