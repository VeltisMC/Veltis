package org.veltismc.world.io;

import org.veltismc.world.api.ChunkDataCodec;
import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.Compression;
import org.veltismc.world.chunk.ChunkSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Default {@link ChunkDataCodec}: a simple little-endian binary format without
 * compression. Swapping in another codec enables future compression schemes.
 *
 * <p>Format: {@code "VCMB" | version | sectionCount | timestampMillis | then per
 * section: blockIds (4096 x int32 LE), blockLight (4096 x u8), skyLight (4096 x
 * u8), opaque (512 x u8, one bit per block)}.
 */
public final class ChunkDataCodecImpl implements ChunkDataCodec {

    private static final byte[] MAGIC = {'V', 'C', 'M', 'B'};
    private static final byte VERSION = 2;

    @Override
    public Compression compression() {
        return Compression.NONE;
    }

    @Override
    public byte[] encode(ChunkSnapshot snapshot) throws IOException {
        int sectionCount = snapshot.sections().length;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + sectionCount * (16 * 1024 + 8192 + 512))) {
            DataOutputStream out = new DataOutputStream(bos);
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(sectionCount);
            out.writeLong(snapshot.timestampMillis());
            for (int i = 0; i < sectionCount; i++) {
                int[] blocks = snapshot.sections()[i];
                for (int b : blocks) {
                    out.writeInt(b);
                }
                out.write(snapshot.blockLight()[i]);
                out.write(snapshot.skyLight()[i]);
                out.write(snapshot.opaque()[i]);
            }
            out.flush();
            return bos.toByteArray();
        }
    }

    @Override
    public ChunkSnapshot decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                throw new IOException("bad chunk data magic");
            }
            if (in.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported chunk data version");
            }
            int sectionCount = in.readInt();
            if (sectionCount < 0 || sectionCount > 64) {
                throw new IOException("implausible section count " + sectionCount);
            }
            long timestamp = in.readLong();
            int[][] blocks = new int[sectionCount][];
            byte[][] blockLight = new byte[sectionCount][];
            byte[][] skyLight = new byte[sectionCount][];
            byte[][] opaque = new byte[sectionCount][];
            for (int i = 0; i < sectionCount; i++) {
                int[] sectionBlocks = new int[ChunkSection.BLOCK_COUNT];
                for (int b = 0; b < sectionBlocks.length; b++) {
                    sectionBlocks[b] = in.readInt();
                }
                byte[] bl = new byte[ChunkSection.BLOCK_COUNT];
                byte[] sl = new byte[ChunkSection.BLOCK_COUNT];
                byte[] op = new byte[ChunkSection.BLOCK_COUNT / 8];
                in.readFully(bl);
                in.readFully(sl);
                in.readFully(op);
                blocks[i] = sectionBlocks;
                blockLight[i] = bl;
                skyLight[i] = sl;
                opaque[i] = op;
            }
            return new ChunkSnapshot(null, blocks, blockLight, skyLight, opaque, timestamp);
        }
    }
}
