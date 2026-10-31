package org.veltismc.world.io;

import org.veltismc.world.api.ChunkDataCodec;
import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.Compression;
import org.veltismc.world.chunk.ChunkSection;

import java.io.EOFException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Default {@link ChunkDataCodec}: a simple big-endian binary format without
 * compression. Swapping in another codec enables future compression schemes.
 *
 * <p>Format: {@code "VCMB" | version | sectionCount | timestampMillis | then per
 * section: blockIds (4096 x int32 BE), blockLight (4096 x u8), skyLight (4096 x
 * u8), opaque (512 x u8, one bit per block)}. Multi-byte fields are big-endian
 * (the {@link ByteBuffer} default), matching the historical
 * {@code DataOutputStream}/{@code DataInputStream} encoding; that order is part
 * of the on-disk format and must not change without a {@code VERSION} bump.
 *
 * <p>Encoding writes straight into one exactly-sized {@link ByteBuffer}: no
 * intermediate stream, no growing buffer and no {@code toByteArray()} copy.
 * Decoding wraps the caller's array and bulk-copies each section into the
 * snapshot's arrays. Each call owns its buffer, so concurrent encodes and
 * decodes share no state.
 */
public final class ChunkDataCodecImpl implements ChunkDataCodec {

    private static final byte[] MAGIC = {'V', 'C', 'M', 'B'};
    private static final byte VERSION = 2;
    private static final int MAX_SECTIONS = 64;
    private static final int HEADER_BYTES = MAGIC.length + Byte.BYTES + Integer.BYTES + Long.BYTES;

    @Override
    public Compression compression() {
        return Compression.NONE;
    }

    @Override
    public byte[] encode(ChunkSnapshot snapshot) throws IOException {
        int[][] sections = snapshot.sections();
        byte[][] blockLight = snapshot.blockLight();
        byte[][] skyLight = snapshot.skyLight();
        byte[][] opaque = snapshot.opaque();
        int sectionCount = sections.length;

        long encodedSize = HEADER_BYTES;
        for (int i = 0; i < sectionCount; i++) {
            encodedSize += (long) sections[i].length * Integer.BYTES
                + blockLight[i].length + skyLight[i].length + opaque[i].length;
        }
        if (encodedSize > Integer.MAX_VALUE) {
            throw new IOException("chunk data too large: " + encodedSize + " bytes");
        }

        ByteBuffer out = ByteBuffer.allocate((int) encodedSize).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC);
        out.put(VERSION);
        out.putInt(sectionCount);
        out.putLong(snapshot.timestampMillis());

        // One int view over the whole buffer instead of one per section. It is
        // only usable while the current byte offset is 4-byte aligned relative
        // to the view start; canonical snapshots always are, odd legacy arrays
        // fall back to putInt. Array lengths are written as-is, exactly like the
        // stream implementation did, so even non-canonical snapshots stay
        // byte-identical.
        IntBuffer ints = out.asIntBuffer();
        for (int i = 0; i < sectionCount; i++) {
            int[] blocks = sections[i];
            int relative = out.position() - HEADER_BYTES;
            if ((relative & 3) == 0) {
                ints.position(relative >>> 2);
                ints.put(blocks);
                out.position(HEADER_BYTES + ints.position() * Integer.BYTES);
            } else {
                for (int block : blocks) {
                    out.putInt(block);
                }
            }
            out.put(blockLight[i]);
            out.put(skyLight[i]);
            out.put(opaque[i]);
        }
        return out.array();
    }

    @Override
    public ChunkSnapshot decode(byte[] data) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (in.remaining() < MAGIC.length) {
            throw new EOFException();
        }
        for (byte expected : MAGIC) {
            if (in.get() != expected) {
                throw new IOException("bad chunk data magic");
            }
        }
        if (!in.hasRemaining()) {
            throw new EOFException();
        }
        if ((in.get() & 0xFF) != VERSION) {
            throw new IOException("unsupported chunk data version");
        }
        if (in.remaining() < Integer.BYTES) {
            throw new EOFException();
        }
        int sectionCount = in.getInt();
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS) {
            throw new IOException("implausible section count " + sectionCount);
        }
        if (in.remaining() < Long.BYTES) {
            throw new EOFException();
        }
        long timestamp = in.getLong();

        int[][] sections = new int[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] opaque = new byte[sectionCount][];

        // Same single-view trick as encode. Decoded section strides are fixed by
        // the format (4 * 4096 + 4096 + 4096 + 512 bytes), so every block array
        // starts at the same 4-byte phase after the 17-byte header.
        IntBuffer ints = in.asIntBuffer();
        try {
            for (int i = 0; i < sectionCount; i++) {
                int[] blocks = new int[ChunkSection.BLOCK_COUNT];
                ints.position((in.position() - HEADER_BYTES) >>> 2);
                ints.get(blocks);
                in.position(HEADER_BYTES + ints.position() * Integer.BYTES);
                byte[] bl = new byte[ChunkSection.BLOCK_COUNT];
                byte[] sl = new byte[ChunkSection.BLOCK_COUNT];
                byte[] op = new byte[ChunkSection.BLOCK_COUNT / 8];
                in.get(bl);
                in.get(sl);
                in.get(op);
                sections[i] = blocks;
                blockLight[i] = bl;
                skyLight[i] = sl;
                opaque[i] = op;
            }
        } catch (BufferUnderflowException e) {
            throw new EOFException();
        }
        return new ChunkSnapshot(null, sections, blockLight, skyLight, opaque, timestamp);
    }
}
