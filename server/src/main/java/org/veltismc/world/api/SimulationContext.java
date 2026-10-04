package org.veltismc.world.api;

/**
 * Simulation context handed to ticking entities and block simulators.
 *
 * <p>Reads are local to the owning region and never block. Writes become deltas:
 * in-region writes apply immediately on the owner thread; cross-region writes are
 * posted as messages and applied by the destination region's worker.
 */
public interface SimulationContext {

    Region region();

    /** Deterministic seed of the current region. */
    long seed();

    /** Reads a block; returns {@link BlockState#AIR} if the chunk is not loaded. */
    BlockState getBlock(BlockPos pos);

    /** Writes a block through the delta pipeline. */
    void setBlock(BlockPos pos, BlockState state);

    /** Deterministic per-tick random value in {@code [0, bound)}. */
    long nextRandom(long bound);
}
