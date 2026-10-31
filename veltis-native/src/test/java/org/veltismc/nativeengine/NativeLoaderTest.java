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
