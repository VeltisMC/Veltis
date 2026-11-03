package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raw copy that replaced the recompression of the whole jar.
 *
 * <p>Every test here is a way "copied unchanged" could be a claim instead of a
 * fact: bytes that round-trip through the writer differently than they stood,
 * a run that is not reproducible, a baseline structure the copier silently
 * misreads. The failure tests pin the fail-closed half — a baseline the writer
 * cannot copy faithfully must be refused before anything is written, and the
 * message must say which property was violated, because "corrupt jar" is not
 * actionable and the baseline is SHA-1 pinned anyway.
 */
class RawZipWriterTest {

    private static final String BASELINE = "baseline.jar";
    private static final byte[] UTF8_TEXT = "héllo, wörld — payload".getBytes(StandardCharsets.UTF_8);

    // ------------------------------------------------------------------
    // Fixture: one of everything a reader has to reason about
    // ------------------------------------------------------------------

    /**
     * A baseline the JDK itself wrote, which is the shape that matters: entries
     * with data descriptors (bit 3 and the descriptor signature), a stored
     * entry with its sizes inline and no descriptor, a directory entry, a
     * non-ASCII name, and a manifest.
     */
    private static byte[] baselineJar(Path tmp) throws IOException {
        var file = tmp.resolve(BASELINE);
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            plain(out, JarFile.MANIFEST_NAME, "Manifest-Version: 1.0\n".getBytes(
                StandardCharsets.UTF_8));
            plain(out, "demo/data.txt", "the data".getBytes(StandardCharsets.UTF_8));
            plain(out, "demo/utf8-éé.txt", UTF8_TEXT);

            var stored = "stored payload bytes".getBytes(StandardCharsets.UTF_8);
            var crc = new CRC32();
            crc.update(stored);
            var entry = new ZipEntry("demo/stored.bin");
            entry.setTime(0L);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(stored.length);
            entry.setCompressedSize(stored.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(stored);
            out.closeEntry();

            var directory = new ZipEntry("demo/");
            directory.setTime(0L);
            out.putNextEntry(directory);
            out.closeEntry();
        }
        return Files.readAllBytes(file);
    }

