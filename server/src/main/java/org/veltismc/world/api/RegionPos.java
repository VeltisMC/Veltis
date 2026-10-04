package org.veltismc.world.api;

/**
 * An immutable region position. Regions are square groups of chunks
 * ({@link WorldConfig#regionSizeChunks()} per side) and are the ownership boundary
 * of the engine: only one worker may mutate a region at a time.
 */
public record RegionPos(int x, int z) {

    public static RegionPos of(int x, int z) {
        return new RegionPos(x, z);
    }

    /** Returns the region containing the given chunk. */
    public static RegionPos containing(int regionSizeChunks, ChunkPos pos) {
        return new RegionPos(pos.x() >> shiftOf(regionSizeChunks), pos.z() >> shiftOf(regionSizeChunks));
    }

    /** Packs this position into a single {@code long} for map keys. */
    public long key() {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    static int shiftOf(int regionSizeChunks) {
        return Integer.numberOfTrailingZeros(regionSizeChunks);
    }

    @Override
    public String toString() {
        return "R(" + x + ", " + z + ")";
    }
}
