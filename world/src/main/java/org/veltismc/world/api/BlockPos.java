package org.veltismc.world.api;

/**
 * An immutable three-dimensional block position.
 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    /** Packs this position into a single {@code long} for map keys. */
    public long key() {
        return ((long) x << 44) ^ ((long) y << 20) ^ (z & 0xFFFFF);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
