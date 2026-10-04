package org.veltismc.world.api;

/** A light level change within a chunk (sky or block light). */
public record LightDelta(ChunkPos chunk, BlockPos pos, boolean sky, int level) implements ChunkDelta {

    public static LightDelta sky(BlockPos pos, int level) {
        return new LightDelta(ChunkPos.containing(pos), pos, true, level);
    }

    public static LightDelta block(BlockPos pos, int level) {
        return new LightDelta(ChunkPos.containing(pos), pos, false, level);
    }
}
