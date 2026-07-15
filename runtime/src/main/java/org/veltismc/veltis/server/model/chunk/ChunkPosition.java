package org.veltismc.veltis.server.model.chunk;

/**
 * Immutable chunk coordinate pair.
 *
 * <p>Represents the position of a chunk in the world's chunk grid.
 * Coordinates are block coordinates divided by 16 (shifted right 4).
 *
 * @param x the chunk X coordinate
 * @param z the chunk Z coordinate
 */
public record ChunkPosition(int x, int z) {

    /**
     * Returns the minimum block X for this chunk.
     */
    public int blockX() {
        return x << 4;
    }

    /**
     * Returns the minimum block Z for this chunk.
     */
    public int blockZ() {
        return z << 4;
    }

    /**
     * Returns the maximum block X (exclusive) for this chunk.
     */
    public int blockXMax() {
        return (x << 4) + 16;
    }

    /**
     * Returns the maximum block Z (exclusive) for this chunk.
     */
    public int blockZMax() {
        return (z << 4) + 16;
    }

    /**
     * Creates a chunk position from block coordinates.
     *
     * @param blockX global block X
     * @param blockZ global block Z
     * @return the chunk position containing those block coordinates
     */
    public static ChunkPosition fromBlock(int blockX, int blockZ) {
        return new ChunkPosition(blockX >> 4, blockZ >> 4);
    }

    /**
     * Returns the squared distance from this chunk to another.
     */
    public long distanceSquared(ChunkPosition other) {
        var dx = (long) this.x - other.x;
        var dz = (long) this.z - other.z;
        return dx * dx + dz * dz;
    }

    /**
     * Returns the region file X coordinate containing this chunk.
     */
    public int regionX() {
        return x >> 5;
    }

    /**
     * Returns the region file Z coordinate containing this chunk.
     */
    public int regionZ() {
        return z >> 5;
    }

    /**
     * Returns the local X coordinate within the region file (0-31).
     */
    public int regionLocalX() {
        return x & 31;
    }

    /**
     * Returns the local Z coordinate within the region file (0-31).
     */
    public int regionLocalZ() {
        return z & 31;
    }
}


