package org.veltismc.world.io;

import org.junit.jupiter.api.Test;
import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.Compression;
import org.veltismc.world.chunk.ChunkSection;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Differential and robustness tests for {@link ChunkDataCodecImpl}.
 *
 * <p>{@link LegacyChunkDataCodec} is the pre-optimization stream implementation;
 * every encoded payload is compared byte-for-byte against it, and both
 * implementations must be able to read each other's output.
 */
class ChunkDataCodecImplTest {

    private static final int SECTION_BYTES = ChunkSection.BLOCK_COUNT * Integer.BYTES
        + ChunkSection.BLOCK_COUNT
        + ChunkSection.BLOCK_COUNT
        + ChunkSection.BLOCK_COUNT / 8;
    private static final int HEADER_BYTES = 4 + 1 + 4 + 8;

    private final ChunkDataCodecImpl codec = new ChunkDataCodecImpl();
    private final LegacyChunkDataCodec legacy = new LegacyChunkDataCodec();

    @Test
    void compressionIsUnchanged() {
        assertEquals(Compression.NONE, codec.compression());
    }

    @Test
    void encodeMatchesLegacyForSupportedSectionCounts() throws Exception {
        for (int sectionCount : new int[]{0, 1, 2, 24, 64}) {
            ChunkSnapshot snapshot = snapshot(sectionCount, 0x5EED + sectionCount);
            byte[] expected = legacy.encode(snapshot);
            byte[] actual = codec.encode(snapshot);
            assertArrayEquals(expected, actual, "sectionCount=" + sectionCount);
            assertEquals(HEADER_BYTES + sectionCount * SECTION_BYTES, actual.length);
        }
    }

    @Test
    void encodeMatchesLegacyForNonCanonicalArrayLengths() throws Exception {
        // The old codec wrote whatever arrays the snapshot held, not a fixed
        // size. The replacement must stay byte-identical even for such input.
        int[][] sections = {new int[0], new int[1], new int[4095], new int[4097], new int[33]};
        byte[][] blockLight = {new byte[0], new byte[1], new byte[3], new byte[4097], new byte[7]};
        byte[][] skyLight = {new byte[2], new byte[0], new byte[4096], new byte[5], new byte[4099]};
        byte[][] opaque = {new byte[1], new byte[0], new byte[511], new byte[512], new byte[513]};
        Random random = new Random(7);
        for (int i = 0; i < sections.length; i++) {
            for (int j = 0; j < sections[i].length; j++) {
                sections[i][j] = random.nextInt();
            }
            random.nextBytes(blockLight[i]);
            random.nextBytes(skyLight[i]);
            random.nextBytes(opaque[i]);
        }
        ChunkSnapshot snapshot = new ChunkSnapshot(null, sections, blockLight, skyLight, opaque, 123456789L);

        assertArrayEquals(legacy.encode(snapshot), codec.encode(snapshot));
    }

    @Test
    void roundTripPreservesSnapshot() throws Exception {
        for (int sectionCount : new int[]{0, 1, 24, 64}) {
            ChunkSnapshot expected = snapshot(sectionCount, 0xC0DEC + sectionCount);
            ChunkSnapshot actual = codec.decode(codec.encode(expected));
            assertSnapshotEquals(expected, actual);
        }
    }

    @Test
    void emptySnapshotEncodesToHeaderOnly() throws Exception {
        long timestamp = 0x1122334455667788L;
        ChunkSnapshot empty = new ChunkSnapshot(null, new int[0][], new byte[0][], new byte[0][], new byte[0][], timestamp);

        byte[] data = codec.encode(empty);

        assertEquals(HEADER_BYTES, data.length);
        assertArrayEquals(new byte[]{
            'V', 'C', 'M', 'B', 2,
            0, 0, 0, 0,
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
        }, data);
        assertSnapshotEquals(empty, codec.decode(data));
        assertArrayEquals(legacy.encode(empty), data);
    }

    @Test
    void headerAndBlockIdsAreBigEndian() throws Exception {
        int[] blocks = new int[ChunkSection.BLOCK_COUNT];
        blocks[0] = 0x01020304;
        blocks[1] = -1;
        ChunkSnapshot snapshot = new ChunkSnapshot(
            null,
            new int[][]{blocks},
            new byte[][]{new byte[ChunkSection.BLOCK_COUNT]},
            new byte[][]{new byte[ChunkSection.BLOCK_COUNT]},
            new byte[][]{new byte[ChunkSection.BLOCK_COUNT / 8]},
            0x0102030405060708L);

        byte[] data = codec.encode(snapshot);

        assertArrayEquals(new byte[]{
            'V', 'C', 'M', 'B', 2,
            0, 0, 0, 1,
            1, 2, 3, 4, 5, 6, 7, 8,
            1, 2, 3, 4,
            -1, -1, -1, -1
        }, Arrays.copyOf(data, HEADER_BYTES + 8));
    }

