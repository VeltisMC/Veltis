package org.veltismc.world.api;

/**
 * An immutable chunk position (chunk column, 16x16 blocks horizontally).
 */
public record ChunkPos(int x, int z) {

    public static ChunkPos of(int x, int z) {
        return new ChunkPos(x, z);
    }

    /** Returns the chunk containing the given block position. */
    public static ChunkPos containing(BlockPos pos) {
        return new ChunkPos(pos.x() >> 4, pos.z() >> 4);
    }

    public ChunkPos offset(int dx, int dz) {
        return new ChunkPos(x + dx, z + dz);
    }

    /** Packs this position into a single {@code long} for map keys. */
    public long key() {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ")";
    }
}
