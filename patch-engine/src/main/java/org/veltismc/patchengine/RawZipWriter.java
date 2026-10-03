package org.veltismc.patchengine;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes the runtime artifact by copying the compressed bytes of every entry
 * the patch set leaves alone, and deflating only the entries it changes.
 *
 * <p>Applying the bytecode patch set used to mean reading all ~17600 entries
 * out of the verified Minecraft classes jar, deflating them, and writing them
 * again — nineteen changed classes paid for the recompression of the whole
 * jar. This writer does the opposite: an entry the index does not name is
 * copied from the baseline exactly as it stands (local header, compressed
 * bytes, data descriptor), which is a buffered copy instead of a compression,
 * and only the entries that change go through a {@link Deflater}. The output
 * is the same jar contents at roughly the speed of writing the file once.
 *
 * <p>What "copied as it stands" is allowed to mean is checked, not assumed.
 * {@link #readIndex(byte[], String)} parses the baseline's central directory
 * and every non-directory entry's local header before anything is written, and
 * refuses — naming what was wrong — everything the raw copy cannot represent
 * faithfully: ZIP64 or multi-disk layouts, unknown or encrypted general
 * purpose flag bits, an entry whose local header does not sit where the
 * central directory says it does or does not spell the name the central
 * directory spells, and a data descriptor that disagrees with the sizes the
 * central directory records. The baseline is SHA-1 pinned to Mojang's jar,
 * which is a plain single-disk zip of deflate entries with descriptors that
 * match, so a refusal here means a bug or a tampered file, never a legitimate
 * variation of a real server jar.
 *
 * <p>Two runs over one baseline and one patch set produce one file: carried
 * entries are slices of the same baseline bytes, entries written here carry
 * the fixed zip-epoch timestamp (1980-01-01) and a fixed deflate level, and
 * the caller decides the entry order. The applier then verifies the finished
 * file entry by entry against the baseline before publishing it, so a copy
 * that went wrong is caught while it is still a staging file.
 */
final class RawZipWriter implements Closeable {

    /** Central directory file header signature. */
    private static final int CEN = 0x02014b50;
    /** Local file header signature. */
    private static final int LOC = 0x04034b50;
    /** Optional data descriptor signature. */
    private static final int EXT = 0x08074b50;
    /** End of central directory signature. */
    private static final int END = 0x06054b50;
    /** ZIP64 end of central directory locator signature. */
    private static final int END64_LOC = 0x07064b50;

    /** The flag bits this writer understands: compression options, descriptor, UTF-8 names. */
    private static final int KNOWN_FLAGS = 0x0002 | 0x0004 | 0x0008 | 0x0800;
    /** The flag bits that mean an entry's bytes are not plain deflate output. */
    private static final int ENCRYPTED_FLAGS = 0x0001 | 0x0040;
    /** Bit 3: the local header's size fields are zero and a descriptor follows the data. */
    private static final int DESCRIPTOR_FLAG = 0x0008;
    /** Bit 11: the entry name is UTF-8, which every name this writer emits is. */
    private static final int UTF8_FLAG = 0x0800;
    /** 1980-01-01, the zip epoch. Entries written here carry it, so runs cannot differ. */
    private static final int DOS_EPOCH = 0x0021;

    /**
     * One central directory record plus where the entry's local bytes are in
     * the file it was parsed from.
     *
     * <p>{@code method} is recorded as the baseline has it and is not
     * restricted to stored or deflate: carrying copies whatever bytes are
     * there, and the baseline being SHA-1 pinned means the JVM that accepts
     * the baseline accepts the copy. Directory entries keep only their name —
     * their local headers are not parsed, because directories are never
     * copied into the artifact.
     *
     * @param name              the name as the central directory spells it
     * @param method            compression method from the central directory
     * @param crc               CRC-32 of the entry's uncompressed bytes
     * @param compressedSize    compressed size from the central directory
     * @param uncompressedSize  uncompressed size from the central directory
     * @param localOffset       where the local file header starts
     * @param dataOffset        where the entry's compressed bytes start
     * @param regionEnd         just past the last byte of the entry: header,
     *                          data, and data descriptor if there is one
     * @param cdOffset          where this entry's central directory record starts
     * @param cdLength          the record's full length, name and extras included
     * @param directory         whether the name ends in a slash
     */
    record Entry(String name, int method, long crc, long compressedSize, long uncompressedSize,
                 long localOffset, long dataOffset, long regionEnd, int cdOffset, int cdLength,
                 boolean directory) {
    }

    /**
     * Parses a zip's central directory — and each non-directory entry's local
     * header — into the map the writer copies from and the verifier compares
     * against.
     *
     * <p>All structure checks are made here, before anything is written, so a
     * baseline that is not a plain zip fails closed with the reason rather
     * than producing a half-copied file. See the class comment for the full
     * list of what is refused.
     *
     * @param zip     the entire file
     * @param subject what this file is, spelled out in any error message
     * @return the entries by name, in central directory order
     * @throws PatchEngineException when the file is not a zip this writer can
     *                              copy entry for entry
     */
    static Map<String, Entry> readIndex(byte[] zip, String subject) {
        if (zip.length < 22) {
            throw broken(subject, "the file is " + zip.length + " bytes, smaller than an"
                + " empty zip");
        }
        // The end record is the last one whose declared comment ends exactly
        // at end of file; anything else is a signature inside a comment.
        int end = -1;
        int earliest = Math.max(0, zip.length - 22 - 0xFFFF);
        for (int i = zip.length - 22; i >= earliest; i--) {
            if (le32(zip, i) == END && i + 22 + u16(zip, i + 20) == zip.length) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw broken(subject, "no end-of-central-directory record ends the file");
        }
        if (end >= 20 && le32(zip, end - 20) == END64_LOC) {
            throw broken(subject, "a ZIP64 end-of-central-directory locator is present");
        }
        if (u16(zip, end + 4) != 0 || u16(zip, end + 6) != 0
                || u16(zip, end + 8) != u16(zip, end + 10)) {
            throw broken(subject, "the zip's disk fields say it is split across disks");
        }
        int count = u16(zip, end + 10);
        long cdSize = u32(zip, end + 12);
        long cdStart = u32(zip, end + 16);
        if (count == 0xFFFF) {
            throw broken(subject, "the entry count is the ZIP64 marker 0xFFFF");
        }
        if (cdSize == 0xFFFFFFFFL || cdStart == 0xFFFFFFFFL) {
            throw broken(subject, "the central directory offset or size is the ZIP64"
                + " marker 0xFFFFFFFF");
        }
        if (cdStart + cdSize > zip.length) {
            throw broken(subject, "the central directory claims to end at " + (cdStart + cdSize)
                + " but the file is " + zip.length + " bytes");
        }
        long cdEnd = cdStart + cdSize;

        var index = new LinkedHashMap<String, Entry>();
        long pos = cdStart;
        for (int i = 0; i < count; i++) {
            if (pos + 46 > cdEnd) {
                throw broken(subject, "central directory record " + i + " is truncated");
            }
            if (le32(zip, (int) pos) != CEN) {
                throw broken(subject, "record " + i + " at offset " + pos
                    + " is not a central directory record");
            }
            int flags = u16(zip, (int) pos + 8);
            int method = u16(zip, (int) pos + 10);
            long crc = u32(zip, (int) pos + 16);
            long compressedSize = u32(zip, (int) pos + 20);
            long uncompressedSize = u32(zip, (int) pos + 24);
            int nameLength = u16(zip, (int) pos + 28);
            int extraLength = u16(zip, (int) pos + 30);
            int commentLength = u16(zip, (int) pos + 32);
            long localOffset = u32(zip, (int) pos + 42);
            long recordEnd = pos + 46 + nameLength + extraLength + commentLength;
            if (recordEnd > cdEnd) {
                throw broken(subject, "central directory record " + i
                    + " runs past the end of the central directory");
            }
            int nameAt = (int) pos + 46;
            String name = new String(zip, nameAt, nameLength, StandardCharsets.UTF_8);
            byte[] reencoded = name.getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(zip, nameAt, nameAt + nameLength, reencoded, 0,
                    reencoded.length)) {
                throw broken(subject, "the name of entry " + i
                    + " is not UTF-8; its bytes do not decode and re-encode as themselves");
            }
            if (compressedSize == 0xFFFFFFFFL || uncompressedSize == 0xFFFFFFFFL
                    || localOffset == 0xFFFFFFFFL) {
                throw broken(subject, "entry " + name + " uses ZIP64 sizes or offsets");
            }
            if ((flags & ENCRYPTED_FLAGS) != 0) {
                throw broken(subject, "entry " + name + " is encrypted");
            }
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw broken(subject, "entry " + name + " sets general purpose flag bits"
                    + " this writer does not understand: 0x"
                    + Integer.toHexString(flags & ~KNOWN_FLAGS));
            }
            if (index.containsKey(name)) {
                throw broken(subject, "entry " + name + " appears twice");
            }

            long dataOffset = 0;
            long regionEnd = 0;
            if (!name.endsWith("/")) {
                if (localOffset + 30 > cdEnd) {
                    throw broken(subject, "entry " + name
                        + "'s local file header would start inside the central directory");
                }
                int local = (int) localOffset;
                if (le32(zip, local) != LOC) {
                    throw broken(subject, "entry " + name
                        + " has no local file header where the central directory says it is");
                }
                int localFlags = u16(zip, local + 6);
                if ((localFlags & ENCRYPTED_FLAGS) != 0) {
                    throw broken(subject, "entry " + name
                        + " is encrypted in its local file header");
                }
                int localNameLength = u16(zip, local + 26);
                int localExtraLength = u16(zip, local + 28);
                dataOffset = localOffset + 30 + localNameLength + localExtraLength;
                if (dataOffset > cdEnd) {
                    throw broken(subject, "entry " + name
                        + "'s data would start after the central directory");
                }
                if (localNameLength != nameLength || !Arrays.equals(zip, local + 30,
                        local + 30 + localNameLength, zip, nameAt, nameAt + nameLength)) {
                    throw broken(subject, "entry " + name
                        + "'s local header spells the name differently than the central"
                        + " directory");
                }
                long dataEnd = dataOffset + compressedSize;
                if (dataEnd > cdEnd) {
                    throw broken(subject, "entry " + name
                        + "'s data would run into the central directory");
                }
                regionEnd = dataEnd;
                if ((localFlags & DESCRIPTOR_FLAG) != 0) {
                    if (dataEnd + 12 > cdEnd) {
                        throw broken(subject, "entry " + name
                            + "'s data descriptor is truncated");
                    }
                    int at = (int) dataEnd;
                    // Both spellings are in the wild: with the 0x08074b50
                    // signature and without. Either matching the central
                    // directory is proof of which one this writer wrote;
                    // neither matching means the descriptor is wrong.
                    boolean signed = dataEnd + 16 <= cdEnd && le32(zip, at) == EXT
                        && u32(zip, at + 4) == crc && u32(zip, at + 8) == compressedSize
                        && u32(zip, at + 12) == uncompressedSize;
                    if (signed) {
                        regionEnd = dataEnd + 16;
                    } else if (u32(zip, at) == crc && u32(zip, at + 4) == compressedSize
                            && u32(zip, at + 8) == uncompressedSize) {
                        regionEnd = dataEnd + 12;
                    } else {
                        throw broken(subject, "entry " + name
                            + "'s data descriptor disagrees with its central directory record");
                    }
                }
            }
            index.put(name, new Entry(name, method, crc, compressedSize, uncompressedSize,
                localOffset, dataOffset, regionEnd, (int) pos, (int) (recordEnd - pos),
                name.endsWith("/")));
            pos = recordEnd;
        }
        if (pos != cdEnd) {
            throw broken(subject, "the central directory holds " + (pos - cdStart)
                + " bytes of records but declares " + cdSize);
        }
        return index;
    }

    private static PatchEngineException broken(String subject, String reason) {
        return new PatchEngineException("[Veltis] " + subject
            + " is not a ZIP the bytecode applier can copy entry for entry"
            + "\n  Reason: " + reason);
    }

    private static int u16(byte[] zip, int at) {
        return (zip[at] & 0xFF) | ((zip[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] zip, int at) {
        return (zip[at] & 0xFFL) | ((zip[at + 1] & 0xFFL) << 8) | ((zip[at + 2] & 0xFFL) << 16)
            | ((zip[at + 3] & 0xFFL) << 24);
    }

    private static int le32(byte[] zip, int at) {
        return (int) u32(zip, at);
    }

    private static void putU16(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >>> 8);
    }

    private static void putU32(byte[] target, int at, long value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >>> 8);
        target[at + 2] = (byte) (value >>> 16);
        target[at + 3] = (byte) (value >>> 24);
    }

    private final byte[] baseline;
    private final Map<String, Entry> index;
    private final OutputStream out;
    private final ByteArrayOutputStream central = new ByteArrayOutputStream(1 << 16);
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    private final byte[] window = new byte[1 << 15];
    private final CRC32 crc = new CRC32();
    private final byte[] offsetPatch = new byte[4];
    private final HashSet<String> written = new HashSet<>();
    private long offset;
    private int records;
    private long copyNanos;
    private long writeNanos;
    private boolean finished;
    private boolean closed;

    /**
     * Opens {@code staging} for writing. The baseline's parsed index is the
     * source for {@link #carry(String)}; the baseline bytes themselves are
     * only ever read from.
     *
     * @param staging  the file to write, whose parent directory exists
     * @param baseline the whole baseline jar
     * @param index    the baseline as {@link #readIndex(byte[], String)} read it
     * @throws IOException when the staging file cannot be opened
     */
    RawZipWriter(Path staging, byte[] baseline, Map<String, Entry> index) throws IOException {
        this.baseline = baseline;
        this.index = index;
        this.out = new BufferedOutputStream(Files.newOutputStream(staging), 1 << 16);
    }

    /** Nanoseconds {@link #carry(String)} spent writing copied bytes. */
    long copyNanos() {
        return copyNanos;
    }

    /** Nanoseconds {@link #put(String, byte[])} and {@link #finish()} spent writing structure. */
    long writeNanos() {
        return writeNanos;
    }

    /**
     * Writes {@code payload} as a fresh deflate entry: local header with the
     * sizes filled in, no data descriptor, and the matching central directory
     * record. The entry is compressed once, here; the caller has already
     * verified the payload against the index.
     *
     * @param name     the entry name
     * @param payload  the entry's uncompressed bytes
     * @throws IOException when the staging file cannot be written
     * @throws PatchEngineException when the name was already written
     */
    void put(String name, byte[] payload) throws IOException {
        long started = System.nanoTime();
        if (name == null || name.isEmpty()) {
            throw new PatchEngineException("[Veltis] The bytecode applier cannot write an entry"
                + " with no name"
                + "\n  Reason: an internal caller passed an empty name; no file was published");
        }
        if (!written.add(name)) {
            throw new PatchEngineException("[Veltis] The bytecode applier would write the entry "
                + name + " twice"
                + "\n  Reason: two writes of one name produce a jar with duplicate entries,"
                + " which no reader can order"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        crc.reset();
        crc.update(payload);
        long checksum = crc.getValue();
        deflater.reset();
        deflater.setInput(payload);
        deflater.finish();
        var buffer = new ByteArrayOutputStream(Math.max(64, payload.length / 3 + 16));
        while (!deflater.finished()) {
            int produced = deflater.deflate(window);
            if (produced == 0) {
                break;
            }
            buffer.write(window, 0, produced);
        }
        byte[] compressed = buffer.toByteArray();
        long localLength = 30 + nameBytes.length;
        if (offset + localLength + compressed.length > 0xFFFFFFFEL) {
            throw new PatchEngineException("[Veltis] The runtime artifact would exceed 4 GB"
                + "\n  Reason: that needs ZIP64, which the bytecode applier does not implement"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }

        byte[] header = new byte[(int) localLength];
        putU32(header, 0, LOC);
        putU16(header, 4, 20);
        putU16(header, 6, UTF8_FLAG);
        putU16(header, 8, 8);
        putU16(header, 10, 0);
        putU16(header, 12, DOS_EPOCH);
        putU32(header, 14, checksum);
        putU32(header, 18, compressed.length);
        putU32(header, 22, payload.length);
        putU16(header, 26, nameBytes.length);
        putU16(header, 28, 0);
        System.arraycopy(nameBytes, 0, header, 30, nameBytes.length);
        long localStart = offset;
        out.write(header);
        out.write(compressed);
        offset += localLength + compressed.length;

        byte[] record = new byte[46 + nameBytes.length];
        putU32(record, 0, CEN);
        putU16(record, 4, 20);
        putU16(record, 6, 20);
        putU16(record, 8, UTF8_FLAG);
        putU16(record, 10, 8);
        putU16(record, 12, 0);
        putU16(record, 14, DOS_EPOCH);
        putU32(record, 16, checksum);
        putU32(record, 20, compressed.length);
        putU32(record, 24, payload.length);
        putU16(record, 28, nameBytes.length);
        putU16(record, 30, 0);
        putU16(record, 32, 0);
        putU16(record, 34, 0);
        putU16(record, 36, 0);
        putU32(record, 38, 0);
        putU32(record, 42, localStart);
        System.arraycopy(nameBytes, 0, record, 46, nameBytes.length);
        central.write(record);
        records++;
        writeNanos += System.nanoTime() - started;
    }

    /**
     * Copies the entry as it stands in the baseline: local header, compressed
     * bytes, data descriptor when there is one, and then the baseline's own
     * central directory record with only the local header offset rewritten to
     * point at where the copy now sits. The bytes are never recompressed and
     * never interpreted.
     *
     * @param name an entry {@link #readIndex(byte[], String)} returned
     * @throws IOException when the staging file cannot be written
     * @throws PatchEngineException when the baseline does not hold the entry,
     *                               it is a directory, or it was already written
     */
    void carry(String name) throws IOException {
        long started = System.nanoTime();
        var entry = index.get(name);
        if (entry == null) {
            throw new PatchEngineException("[Veltis] The bytecode applier cannot copy " + name
                + "\n  Reason: the baseline's central directory does not list it under that"
                + " name, so two readings of the same file disagree"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        if (entry.directory()) {
            throw new PatchEngineException("[Veltis] The bytecode applier cannot copy the"
                + " directory entry " + name
                + "\n  Reason: directories are not carried into the artifact; the caller"
                + " should not have asked for one"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        if (!written.add(name)) {
            throw new PatchEngineException("[Veltis] The bytecode applier would write the entry "
                + name + " twice"
                + "\n  Reason: two writes of one name produce a jar with duplicate entries,"
                + " which no reader can order"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        long regionLength = entry.regionEnd() - entry.localOffset();
        if (regionLength > Integer.MAX_VALUE || offset + regionLength > 0xFFFFFFFEL) {
            throw new PatchEngineException("[Veltis] The runtime artifact would exceed 4 GB"
                + "\n  Reason: that needs ZIP64, which the bytecode applier does not implement"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }

        long localStart = offset;
        out.write(baseline, (int) entry.localOffset(), (int) regionLength);
        offset += regionLength;

        // The baseline's own central directory record, byte for byte, except
        // for the local header offset at byte 42 — the copy starts somewhere
        // else in this file. Flags, sizes, CRC and extras stay as they were,
        // which is what makes the copied local header and its data descriptor
        // consistent with the record that describes them.
        central.write(baseline, entry.cdOffset(), 42);
        putU32(offsetPatch, 0, localStart);
        central.write(offsetPatch, 0, 4);
        central.write(baseline, entry.cdOffset() + 46, entry.cdLength() - 46);
        records++;
        copyNanos += System.nanoTime() - started;
    }

    /**
     * Writes the central directory and the end-of-central-directory record.
     * Everything the file needs is in place after this; the caller still owns
     * closing the writer, which flushes the buffer to disk.
     *
     * @throws IOException when the staging file cannot be written
     * @throws PatchEngineException when the jar cannot be expressed as a zip
     *                               without ZIP64
     */
    void finish() throws IOException {
        if (finished) {
            throw new PatchEngineException("[Veltis] The bytecode applier would finish the same"
                + " artifact twice"
                + "\n  Reason: an internal caller repeated finish(); no file was published");
        }
        long started = System.nanoTime();
        if (records > 0xFFFE) {
            throw new PatchEngineException("[Veltis] The runtime artifact would hold " + records
                + " entries"
                + "\n  Reason: that needs ZIP64, which the bytecode applier does not implement"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        long centralStart = offset;
        byte[] centralBytes = central.toByteArray();
        if (centralStart > 0xFFFFFFFEL || centralBytes.length > 0xFFFFFFFEL) {
            throw new PatchEngineException("[Veltis] The runtime artifact would exceed 4 GB"
                + "\n  Reason: that needs ZIP64, which the bytecode applier does not implement"
                + "\n  Nothing has been published; the artifact is unchanged.");
        }
        out.write(centralBytes);
        offset += centralBytes.length;

        byte[] end = new byte[22];
        putU32(end, 0, END);
        putU16(end, 4, 0);
        putU16(end, 6, 0);
        putU16(end, 8, records);
        putU16(end, 10, records);
        putU32(end, 12, centralBytes.length);
        putU32(end, 16, centralStart);
        putU16(end, 20, 0);
        out.write(end);
        offset += end.length;
        finished = true;
        writeNanos += System.nanoTime() - started;
    }

    /**
     * Closes the staging file, flushing whatever is buffered. Safe to call
     * twice; the deflater's native memory is released once.
     *
     * @throws IOException when the file cannot be flushed or closed
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        deflater.end();
        out.close();
    }
}
