package org.veltismc.world.api;

/** A block replacement at a specific position within a chunk. */
public record BlockDelta(ChunkPos chunk, BlockPos pos, BlockState state) implements ChunkDelta {

    public static BlockDelta of(BlockPos pos, BlockState state) {
        return new BlockDelta(ChunkPos.containing(pos), pos, state);
    }
}