    @Test
    void boundaryValuesSurviveRoundTrip() throws Exception {
        int[] blocks = new int[ChunkSection.BLOCK_COUNT];
        blocks[0] = Integer.MIN_VALUE;
        blocks[1] = Integer.MAX_VALUE;
        blocks[2] = -1;
        blocks[3] = 0;
        blocks[4] = 1;
        byte[] blockLight = boundaryBytes();
        byte[] skyLight = boundaryBytes();
        byte[] opaque = new byte[ChunkSection.BLOCK_COUNT / 8];
        opaque[0] = (byte) 0xFF;
        opaque[opaque.length - 1] = (byte) 0x80;
        ChunkSnapshot expected = new ChunkSnapshot(
            null,
            new int[][]{blocks},
            new byte[][]{blockLight},
            new byte[][]{skyLight},
            new byte[][]{opaque},
            Long.MIN_VALUE);

        byte[] encoded = codec.encode(expected);
        assertArrayEquals(legacy.encode(expected), encoded);
        assertSnapshotEquals(expected, codec.decode(encoded));
        assertSnapshotEquals(expected, legacy.decode(encoded));
    }

    @Test
    void existingPersistedDataIsReadableBothWays() throws Exception {
        ChunkSnapshot expected = snapshot(24, 0xDA7A);
        byte[] storedByLegacy = legacy.encode(expected);

        assertSnapshotEquals(expected, codec.decode(storedByLegacy));

        byte[] storedByNew = codec.encode(expected);
        assertSnapshotEquals(expected, legacy.decode(storedByNew));
    }

    @Test
    void maximumPayloadIsAcceptedAndMaximumSectionCountIsEnforced() throws Exception {
        ChunkSnapshot maximum = snapshot(64, 0x64);
        byte[] encoded = codec.encode(maximum);
        assertEquals(HEADER_BYTES + 64 * SECTION_BYTES, encoded.length);
        assertEquals(64, codec.decode(encoded).sections().length);

        byte[] tooMany = RawHeader.header(2, 65, 0L);
        IOException error = assertThrows(IOException.class, () -> codec.decode(tooMany));
        assertEquals("implausible section count 65", error.getMessage());
    }

    @Test
    void decodeRejectsNullAndShortData() {
        assertThrows(NullPointerException.class, () -> codec.decode(null));
        assertThrows(EOFException.class, () -> codec.decode(new byte[0]));
        assertThrows(EOFException.class, () -> codec.decode(new byte[1]));
        assertThrows(EOFException.class, () -> codec.decode(new byte[3]));
    }

    @Test
    void decodeRejectsBadMagic() {
        byte[] data = RawHeader.header(2, 0, 0L);
        data[0] = 'X';

        IOException error = assertThrows(IOException.class, () -> codec.decode(data));
        assertEquals("bad chunk data magic", error.getMessage());
    }

    @Test
    void decodeRejectsUnsupportedVersion() {
        for (int version : new int[]{0, 1, 3, 255}) {
            byte[] data = RawHeader.header(version, 0, 0L);
            IOException error = assertThrows(IOException.class, () -> codec.decode(data));
            assertEquals("unsupported chunk data version", error.getMessage(), "version=" + version);
        }
    }

