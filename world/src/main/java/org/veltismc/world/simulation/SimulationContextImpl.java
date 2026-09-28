package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockDelta;
import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.SimulationContext;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.util.SeedHash;

/**
 * Simulation context for one region tick. Reads are local and never block;
 * writes within the region apply immediately on the owner thread, cross-region
 * writes are posted as deltas to the destination region's inbox.
 */
public final class SimulationContextImpl implements SimulationContext {

    private final RegionImpl region;
    private final long seed;
    private long step;

    public SimulationContextImpl(RegionImpl region, long seed) {
        this.region = region;
        this.seed = seed;
    }

    @Override
    public Region region() {
        return region;
    }

    @Override
    public long seed() {
        return seed;
    }

    @Override
    public BlockState getBlock(BlockPos pos) {
        WorldImpl world = region.world();
        Chunk chunk = world.chunkIfPresent(ChunkPos.containing(pos));
        return chunk != null && chunk.isLoaded() ? chunk.getBlock(pos) : BlockState.AIR;
    }

    @Override
    public void setBlock(BlockPos pos, BlockState state) {
        WorldImpl world = region.world();
        ChunkPos cp = ChunkPos.containing(pos);
        if (region.owns(cp)) {
            Chunk chunk = world.chunkIfPresent(cp);
            if (chunk != null && chunk.isLoaded() && chunk.setBlock(pos, state)) {
                world.lighting().relight(cp);
            }
            return;
        }
        world.routeDelta(new BlockDelta(cp, pos, state));
    }

    @Override
    public long nextRandom(long bound) {
        if (bound <= 0) {
            return 0;
        }
        long r = SeedHash.hash(seed, step++, bound);
        return Math.floorMod(r, bound);
    }
}