    private static void plain(ZipOutputStream out, String name, byte[] content)
            throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0L);
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    /** Carries every non-directory baseline entry, then adds one new entry. */
    private static byte[] writeOnce(Path tmp, byte[] baseline, String fileName, byte[] extra)
            throws IOException {
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        var target = tmp.resolve(fileName);
        try (var writer = new RawZipWriter(target, baseline, index)) {
            for (var entry : index.values()) {
                if (!entry.directory()) {
                    writer.carry(entry.name());
                }
            }
            writer.put("demo/new.txt", extra);
            writer.finish();
        }
        return Files.readAllBytes(target);
    }

    private static byte[] readEntry(Path jar, String name) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(name);
            assertNotNull(entry, jar + " must contain " + name);
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static PatchEngineException refused(byte[] zip) {
        return assertThrows(PatchEngineException.class,
            () -> RawZipWriter.readIndex(zip, "The fixture baseline"));
    }

    private static byte[] flipTo(byte[] zip, int at, int value) {
        zip[at] = (byte) value;
        return zip;
    }

    // ------------------------------------------------------------------
    // The happy path, which has to prove both halves
    // ------------------------------------------------------------------

    @Test
    void unchangedEntriesAreCopiedByteForByteAndWrittenOnesRoundTrip(@TempDir Path tmp)
            throws IOException {
        var baseline = baselineJar(tmp);
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        var payload = "replaced content".getBytes(StandardCharsets.UTF_8);
        var written = writeOnce(tmp, baseline, "output.jar", payload);

        // The JDK opens it as a jar, and every entry is what it should be.
        try (var zip = new ZipFile(tmp.resolve("output.jar").toFile())) {
            for (var entry : index.values()) {
                if (entry.directory()) {
                    continue;
                }
                assertNotNull(zip.getEntry(entry.name()), entry.name());
                assertArrayEquals(readEntry(tmp.resolve(BASELINE), entry.name()),
                    readEntry(tmp.resolve("output.jar"), entry.name()), entry.name());
            }
            assertArrayEquals(payload, readEntry(tmp.resolve("output.jar"), "demo/new.txt"));
            assertEquals(ZipEntry.STORED,
                zip.getEntry("demo/stored.bin").getMethod(),
                "a stored entry must stay stored: copying does not reinterpret it");
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("demo/data.txt").getMethod());
        }

        // And the copy is the baseline's own region, not a recompression of it.
        var outputIndex = RawZipWriter.readIndex(written, "The output");
        assertNull(outputIndex.get("demo/"), "directories are not carried into the artifact");
        assertNotNull(outputIndex.get("demo/new.txt"));
        for (var entry : index.values()) {
            if (entry.directory()) {
                continue;
            }
            var copy = outputIndex.get(entry.name());
            assertNotNull(copy, entry.name());
            assertEquals(entry.method(), copy.method(), entry.name());
            assertEquals(entry.crc(), copy.crc(), entry.name());
            assertEquals(entry.compressedSize(), copy.compressedSize(), entry.name());
            assertEquals(entry.uncompressedSize(), copy.uncompressedSize(), entry.name());
            assertEquals(entry.regionEnd() - entry.localOffset(),
                copy.regionEnd() - copy.localOffset(), entry.name());
            assertEquals(-1,
                Arrays.mismatch(baseline, (int) entry.localOffset(), (int) entry.regionEnd(),
                    written, (int) copy.localOffset(), (int) copy.regionEnd()),
                entry.name() + " must be byte-identical to the baseline region it came from");
        }
    }

    @Test
    void twoRunsOverOneBaselineProduceTheSameBytes(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var extra = "same every time".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(writeOnce(tmp, baseline, "first.jar", extra),
            writeOnce(tmp, baseline, "second.jar", extra),
            "two applications of one patch set to one baseline must write one file");
    }

    @Test
    void aBaselineWithNoEntriesCanBeWrittenInto(@TempDir Path tmp) throws IOException {
        var empty = tmp.resolve("empty.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(empty))) {
            // No entries at all: an end record and nothing else.
        }
        var baseline = Files.readAllBytes(empty);
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        assertTrue(index.isEmpty());

        var target = tmp.resolve("output.jar");
        try (var writer = new RawZipWriter(target, baseline, index)) {
            writer.put("only.txt", "content".getBytes(StandardCharsets.UTF_8));
            writer.finish();
        }
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8),
            readEntry(target, "only.txt"));
    }

    // ------------------------------------------------------------------
    // Fail closed: a baseline the copy cannot represent faithfully
    // ------------------------------------------------------------------

    @Test
    void aFileWhoseEndRecordDoesNotEndItIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var message = refused(Arrays.copyOf(baseline, baseline.length - 1)).getMessage();
        assertTrue(message.contains("end-of-central-directory"), message);
    }

    @Test
    void anEncryptedEntryIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        flipTo(baseline, entry.cdOffset() + 8, baseline[entry.cdOffset() + 8] | 0x01);
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("is encrypted"), message);
    }

    @Test
    void aGeneralPurposeFlagBitNobodyDeclaredIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        flipTo(baseline, entry.cdOffset() + 8, baseline[entry.cdOffset() + 8] | 0x20);
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("does not understand"), message);
    }

    @Test
    void aZip64SizedEntryIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        var at = entry.cdOffset() + 20;
        for (var i = 0; i < 4; i++) {
            baseline[at + i] = (byte) 0xFF;
        }
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("ZIP64"), message);
    }

    @Test
    void anEntryWithoutALocalHeaderWhereTheIndexSaysIsRefused(@TempDir Path tmp)
            throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        flipTo(baseline, (int) entry.localOffset(), 0);
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("no local file header"), message);
    }

    @Test
    void aLocalHeaderNamingADifferentEntryIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        flipTo(baseline, (int) entry.localOffset() + 30, baseline[(int) entry.localOffset() + 30] ^ 0x01);
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("spells the name differently"), message);
    }

    @Test
    void aDataDescriptorThatDisagreesWithTheCentralDirectoryIsRefused(@TempDir Path tmp)
            throws IOException {
        var baseline = baselineJar(tmp);
        var entry = RawZipWriter.readIndex(baseline, "The fixture baseline").get("demo/data.txt");
        // The last twelve bytes of a signed descriptor are CRC, compressed
        // size, uncompressed size — same tail when it is unsigned.
        flipTo(baseline, (int) entry.regionEnd() - 12,
            baseline[(int) entry.regionEnd() - 12] ^ 0x01);
        var message = refused(baseline).getMessage();
        assertTrue(message.contains("data descriptor disagrees"), message);
    }

    @Test
    void theSameEntryNamedTwiceIsRefused(@TempDir Path tmp) throws IOException {
        var file = tmp.resolve("duplicate.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            plain(out, "aa.txt", "first".getBytes(StandardCharsets.UTF_8));
            plain(out, "bb.txt", "second".getBytes(StandardCharsets.UTF_8));
        }
        var zip = Files.readAllBytes(file);
        var second = RawZipWriter.readIndex(zip, "The fixture baseline").get("bb.txt");
        var name = "aa.txt".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, zip, second.cdOffset() + 46, name.length);
        var message = refused(zip).getMessage();
        assertTrue(message.contains("appears twice"), message);
    }

    // ------------------------------------------------------------------
    // Fail closed: writes that would not mean what they claim
    // ------------------------------------------------------------------

    @Test
    void copyingSomethingTheBaselineDoesNotHoldIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        try (var writer = new RawZipWriter(tmp.resolve("output.jar"), baseline, index)) {
            var failure = assertThrows(PatchEngineException.class,
                () -> writer.carry("not/there.txt"));
            assertTrue(failure.getMessage().contains("central directory does not list it"),
                failure.getMessage());
        }
    }

    @Test
    void copyingADirectoryIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        try (var writer = new RawZipWriter(tmp.resolve("output.jar"), baseline, index)) {
            var failure = assertThrows(PatchEngineException.class, () -> writer.carry("demo/"));
            assertTrue(failure.getMessage().contains("directory entry"), failure.getMessage());
        }
    }

    @Test
    void writingOneNameTwiceIsRefused(@TempDir Path tmp) throws IOException {
        var baseline = baselineJar(tmp);
        var index = RawZipWriter.readIndex(baseline, "The fixture baseline");
        try (var writer = new RawZipWriter(tmp.resolve("output.jar"), baseline, index)) {
            writer.carry("demo/data.txt");
            var failure = assertThrows(PatchEngineException.class,
                () -> writer.put("demo/data.txt", "shadow".getBytes(StandardCharsets.UTF_8)));
            assertTrue(failure.getMessage().contains("twice"), failure.getMessage());
        }
    }
}
