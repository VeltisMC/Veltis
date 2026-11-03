package org.veltismc.nativeengine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLoaderTest {

    @TempDir
    Path temp;

    @Test
    void missingLibraryIsAReportedFailureNotAnException() {
        var missing = temp.resolve("does-not-exist").resolve(NativeLoader.libraryFileName());
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, missing.toString()));

        assertNull(result.library());
        assertFalse(result.status().available());
        assertFalse(result.status().loaded());
        assertTrue(result.status().reason().contains("not found"));
    }

    @Test
    void locateAcceptsAConfiguredDirectoryOrFile() throws Exception {
        var directory = Files.createDirectories(temp.resolve("native"));
        var library = Files.write(directory.resolve(NativeLoader.libraryFileName()), new byte[] {0});

        assertEquals(library.toAbsolutePath().normalize(),
                NativeLoader.locate(new NativeConfig(true, true, true, false, directory.toString())).orElseThrow());
        assertEquals(library.toAbsolutePath().normalize(),
                NativeLoader.locate(new NativeConfig(true, true, true, false, library.toString())).orElseThrow());
    }

    @Test
    void locateDoesNotAcceptADirectoryWithoutThePlatformLibrary() throws Exception {
        var directory = Files.createDirectories(temp.resolve("empty-native"));
        Files.write(directory.resolve("something-else.bin"), new byte[] {0});
        assertTrue(NativeLoader.locate(
                new NativeConfig(true, true, true, false, directory.toString())).isEmpty());
    }

    @Test
    void abiMismatchIsRejectedBeforeUse() {
        var reason = NativeLoader.checkCompatibility(999, 1, 0);
        assertNotNull(reason);
        assertTrue(reason.contains("ABI mismatch"), reason);
    }

    @Test
    void engineMajorMismatchIsRejected() {
        var reason = NativeLoader.checkCompatibility(1, 2, 0);
        assertNotNull(reason);
        assertTrue(reason.contains("engine version mismatch"), reason);
    }

    @Test
    void engineMinorBelowMinimumIsRejected() {
        var reason = NativeLoader.checkCompatibility(1, 1, -1);
        assertNotNull(reason);
        assertTrue(reason.contains("too old"), reason);
    }

    @Test
    void sameAbiAndNewerMinorIsAccepted() {
        assertNull(NativeLoader.checkCompatibility(1, 1, 0));
        assertNull(NativeLoader.checkCompatibility(1, 1, 4));
    }

    @Test
    void platformAndLibraryNameAreStable() {
        assertFalse(NativeLoader.platformKey().isBlank());
        assertFalse(NativeLoader.platformKey().contains(" "));
        assertFalse(NativeLoader.libraryFileName().isBlank());
        assertTrue(NativeLoader.libraryFileName().contains("veltis_native"));
    }

    @Test
    void platformKeysNormalizeOsAndArchitectureIndependently() {
        assertEquals("windows-x86_64", NativeLoader.platformKey("Windows 11", "amd64"));
        assertEquals("windows-aarch64", NativeLoader.platformKey("Windows Server 2025", "aarch64"));
        assertEquals("macos-aarch64", NativeLoader.platformKey("Mac OS X", "arm64"));
        assertEquals("macos-x86_64", NativeLoader.platformKey("Darwin", "x86_64"));
        assertEquals("linux-x86_64", NativeLoader.platformKey("Linux", "amd64"));
        assertEquals("linux-riscv64", NativeLoader.platformKey("Linux", "riscv64"));
        assertEquals("freebsd-x86_64", NativeLoader.platformKey("FreeBSD", "amd64"));
        assertEquals("unknown-unknown", NativeLoader.platformKey("", ""));
    }

    @Test
    void libraryNameDependsOnOperatingSystemNotArchitecture() {
        assertEquals("veltis_native.dll", NativeLoader.libraryFileName("windows-aarch64"));
        assertEquals("libveltis_native.so", NativeLoader.libraryFileName("linux-aarch64"));
        assertEquals("libveltis_native.dylib", NativeLoader.libraryFileName("macos-x86_64"));
    }

    @Test
    void probesPeElfAndMachOHeaders() throws Exception {
        var pe64 = write("pe-x64", peImage(0x8664));
        assertEquals("PE", NativeLoader.probeBinary(pe64).format());
        assertTrue(NativeLoader.probeBinary(pe64).architectures().contains("x86_64"));

        var peArm = write("pe-arm64", peImage(0xAA64));
        assertTrue(NativeLoader.probeBinary(peArm).architectures().contains("aarch64"));

        var elf = write("elf-x64", elfImage(0x3E, true));
        assertEquals("ELF", NativeLoader.probeBinary(elf).format());
        assertTrue(NativeLoader.probeBinary(elf).architectures().contains("x86_64"));

        var elfBigEndian = write("elf-arm64-be", elfImage(0xB7, false));
        assertTrue(NativeLoader.probeBinary(elfBigEndian).architectures().contains("aarch64"));

        var machO = write("macho-arm64", machOImage(0x0100000C, true));
        assertEquals("Mach-O", NativeLoader.probeBinary(machO).format());
        assertTrue(NativeLoader.probeBinary(machO).architectures().contains("aarch64"));

        var universal = write("macho-universal", fatImage(0x01000007, 0x0100000C));
        assertEquals("Mach-O", NativeLoader.probeBinary(universal).format());
        assertTrue(NativeLoader.probeBinary(universal).architectures().contains("x86_64"));
        assertTrue(NativeLoader.probeBinary(universal).architectures().contains("aarch64"));
    }

    @Test
    void wrongArchitectureIsExplainedBeforeTheOperatingSystemLoader() throws Exception {
        var arm64 = write("arm64.dll", peImage(0xAA64));
        var reason = NativeLoader.checkBinary(arm64, "windows-x86_64");

        assertNotNull(reason);
        assertTrue(reason.contains("aarch64"), reason);
        assertTrue(reason.contains("windows-x86_64"), reason);
        assertNull(NativeLoader.checkBinary(arm64, "windows-aarch64"));

        var x64 = write("x64.dll", peImage(0x8664));
        assertNull(NativeLoader.checkBinary(x64, "windows-x86_64"));
    }

    @Test
    void wrongBinaryFormatIsExplainedBeforeTheOperatingSystemLoader() throws Exception {
        var elf = write("libveltis_native.so", elfImage(0x3E, true));
        var reason = NativeLoader.checkBinary(elf, "windows-x86_64");

        assertNotNull(reason);
        assertTrue(reason.contains("ELF"), reason);
        assertTrue(reason.contains("PE"), reason);
        assertNull(NativeLoader.checkBinary(elf, "linux-x86_64"));
    }

    @Test
    void corruptOrTruncatedLibraryIsRejectedWithoutThrowing() throws Exception {
        var garbage = write("garbage.bin", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        var truncated = write("truncated.dll", new byte[] {'M', 'Z'});

        var garbageReason = NativeLoader.checkBinary(garbage, NativeLoader.platformKey());
        var truncatedReason = NativeLoader.checkBinary(truncated, NativeLoader.platformKey());
        assertNotNull(garbageReason);
        assertNotNull(truncatedReason);
        assertTrue(garbageReason.contains("not usable"), garbageReason);
        assertTrue(truncatedReason.contains("truncated"), truncatedReason);
    }

    @Test
    void javaOnlySuiteHasNoNativeFixtureConfigured() {
        // Enabled only by `-Dveltis.native.test.expectJavaOnly=true`. It turns the
        // CI's "Java-only" claim into an assertion: if a fixture leaked into the
        // JVM, the native tests would run instead of skipping and this fails.
        Assumptions.assumeTrue(Boolean.getBoolean("veltis.native.test.expectJavaOnly"),
                "not running the Java-only configuration");

        for (var property : new String[] {
                "veltis.native.test.library",
                "veltis.native.test.fakeAbi",
                "veltis.native.test.fakeVersion",
                "veltis.native.test.fakeInit",
                "veltis.native.test.fakeTransform",
                "veltis.native.test.fakeMissing",
                "veltis.native.test.fakeMinimal",
        }) {
            var value = System.getProperty(property);
            assertTrue(value == null || value.isBlank(),
                    () -> property + " must be unset in the Java-only run, but was: " + value);
        }
    }

    @Test
    void builtLibraryMatchesTheHostFormatAndArchitecture() throws Exception {
        var path = fixturePath("veltis.native.test.library");
        var info = NativeLoader.probeBinary(path);
        var platform = NativeLoader.platformKey();

        assertNull(NativeLoader.checkBinary(path, platform));
        assertTrue(info.architectures().contains(platform.substring(platform.indexOf('-') + 1)),
                () -> "unexpected architectures in " + info.describe());
    }

    @Test
    void realLibraryWithWrongAbiIsRejected() {
        var path = fixturePath("veltis.native.test.fakeAbi");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));

        assertNull(result.library(), "an incompatible library must never be returned");
        assertTrue(result.status().loaded(), "the library was found and opened");
        assertFalse(result.status().available());
        assertTrue(result.status().reason().contains("ABI mismatch"), result.status().reason());
    }

    @Test
    void wrongAbiIsReportedBeforeMissingVersionSymbols() {
        var path = fixturePath("veltis.native.test.fakeMinimal");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));

        assertNull(result.library());
        assertTrue(result.status().loaded(), "the library was opened");
        assertTrue(result.status().reason().contains("ABI mismatch"), result.status().reason());
        assertFalse(result.status().reason().contains("missing native export"), result.status().reason());
    }

    @Test
    void realLibraryWithWrongEngineVersionIsRejected() {
        var path = fixturePath("veltis.native.test.fakeVersion");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));

        assertNull(result.library(), "an incompatible library must never be returned");
        assertTrue(result.status().loaded(), "the library was found and opened");
        assertFalse(result.status().available());
        assertTrue(result.status().reason().contains("engine version mismatch"), result.status().reason());
    }

    @Test
    void compatibleLibraryWithAMissingSymbolIsRejected() {
        var path = fixturePath("veltis.native.test.fakeMissing");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));

        assertNull(result.library(), "a library missing an ABI symbol must never be returned");
        assertTrue(result.status().loaded());
        assertFalse(result.status().available());
        assertTrue(result.status().reason().contains("missing native export"), result.status().reason());
    }

    @Test
    void initializationFailureIsReportedByTheLoadedLibrary() {
        var path = fixturePath("veltis.native.test.fakeInit");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));
        assertNotNull(result.library(), () -> "fixture should load: " + result.status().summary());

        try (var loaded = result.library()) {
            assertNotEquals(0, loaded.initialize());
            assertFalse(loaded.isInitialized());
            assertTrue(loaded.lastError().contains("fixture init failure"), loaded.lastError());
        }
    }

    @Test
    void transformFailureIsReportedByTheLoadedLibrary() {
        var path = fixturePath("veltis.native.test.fakeTransform");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));
        assertNotNull(result.library(), () -> "fixture should load: " + result.status().summary());

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());
            try (var arena = Arena.ofConfined()) {
                var input = arena.allocateFrom(ValueLayout.JAVA_INT, new int[] {1, 2, 3});
                var output = arena.allocate(ValueLayout.JAVA_INT, 3);
                assertNotEquals(0, loaded.transform(input, 3, 7, output, 3));
                assertTrue(loaded.lastError().contains("fixture transform failure"), loaded.lastError());
            }
        }
    }

    @Test
    void corruptedFileIsAReportedFailureNotAnException() throws Exception {
        var directory = Files.createDirectories(temp.resolve("corrupt"));
        var file = Files.write(directory.resolve(NativeLoader.libraryFileName()),
                "this is not a shared library".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, file.toString()));

        assertNull(result.library());
        assertFalse(result.status().available());
        assertTrue(result.status().fallbackActive());
        assertFalse(result.status().reason().isBlank());
    }

    @Test
    void closedLibraryRejectsFurtherOperations() {
        var path = fixturePath("veltis.native.test.fakeTransform");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));
        assertNotNull(result.library());

        var loaded = result.library();
        loaded.close();
        loaded.close();

        assertThrows(IllegalStateException.class, loaded::initialize);
        assertThrows(IllegalStateException.class, loaded::isInitialized);
        assertThrows(IllegalStateException.class, loaded::selfTest);
        assertThrows(IllegalStateException.class, loaded::capabilitiesMask);
        assertThrows(IllegalStateException.class, loaded::lastError);
        assertThrows(IllegalStateException.class, () -> loaded.transform(
                MemorySegment.NULL, 0, 0, MemorySegment.NULL, 0));
    }

    @Test
    void transformRejectsSegmentsShorterThanTheDeclaredExtent() {
        var path = fixturePath("veltis.native.test.fakeTransform");
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, path.toString()));
        assertNotNull(result.library());

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());
            try (var arena = Arena.ofConfined()) {
                var input = arena.allocate(ValueLayout.JAVA_INT, 3);
                var output = arena.allocate(ValueLayout.JAVA_INT, 2);

                assertThrows(IllegalArgumentException.class,
                        () -> loaded.transform(input, 4, 0, output, 4));
                assertThrows(IllegalArgumentException.class,
                        () -> loaded.transform(input, 3, 0, output, 3));
                assertThrows(IllegalArgumentException.class,
                        () -> loaded.transform(input, -1, 0, output, -1));
                assertThrows(IllegalArgumentException.class,
                        () -> loaded.transform(null, 0, 0, output, 0));
            }
        }
    }

    private Path write(String name, byte[] bytes) throws Exception {
        return Files.write(temp.resolve(name), bytes);
    }

    /*
     * Minimal syntactically valid headers. The probe reads format and machine
     * only, so no section table or program header is needed; the operating
     * system is never asked to load these synthetic files.
     */
    private static byte[] peImage(int machine) {
        var bytes = new byte[0x80];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        putLe32(bytes, 0x3C, 0x40);
        bytes[0x40] = 'P';
        bytes[0x41] = 'E';
        putLe16(bytes, 0x44, machine);
        return bytes;
    }

    private static byte[] elfImage(int machine, boolean littleEndian) {
        var bytes = new byte[64];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = (byte) (littleEndian ? 1 : 2);
        if (littleEndian) {
            putLe16(bytes, 18, machine);
        } else {
            putBe16(bytes, 18, machine);
        }
        return bytes;
    }

    private static byte[] machOImage(int cpuType, boolean littleEndian) {
        var bytes = new byte[32];
        if (littleEndian) {
            // MH_MAGIC_64 (0xFEEDFACF) stored little-endian.
            bytes[0] = (byte) 0xCF;
            bytes[1] = (byte) 0xFA;
            bytes[2] = (byte) 0xED;
            bytes[3] = (byte) 0xFE;
            putLe32(bytes, 4, cpuType);
        } else {
            bytes[0] = (byte) 0xFE;
            bytes[1] = (byte) 0xED;
            bytes[2] = (byte) 0xFA;
            bytes[3] = (byte) 0xCF;
            putBe32(bytes, 4, cpuType);
        }
        return bytes;
    }

    private static byte[] fatImage(int... cpuTypes) {
        var bytes = new byte[8 + 20 * cpuTypes.length];
        putBe32(bytes, 0, 0xCAFEBABE);
        putBe32(bytes, 4, cpuTypes.length);
        for (var i = 0; i < cpuTypes.length; i++) {
            putBe32(bytes, 8 + 20 * i, cpuTypes[i]);
        }
        return bytes;
    }

    private static void putLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putBe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private static void putLe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void putBe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static Path fixturePath(String property) {
        var value = System.getProperty(property);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                property + " is not configured");
        var path = Path.of(value);
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "native test fixture has not been built: " + path + " (run :veltis-native:buildNative)");
        return path;
    }
}
