package org.veltismc.world.api;

/**
 * Fully asynchronous lighting engine.
 *
 * <p>Lighting never blocks simulation: light computation runs as independent
 * jobs. During generation, lighting is a migratable job guarded by the chunk
 * state machine; relighting of loaded chunks runs as a region-bound message so
 * it never races the owning worker.
 */
public interface LightingEngine {

    /** Requests relight of a chunk (queues a lighting job). Idempotent per chunk. */
    void relight(ChunkPos pos);

    /** Whether the chunk has a pending or in-flight lighting job. */
    boolean isPending(ChunkPos pos);

    /** Whether the chunk has been lit (READY or later with light data). */
    boolean isLit(ChunkPos pos);

    /** Number of queued lighting jobs. */
    int pendingCount();
}