    @Test
    void decodeRejectsImplausibleSectionCounts() {
        for (int sectionCount : new int[]{-1, -64, 65, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            byte[] data = RawHeader.header(2, sectionCount, 0L);
            IOException error = assertThrows(IOException.class, () -> codec.decode(data));
            assertEquals("implausible section count " + sectionCount, error.getMessage());
        }
    }

    @Test
    void decodeRejectsTruncatedHeader() throws Exception {
        byte[] valid = codec.encode(snapshot(0, 1));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> codec.decode(truncated), "length=" + length);
        }
    }

    @Test
    void decodeRejectsTruncatedSection() throws Exception {
        byte[] valid = codec.encode(snapshot(1, 2));
        int blocksEnd = HEADER_BYTES + ChunkSection.BLOCK_COUNT * Integer.BYTES;
        int blockLightEnd = blocksEnd + ChunkSection.BLOCK_COUNT;
        int skyLightEnd = blockLightEnd + ChunkSection.BLOCK_COUNT;
        int[] cuts = {
            17, 18, 20, 21,
            HEADER_BYTES + 1, HEADER_BYTES + 3, blocksEnd - 1, blocksEnd,
            blockLightEnd - 1, blockLightEnd,
            skyLightEnd - 1, skyLightEnd,
            valid.length - ChunkSection.BLOCK_COUNT / 8,
            valid.length - 1
        };
        for (int cut : cuts) {
            byte[] truncated = Arrays.copyOf(valid, cut);
            assertThrows(IOException.class, () -> codec.decode(truncated), "cut=" + cut);
        }
    }

    @Test
    void decodeIgnoresTrailingBytes() throws Exception {
        ChunkSnapshot expected = snapshot(2, 3);
        byte[] encoded = codec.encode(expected);
        byte[] padded = Arrays.copyOf(encoded, encoded.length + 37);
        padded[encoded.length] = 0x7F;

        assertSnapshotEquals(expected, codec.decode(padded));
    }

    @Test
    void repeatedEncodeIsStable() throws Exception {
        ChunkSnapshot snapshot = snapshot(24, 4);
        byte[] expected = legacy.encode(snapshot);
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(expected, codec.encode(snapshot), "iteration=" + i);
        }
    }

    @Test
    void concurrentEncodeDecodeCallsAreIndependent() throws Exception {
        int threads = 8;
        int iterations = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                int threadId = thread;
                futures.add(pool.submit(() -> {
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        ChunkSnapshot snapshot = snapshot(4, threadId * 10_000L + iteration);
                        byte[] expected = legacy.encode(snapshot);
                        byte[] actual = codec.encode(snapshot);
                        assertArrayEquals(expected, actual);
                        assertSnapshotEquals(snapshot, codec.decode(actual));
                        assertSnapshotEquals(snapshot, legacy.decode(actual));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static ChunkSnapshot snapshot(int sectionCount, long seed) {
        Random random = new Random(seed);
        int[][] sections = new int[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] opaque = new byte[sectionCount][];
        for (int i = 0; i < sectionCount; i++) {
            int[] blocks = new int[ChunkSection.BLOCK_COUNT];
            for (int j = 0; j < blocks.length; j++) {
                blocks[j] = random.nextInt();
            }
            blocks[0] = Integer.MIN_VALUE;
            blocks[1] = Integer.MAX_VALUE;
            blocks[2] = -1;
            blocks[3] = 0;
            blocks[4] = 1;
            byte[] bl = new byte[ChunkSection.BLOCK_COUNT];
            byte[] sl = new byte[ChunkSection.BLOCK_COUNT];
            byte[] op = new byte[ChunkSection.BLOCK_COUNT / 8];
            random.nextBytes(bl);
            random.nextBytes(sl);
            random.nextBytes(op);
            sections[i] = blocks;
            blockLight[i] = bl;
            skyLight[i] = sl;
            opaque[i] = op;
        }
        return new ChunkSnapshot(null, sections, blockLight, skyLight, opaque, random.nextLong());
    }

    private static byte[] boundaryBytes() {
        byte[] bytes = new byte[ChunkSection.BLOCK_COUNT];
        bytes[0] = Byte.MIN_VALUE;
        bytes[1] = Byte.MAX_VALUE;
        bytes[2] = (byte) 0xFF;
        bytes[3] = 0;
        bytes[4] = 1;
        bytes[bytes.length - 1] = (byte) 0x80;
        return bytes;
    }

    private static void assertSnapshotEquals(ChunkSnapshot expected, ChunkSnapshot actual) {
        assertNull(actual.pos());
        assertEquals(expected.timestampMillis(), actual.timestampMillis());
        assertEquals(expected.sections().length, actual.sections().length);
        for (int i = 0; i < expected.sections().length; i++) {
            assertArrayEquals(expected.sections()[i], actual.sections()[i], "blocks[" + i + "]");
            assertArrayEquals(expected.blockLight()[i], actual.blockLight()[i], "blockLight[" + i + "]");
            assertArrayEquals(expected.skyLight()[i], actual.skyLight()[i], "skyLight[" + i + "]");
            assertArrayEquals(expected.opaque()[i], actual.opaque()[i], "opaque[" + i + "]");
        }
    }

    /** Builds the fixed 17-byte header without going through the codec. */
    private static final class RawHeader {
        private RawHeader() {
        }

        static byte[] header(int version, int sectionCount, long timestamp) {
            return new byte[]{
                'V', 'C', 'M', 'B', (byte) version,
                (byte) (sectionCount >>> 24), (byte) (sectionCount >>> 16),
                (byte) (sectionCount >>> 8), (byte) sectionCount,
                (byte) (timestamp >>> 56), (byte) (timestamp >>> 48),
                (byte) (timestamp >>> 40), (byte) (timestamp >>> 32),
                (byte) (timestamp >>> 24), (byte) (timestamp >>> 16),
                (byte) (timestamp >>> 8), (byte) timestamp
            };
        }
    }
}
