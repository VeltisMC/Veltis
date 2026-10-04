package org.veltismc.world.api;

/**
 * Context handed to a {@link ChunkGenerator} for one stage of one chunk.
 *
 * <p>Writes are applied directly to the generating chunk: while a chunk is in a
 * pre-READY state, it is exclusively owned by its current stage job, so direct
 * writes are safe. Reads of neighboring chunks are non-blocking and may observe
 * slightly stale values (documented trade-off).
 */
public interface GenerationContext {

    ChunkPos pos();

    GenerationStage stage();

    /** Deterministic chunk seed (world seed mixed with chunk coordinates). */
    long seed();

    BlockState getBlock(BlockPos pos);

    void setBlock(BlockPos pos, BlockState state);

    /** Deterministic random value in {@code [0, bound)} derived from the chunk seed. */
    long random(long bound);
}
