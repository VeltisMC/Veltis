package org.veltismc.nativeengine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

    /*
     * e_lfanew is chosen by the linker, not fixed at 0x40. These cases are the
     * reason the probe seeks instead of reading one static window: the old
     * behaviour rejected a well-formed binary simply because its header sat past
     * the first 4096 bytes.
     */
    @Test
    void peHeaderAtAnUnusualOffsetIsFoundBySeeking() throws Exception {
        for (var offset : new int[] {0x40, 0x100, 0x200, 0xFFF, 0x1000, 0x1234, 0x8000}) {
            var path = write("pe-at-" + offset, peImageAt(0x8664, offset, offset + 0x400));
            var info = NativeLoader.probeBinary(path);

            assertEquals("PE", info.format(), "offset " + offset);
            assertTrue(info.architectures().contains("x86_64"), "offset " + offset);
            assertNull(NativeLoader.checkBinary(path, "windows-x86_64"), "offset " + offset);
        }
    }

    @Test
    void peArm64AndX86AreToldApartFromX8664() throws Exception {
        assertEquals("aarch64",
                NativeLoader.probeBinary(write("arm64.dll", peImage(0xAA64))).architectures().iterator().next());
        assertEquals("x86",
                NativeLoader.probeBinary(write("x86.dll", peImage(0x014C))).architectures().iterator().next());
        assertEquals("x86_64",
                NativeLoader.probeBinary(write("x64.dll", peImage(0x8664))).architectures().iterator().next());
    }

    @Test
    void unknownPeMachineIsReportedRatherThanGuessed() throws Exception {
        var info = NativeLoader.probeBinary(write("itanium.dll", peImage(0x200)));

        assertEquals("PE", info.format());
        assertTrue(info.architectures().contains("unknown-0x200"), info.describe());
        var reason = NativeLoader.checkBinary(write("itanium2.dll", peImage(0x200)), "windows-x86_64");
        assertNotNull(reason);
        assertTrue(reason.contains("unknown-0x200"), reason);
    }

    @Test
    void truncatedDosHeaderSaysHowShortTheFileIs() {
        var twoBytes = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("mz-2.dll", new byte[] {'M', 'Z'})));
        assertTrue(twoBytes.getMessage().contains("DOS header"), twoBytes.getMessage());
        assertTrue(twoBytes.getMessage().contains("2 bytes"), twoBytes.getMessage());

        // One byte short of the field that holds e_lfanew.
        var justShort = new byte[0x3F];
        justShort[0] = 'M';
        justShort[1] = 'Z';
        var shortHeader = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("mz-63.dll", justShort)));
        assertTrue(shortHeader.getMessage().contains("DOS header"), shortHeader.getMessage());
    }

    @Test
    void impossiblePeOffsetsAreRejectedWithoutWrappingOrReadingTheFile() throws Exception {
        // e_lfanew below the end of the DOS header cannot address a PE header.
        var overlapping = new byte[0x80];
        overlapping[0] = 'M';
        overlapping[1] = 'Z';
        putLe32(overlapping, 0x3C, 0x20);
        var inside = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("overlap.dll", overlapping)));
        assertTrue(inside.getMessage().contains("invalid PE header offset"), inside.getMessage());

        // The maximum value of a 32-bit field must not become a negative int.
        var wrapped = new byte[0x80];
        wrapped[0] = 'M';
        wrapped[1] = 'Z';
        putLe32(wrapped, 0x3C, 0xFFFFFFFF);
        var past = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("wrapped.dll", wrapped)));
        assertTrue(past.getMessage().contains("past the end"), past.getMessage());
        assertTrue(past.getMessage().contains("4294967295"), past.getMessage());
    }

    @Test
    void truncatedSignatureAndMissingSignatureAreDistinguishable() throws Exception {
        // The four signature bytes themselves are cut off by the end of file.
        var cut = new byte[0x42];
        cut[0] = 'M';
        cut[1] = 'Z';
        putLe32(cut, 0x3C, 0x40);
        cut[0x40] = 'P';
        var cutReason = assertThrows(IOException.class, () -> NativeLoader.probeBinary(write("cut.dll", cut)));
        assertTrue(cutReason.getMessage().contains("truncated PE signature"), cutReason.getMessage());
        assertTrue(cutReason.getMessage().contains("2 of 4"), cutReason.getMessage());

        // The signature starts, but the COFF header holding the machine type does
        // not fit before the end of the file.
        var halfCoff = peHeaderOnlyShell(0x40 + 10, 0x40);
        halfCoff[0x40] = 'P';
        halfCoff[0x41] = 'E';
        var coffReason = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("halfCoff.dll", halfCoff)));
        assertTrue(coffReason.getMessage().contains("truncated COFF header"), coffReason.getMessage());
        assertTrue(coffReason.getMessage().contains("10 of 24"), coffReason.getMessage());

        // Every byte is present; it is simply not a PE header.
        var bogus = peHeaderOnlyShell(0x80, 0x40);
        putLe32(bogus, 0x40, 0x0A0B0C0D);
        var bogusReason = assertThrows(IOException.class,
                () -> NativeLoader.probeBinary(write("bogus.dll", bogus)));
        assertTrue(bogusReason.getMessage().contains("missing PE signature"), bogusReason.getMessage());
        assertTrue(bogusReason.getMessage().contains("0D 0C 0B 0A"), bogusReason.getMessage());
    }

    @Test
    void machOHeaderBitnessMustMatchTheCpuItDeclares() throws Exception {
        // MH_MAGIC (32-bit) carrying an arm64 CPU is corruption, not a library.
        var mismatch = write("macho-32-with-arm64", machOImage(0xFEEDFACE, 0x0100000C, true));
        var reason = assertThrows(IOException.class, () -> NativeLoader.probeBinary(mismatch));
        assertTrue(reason.getMessage().contains("32-bit"), reason.getMessage());
        assertTrue(reason.getMessage().contains("0x100000C"), reason.getMessage());

        // The same CPU in the 64-bit container is the normal Apple Silicon case.
        assertEquals("aarch64",
                NativeLoader.probeBinary(write("macho-64-arm64", machOImage(0xFEEDFACF, 0x0100000C, true)))
                        .architectures().iterator().next());

        // Big-endian thin headers (MH_CIGAM_64) still resolve to the same CPU.
        assertEquals("x86_64",
                NativeLoader.probeBinary(write("macho-be-x64", machOImage(0xFEEDFACF, 0x01000007, false)))
                        .architectures().iterator().next());

        // A header shorter than mach_header is truncated, whatever its magic says.
        var stub = new byte[16];
        stub[0] = (byte) 0xCF;
        stub[1] = (byte) 0xFA;
        stub[2] = (byte) 0xED;
        stub[3] = (byte) 0xFE;
        putLe32(stub, 4, 0x0100000C);
        var truncated = assertThrows(IOException.class, () -> NativeLoader.probeBinary(write("macho-stub", stub)));
        assertTrue(truncated.getMessage().contains("truncated Mach-O header"), truncated.getMessage());
    }

    @Test
    void fatMachOCountsAreSanityCheckedAgainstRealContainers() throws Exception {
        var noSlices = write("fat-zero", fatImageWithCount(0));
        assertTrue(assertThrows(IOException.class, () -> NativeLoader.probeBinary(noSlices))
                .getMessage().contains("architecture count 0"));

        var tooMany = write("fat-huge", fatImageWithCount(64));
        assertTrue(assertThrows(IOException.class, () -> NativeLoader.probeBinary(tooMany))
                .getMessage().contains("between 1 and 16"));

        // A Java class file starts with the same four bytes as a fat Mach-O. Its
        // major version sits where nfat_arch is read, so the count is the only
        // thing that tells the two containers apart before the slices are garbage.
        var classFile = write("Hello.class", fatImageWithCount(52));
        var rejected = assertThrows(IOException.class, () -> NativeLoader.probeBinary(classFile));
        assertTrue(rejected.getMessage().contains("architecture count 52"), rejected.getMessage());

        // Slice count promises more table than the file holds.
        var cutTable = new byte[8 + 20];
        putBe32(cutTable, 0, 0xCAFEBABE);
        putBe32(cutTable, 4, 4);
        putBe32(cutTable, 8, 0x0100000C);
        assertTrue(assertThrows(IOException.class, () -> NativeLoader.probeBinary(write("fat-cut", cutTable)))
                .getMessage().contains("truncated universal Mach-O architecture table"));
    }

    @Test
    void wrongArchitectureIsExplainedBeforeTheOperatingSystemLoader() throws Exception {        var arm64 = write("arm64.dll", peImage(0xAA64));
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
    void aLibraryFromAnotherOperatingSystemIsRejectedBeforeTheSystemLoaderRuns() throws Exception {
        var host = switch (NativeLoader.platformKey()) {
            case "windows-x86_64", "windows-aarch64" -> "PE";
            case "linux-x86_64", "linux-aarch64" -> "ELF";
            case "macos-x86_64", "macos-aarch64" -> "Mach-O";
            default -> null;
        };
        Assumptions.assumeTrue(host != null, "unrecognised host platform");
        // The file written is the container after the host's own, in rotation, so
        // the test means the same thing on every supported system.
        var foreign = switch (host) {
            case "PE" -> "ELF";
            case "ELF" -> "Mach-O";
            default -> "PE";
        };
        var bytes = switch (foreign) {
            case "ELF" -> elfImage(0x3E, true);
            case "Mach-O" -> machOImage(0x0100000C, true);
            default -> peImage(0x8664);
        };
        var name = switch (foreign) {
            case "ELF" -> "libveltis_native.so";
            case "Mach-O" -> "libveltis_native.dylib";
            default -> "veltis_native.dll";
        };

        var file = write(name, bytes);
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, file.toString()));

        assertNull(result.library(), "a foreign container must never be returned");
        assertTrue(result.status().loaded(), "the file exists, so this is found-but-rejected");
        assertFalse(result.status().available());
        assertTrue(result.status().reason().contains(foreign), result.status().reason());
        assertTrue(result.status().reason().contains(host), result.status().reason());
    }

    @Test
    void aLibraryForAnotherArchitectureIsRejectedBeforeTheSystemLoaderRuns() throws Exception {
        var platform = NativeLoader.platformKey();
        // Right container, wrong machine. The operating system loader also
        // refuses this, but only with a generic "could not load" message.
        var mismatched = switch (platform) {
            case "windows-x86_64" -> peImage(0xAA64);
            case "windows-aarch64" -> peImage(0x8664);
            case "linux-x86_64" -> elfImage(0xB7, true);
            case "linux-aarch64" -> elfImage(0x3E, true);
            case "macos-aarch64" -> machOImage(0x01000007, true);
            case "macos-x86_64" -> machOImage(0x0100000C, true);
            default -> null;
        };
        Assumptions.assumeTrue(mismatched != null, "no mismatched-arch image for this host");

        var file = write(NativeLoader.libraryFileName(platform), mismatched);
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, file.toString()));

        assertNull(result.library(), "an incompatible architecture must never be returned");
        assertTrue(result.status().loaded(), "the file exists, so this is found-but-rejected");
        assertFalse(result.status().available());
        assertTrue(result.status().reason().contains(platform), result.status().reason());
        assertEquals(0, result.status().abiVersion(), "no ABI was negotiated");
    }

    @Test
    void anUnusableConfiguredPathIsAReportedFailureNotAnException() {
        // A path the filesystem cannot name is a configuration mistake, and the
        // server starts before an administrator fixes it. Path.of() rejects a NUL
        // with InvalidPathException on some platforms and accepts it on others, so
        // the contract asserted here is the one NativeLoader promises: a status
        // that explains itself, never an unchecked exception into the caller.
        var result = NativeLoader.tryLoad(
                new NativeConfig(true, true, true, false, "veltis\u0000native"));

        assertNull(result.library(), "no library can come from a path that does not resolve");
        assertFalse(result.status().available());
        assertFalse(result.status().loaded());
        assertTrue(result.status().fallbackActive());
        assertTrue(result.status().reason().startsWith("invalid native library path '")
                        || result.status().reason().startsWith("configured native library not found: "),
                result.status().reason());
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
     * Minimal syntactically valid headers. The probe reads the container, the
     * header position and the machine type only, so no section table or program
     * header is needed; the operating system is never asked to load these
     * synthetic files.
     */
    private static byte[] peImage(int machine) {
        return peImageAt(machine, 0x40, 0x80);
    }

    /** A syntactically valid PE whose header starts at {@code peOffset}. */
    private static byte[] peImageAt(int machine, int peOffset, int fileSize) {
        var bytes = peHeaderOnlyShell(fileSize, peOffset);
        bytes[peOffset] = 'P';
        bytes[peOffset + 1] = 'E';
        putLe16(bytes, peOffset + 4, machine);
        return bytes;
    }

    /** An MZ file of {@code fileSize} bytes whose e_lfanew names {@code peOffset}. */
    private static byte[] peHeaderOnlyShell(int fileSize, int peOffset) {
        var bytes = new byte[fileSize];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        putLe32(bytes, 0x3C, peOffset);
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
        // MH_MAGIC_64: the only bitness a supported Apple slice can have.
        return machOImage(0xFEEDFACF, cpuType, littleEndian);
    }

    private static byte[] machOImage(int magic, int cpuType, boolean littleEndian) {
        var bytes = new byte[32];
        if (littleEndian) {
            putLe32(bytes, 0, magic);
            putLe32(bytes, 4, cpuType);
        } else {
            putBe32(bytes, 0, magic);
            putBe32(bytes, 4, cpuType);
        }
        return bytes;
    }

    private static byte[] fatImage(int... cpuTypes) {
        var bytes = fatImageWithCount(cpuTypes.length);
        for (var i = 0; i < cpuTypes.length; i++) {
            putBe32(bytes, 8 + 20 * i, cpuTypes[i]);
        }
        return bytes;
    }

    /** A fat header that declares {@code count} slices, all with CPU type zero. */
    private static byte[] fatImageWithCount(int count) {
        var bytes = new byte[8 + 20 * count];
        putBe32(bytes, 0, 0xCAFEBABE);
        putBe32(bytes, 4, count);
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
