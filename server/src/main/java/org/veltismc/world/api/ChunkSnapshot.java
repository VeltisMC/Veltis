package org.veltismc.world.api;

/**
 * Immutable serialized form of a chunk used by the save pipeline.
 *
 * <p>Encoded buffers are drawn from a memory pool and returned after the write
 * completes, keeping GC pressure low under heavy churn.
 */
public record ChunkSnapshot(
    ChunkPos pos,
    int[][] sections,
    byte[][] blockLight,
    byte[][] skyLight,
    byte[][] opaque,
    long timestampMillis
) {
}
