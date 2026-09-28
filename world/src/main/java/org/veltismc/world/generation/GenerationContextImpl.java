package org.veltismc.world.generation;

import org.veltismc.world.api.BlockDelta;
import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.GenerationContext;
import org.veltismc.world.api.GenerationStage;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.util.SeedHash;

/**
 * Generation context for one stage of one chunk. Writes to the generating chunk
 * are direct (it holds an exclusive pre-READY state); writes to neighboring
 * chunks are routed as deltas and applied if those chunks are loaded.
 */
public final class GenerationContextImpl implements GenerationContext {

    private final WorldImpl world;
    private final Chunk chunk;
    private final GenerationStage stage;
    private final long seed;
    private int step;

    public GenerationContextImpl(WorldImpl world, Chunk chunk, GenerationStage stage, long seed) {
        this.world = world;
        this.chunk = chunk;
        this.stage = stage;
        this.seed = seed;
    }

    @Override
    public ChunkPos pos() {
        return chunk.pos();
    }

    @Override
    public GenerationStage stage() {
        return stage;
    }

    @Override
    public long seed() {
        return seed;
    }

    @Override
    public BlockState getBlock(BlockPos p) {
        Chunk target = world.chunkIfPresent(ChunkPos.containing(p));
        if (target == null) {
            return BlockState.AIR;
        }
        return target.getBlock(p);
    }

    @Override
    public void setBlock(BlockPos p, BlockState state) {
        Chunk target = chunk.pos().equals(ChunkPos.containing(p)) ? chunk : world.chunkIfPresent(ChunkPos.containing(p));
        if (target == chunk) {
            chunk.setBlockUnsafe(p, state);
            return;
        }
        if (target != null && target.isLoaded()) {
            world.routeDelta(new BlockDelta(target.pos(), p, state));
        }
    }

    @Override
    public long random(long bound) {
        if (bound <= 0) {
            return 0;
        }
        long r = SeedHash.hash(seed, step++, 0x6D2B79F5L);
        return Math.floorMod(r, bound);
    }
}
