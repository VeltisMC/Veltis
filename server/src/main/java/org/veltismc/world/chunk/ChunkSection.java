package org.veltismc.world.chunk;

import org.veltismc.world.api.BlockState;

/**
 * A 16x16x16 block section: block ids plus per-block emitted (block) light,
 * sky light, and an opacity bitset. Sections are pooled and reused.
 *
 * <p>Not thread-safe. Writes happen either on the region owner thread (delta
 * application in READY/SIMULATING states) or on a generation/lighting job thread
 * while the chunk holds an exclusive pre-READY state (CAS-guarded).
 */
public final class ChunkSection {

    public static final int SIZE = 16;
    public static final int BLOCK_COUNT = SIZE * SIZE * SIZE;

    private final int[] blocks = new int[BLOCK_COUNT];
    private final byte[] blockLight = new byte[BLOCK_COUNT];
    private final byte[] skyLight = new byte[BLOCK_COUNT];
    private final long[] opaque = new long[BLOCK_COUNT / 64];
    private int nonAir;

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static boolean opaqueBit(long[] bits, int idx) {
        return ((bits[idx >> 6] >>> (idx & 63)) & 1L) != 0;
    }

    private static void setOpaqueBit(long[] bits, int idx, boolean value) {
        if (value) {
            bits[idx >> 6] |= 1L << (idx & 63);
        } else {
            bits[idx >> 6] &= ~(1L << (idx & 63));
        }
    }

    public BlockState getBlock(int x, int y, int z) {
        int idx = index(x, y, z);
        return new BlockState(blocks[idx], blockLight[idx] & 0xFF, opaqueBit(opaque, idx));
    }

    public int getBlockId(int x, int y, int z) {
        return blocks[index(x, y, z)];
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        int idx = index(x, y, z);
        boolean wasAir = blocks[idx] == 0;
        boolean isAir = state.id() == 0;
        if (wasAir && !isAir) {
            nonAir++;
        } else if (!wasAir && isAir) {
            nonAir--;
        }
        blocks[idx] = state.id();
        blockLight[idx] = (byte) state.lightLevel();
        setOpaqueBit(opaque, idx, state.opaque());
    }

    public int getBlockLight(int x, int y, int z) {
        return blockLight[index(x, y, z)] & 0xFF;
    }

    public void setBlockLight(int x, int y, int z, int level) {
        blockLight[index(x, y, z)] = (byte) level;
    }

    public int getSkyLight(int x, int y, int z) {
        return skyLight[index(x, y, z)] & 0xFF;
    }

    public void setSkyLight(int x, int y, int z, int level) {
        skyLight[index(x, y, z)] = (byte) level;
    }

    public boolean isEmpty() {
        return nonAir == 0;
    }

    public int nonAirCount() {
        return nonAir;
    }

    /** Raw block id array (length 4096, y-major). */
    public int[] blocks() {
        return blocks;
    }

    /** Raw emitted-light array (length 4096, y-major). */
    public byte[] blockLight() {
        return blockLight;
    }

    /** Raw sky-light array (length 4096, y-major). */
    public byte[] skyLight() {
        return skyLight;
    }

    /**
     * Copies this section's data into the given snapshot arrays. Opacity is
     * packed into {@code outOpaque} (512 bytes: one bit per block).
     */
    public void copyTo(int[] outBlocks, byte[] outBlockLight, byte[] outSkyLight, byte[] outOpaque) {
        System.arraycopy(blocks, 0, outBlocks, 0, BLOCK_COUNT);
        System.arraycopy(blockLight, 0, outBlockLight, 0, BLOCK_COUNT);
        System.arraycopy(skyLight, 0, outSkyLight, 0, BLOCK_COUNT);
        for (int i = 0; i < BLOCK_COUNT; i++) {
            if (opaqueBit(opaque, i)) {
                outOpaque[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
    }

    /**
     * Overwrites this section from snapshot arrays (used when loading from disk).
     * Opacity is restored from the persisted bitset in {@code inOpaque}.
     */
    public void copyFrom(int[] inBlocks, byte[] inBlockLight, byte[] inSkyLight, byte[] inOpaque) {
        System.arraycopy(inBlocks, 0, blocks, 0, BLOCK_COUNT);
        System.arraycopy(inBlockLight, 0, blockLight, 0, BLOCK_COUNT);
        System.arraycopy(inSkyLight, 0, skyLight, 0, BLOCK_COUNT);
        for (int i = 0; i < BLOCK_COUNT; i++) {
            boolean isOpaque = (inOpaque[i >> 3] & (1 << (i & 7))) != 0;
            setOpaqueBit(opaque, i, isOpaque);
        }
        nonAir = 0;
        for (int i = 0; i < BLOCK_COUNT; i++) {
            if (blocks[i] != 0) {
                nonAir++;
            }
        }
    }

    /** Resets the section for reuse from a pool. */
    public void reset() {
        for (int i = 0; i < BLOCK_COUNT; i++) {
            blocks[i] = 0;
            blockLight[i] = 0;
            skyLight[i] = 0;
            opaque[i >> 6] = 0;
        }
        nonAir = 0;
    }
}
