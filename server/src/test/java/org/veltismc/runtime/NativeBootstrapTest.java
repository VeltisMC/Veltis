package org.veltismc.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veltismc.nativeengine.NativeConfig;
import org.veltismc.nativeengine.NativeDistributionConfig;
import org.veltismc.nativeengine.NativeEngine;
import org.veltismc.nativeengine.NativeLoader;
import org.veltismc.nativeengine.NativeStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup-path tests for the optional native engine.
 *
 * <p>{@link VeltisBootstrap#boot(String[])} is the only place the server wires
 * native support in, and it must never fail startup: every one of these tests
 * starts the server with a library it cannot use and asserts that the server is
 * up, on the Java fallback, producing Java results, and that the native engine
 * does not claim availability it did not earn. {@link #validLibraryStartupEnablesNativeSupport}
 * is the one case where native is supposed to work.
 *
 * <p>The nine shapes of failure are the ones an operator can actually create:
 * no library at all, a library for another OS or another CPU, one built for a
 * different ABI, one missing an export, one that is not a binary, one truncated
 * mid-header, one whose initialization fails, and a configuration that names a
 * path the filesystem rejects.
 *
 * <p>Scenarios that need a built library, or one of the deliberately broken
 * fixtures, skip when the native artifacts were not built: {@code :server:test}
 * must stay runnable, and meaningful, on a machine with no C/C++ toolchain.
 *
 * <p>{@link VeltisBootstrap} initializes once per JVM by design, so every
 * scenario rewinds that flag in {@code @BeforeEach} before calling {@code boot};
 * {@link #bootAndStopWorkWithAndWithoutANativeLibrary} then adds the second
 * boot, which is the behaviour the flag exists to provide.
 */
class NativeBootstrapTest {

    private static final String PATH_PROPERTY = NativeConfig.PATH_PROPERTY;

    /** The properties these tests may touch, so each can put them back. */
    private static final String[] GUARDED_PROPERTIES = {
            PATH_PROPERTY,
            NativeConfig.ENABLED_PROPERTY,
            NativeConfig.AUTOLOAD_PROPERTY,
            NativeConfig.FALLBACK_PROPERTY,
            NativeConfig.STATS_PROPERTY,
            NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY,
            NativeDistributionConfig.OFFLINE_PROPERTY,
            NativeDistributionConfig.UPDATE_POLICY_PROPERTY,
            NativeDistributionConfig.DOWNLOAD_TIMEOUT_PROPERTY,
            NativeDistributionConfig.REPOSITORY_PROPERTY,
            "veltismc.home",
            "os.arch",
    };

    private final Map<String, String> savedProperties = new HashMap<>();

    @TempDir
    Path home;

    @BeforeEach
    void rememberNativeProperties() {
        for (var name : GUARDED_PROPERTIES) {
            var value = System.getProperty(name);
            if (value != null) {
                savedProperties.put(name, value);
            }
        }
        // The once-per-JVM boot flag and the loaded library are process state, so
        // every scenario starts from "not booted, nothing loaded" and the suite
        // stays independent of the order JUnit happens to pick.
        resetBootstrapFlag();
        // Downloading is on by default, and a bootstrap test has no business
        // contacting the official release line. Scenarios that exercise the
        // distribution point it at a closed loopback port and say so; the rest
        // start from the switch already off.
        System.setProperty(NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY, "false");
    }

    @AfterEach
    void restoreNativeProperties() {
        for (var name : GUARDED_PROPERTIES) {
            var value = savedProperties.get(name);
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        }
    }

    @Test
    void bootAndStopWorkWithAndWithoutANativeLibrary() {
        var configured = System.getProperty(PATH_PROPERTY, "").isBlank() ? null
                : System.getProperty(PATH_PROPERTY);

        VeltisBootstrap.boot(new String[] {"--home", home.toString()});

        var engine = VeltisBootstrap.nativeEngine();
        var status = engine.status();
        if (configured == null) {
            assertFalse(status.available(), status.summary());
            assertFalse(engine.nativeAvailable(), "no library was configured");
        } else {
            assertTrue(status.available(), status.summary());
            assertTrue(engine.nativeAvailable(), status.summary());
        }

        var input = sampleInput();
        assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42));

        VeltisBootstrap.onServerStopping();
        assertFalse(engine.nativeAvailable(), "server stop must release the native library");
        assertTrue(engine.status().fallbackActive());

        // Boot is a one-time operation. A second call must not throw and must
        // not resurrect a library the server already released.
        VeltisBootstrap.boot(new String[] {"--home", home.toString()});
        assertFalse(engine.nativeAvailable());

        assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42));
    }

    // ------------------------------------------------------------------
    // The failure matrix: the server starts, on Java, every time
    // ------------------------------------------------------------------

    /** Case 1: nothing installed. This is the ordinary Java-only installation. */
    @Test
    void startupWithoutAnyNativeLibraryUsesTheJavaFallback() {
        var absent = home.resolve("not-installed").resolve(NativeLoader.libraryFileName());
        startAndCheck(absent.toString(), false, "configured native library not found");
    }

    /** Case 2: the library the build produced, which is the one case that works. */
    @Test
    void validLibraryStartupEnablesNativeSupport() {
        var library = builtLibrary();
        System.setProperty(PATH_PROPERTY, library.toString());
        VeltisBootstrap.boot(new String[] {"--home", home.toString()});
        try {
            var engine = VeltisBootstrap.nativeEngine();
            assertTrue(engine.status().available(), engine.status().summary());
            assertTrue(engine.nativeAvailable(), engine.status().summary());
            assertTrue(engine.status().supports(org.veltismc.nativeengine.NativeCapabilities.TRANSFORM_I32),
                    engine.status().summary());

            var input = sampleInput();
            assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42),
                    "the native result must equal the authoritative Java one");
        } finally {
            VeltisBootstrap.onServerStopping();
        }
        assertFalse(VeltisBootstrap.nativeEngine().nativeAvailable(),
                "a native-enabled server must release the library when it stops");
    }

    /** Case 3: a library reporting an ABI this server does not speak. */
    @Test
    void startupWithALibraryOfTheWrongAbiUsesTheJavaFallback() {
        startAndCheck(fixture("veltis_native_fake_abi").toString(), true, "ABI mismatch");
    }

    /** Case 4: a library that dropped one of the promised exports. */
    @Test
    void startupWithALibraryMissingAnExportUsesTheJavaFallback() {
        startAndCheck(fixture("veltis_native_fake_missing").toString(), true,
                "could not use native library");
    }

    /**
     * Case 5: a binary for a different operating system. The probe has to reject
     * it, because handing it to the system loader is what turns a configuration
     * mistake into an unsatisfiable-link error.
     */
    @Test
    void startupWithALibraryFromAnotherOperatingSystemUsesTheJavaFallback() {
        var foreign = switch (osOfHost()) {
            case "windows", "macos" -> elfImage(0x3E);
            default -> peImage(0x8664);
        };
        startAndCheck(write("foreign-os", foreign).toString(), true, "binary, but");
    }

    /** Case 6: the right container, the wrong CPU. */
    @Test
    void startupWithALibraryForAnotherArchitectureUsesTheJavaFallback() {
        var os = osOfHost();
        var aarch64Host = archOfHost().equals("aarch64");
        byte[] image = switch (os) {
            // 0x014C is i386, 0xB7 aarch64, 0x3E x86-64; Mach-O uses CPU types.
            case "windows" -> peImage(0x014C);
            case "macos" -> machOImage(aarch64Host ? 0x01000007L : 0x0100000CL);
            default -> elfImage(aarch64Host ? 0x3E : 0xB7);
        };
        startAndCheck(write("foreign-arch", image).toString(), true, "but this JVM is");
    }

    /** Case 7: a file with a real magic number and nonsense inside it. */
    @Test
    void startupWithACorruptLibraryUsesTheJavaFallback() {
        var corrupt = switch (osOfHost()) {
            // MZ plus a PE header offset past the end of the file.
            case "windows" -> peWithHeaderOffsetBeyondTheFile();
            // A 32-bit Mach-O magic naming a CPU that only exists in 64-bit form.
            case "macos" -> machO32BitMagicNamingA64BitCpu();
            // ELF magic with an endian flag that does not exist.
            default -> elfWithImpossibleByteOrder();
        };
        startAndCheck(write("corrupt", corrupt).toString(), true, "is not usable");
    }

    /** Case 8: a file that stops in the middle of the header. */
    @Test
    void startupWithATruncatedBinaryHeaderUsesTheJavaFallback() {
        var truncated = switch (osOfHost()) {
            case "windows" -> new byte[] {'M', 'Z', 0, 0};
            case "macos" -> new byte[] {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0, 0, 0, 0};
            default -> "\u007FELF".getBytes(StandardCharsets.ISO_8859_1);
        };
        startAndCheck(write("truncated", truncated).toString(), true, "is not usable");
    }

    /** Case 9a: the library opens, then refuses to initialize. */
    @Test
    void startupWithANativeInitializationFailureUsesTheJavaFallback() {
        startAndCheck(fixture("veltis_native_fake_init").toString(), true,
                "native initialization failed");
    }

    /** A path the filesystem itself rejects must not reach the operator as a crash. */
    @Test
    void startupWithAnInvalidNativePathConfigurationUsesTheJavaFallback() {
        startAndCheck("veltis\u0000native", false, "invalid native library path");
    }

    /** The platform gate fires before the file is opened, on any platform. */
    @Test
    void startupOnAnUnsupportedPlatformUsesTheJavaFallback() {
        // A file that is exactly right for this host's container and CPU, so the
        // only thing that can reject it is the platform name itself.
        var library = write("host-shaped", hostContainerImage()).toString();
        var host = NativeLoader.platformKey();
        Assumptions.assumeFalse(host.endsWith("-riscv64") || host.endsWith("-sparc64"),
                "the host architecture is already unrecognised: " + host);
        System.setProperty("os.arch", host.contains("-x86_64") ? "sparc64" : "riscv64");
        startAndCheck(library, false, "unsupported platform");
    }

    /**
     * The download is a convenience, never a precondition: an operator who
     * enables it against a repository that refuses to answer must still get a
     * server that starts, on Java, with the failure reported rather than
     * thrown. This is the outage case — nothing is listening on that port, so
     * the connection fails for real rather than being mocked.
     */
    @Test
    void aFailingNativeDistributionNeverBlocksStartup() {
        System.clearProperty(PATH_PROPERTY);
        System.setProperty(NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY, "true");
        // Loopback is the one plain-HTTP host the transport accepts, and port 1
        // on it is reliably closed, so this fails as a refused connection and
        // never as a policy surprise.
        System.setProperty(NativeDistributionConfig.REPOSITORY_PROPERTY, "http://127.0.0.1:1/");
        System.setProperty(NativeDistributionConfig.DOWNLOAD_TIMEOUT_PROPERTY, "2");

        VeltisBootstrap.boot(new String[] {"--home", home.toString()});
        var engine = VeltisBootstrap.nativeEngine();
        try {
            var status = engine.status();
            assertFalse(status.available(),
                    "a failed download must not claim native support: " + status.summary());
            assertTrue(status.fallbackActive(), status.summary());

            var input = sampleInput();
            assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42),
                    "the server must answer in Java after a failed download");
        } finally {
            VeltisBootstrap.onServerStopping();
        }
        assertArrayEquals(NativeEngine.javaTransform(sampleInput(), 7),
                engine.transform(sampleInput(), 7));
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /**
     * Boots the server with {@code libraryPath} configured, asserts the startup
     * survived on the Java fallback, then stops it.
     *
     * @param libraryFound whether the failure is expected to have reached a file
     *                     on disk; {@code NativeStatus.loaded()} is what tells an
     *                     operator "not installed" from "installed and unusable".
     */
    private void startAndCheck(String libraryPath, boolean libraryFound, String expectedReason) {
        System.setProperty(PATH_PROPERTY, libraryPath);

        VeltisBootstrap.boot(new String[] {"--home", home.toString()});
        NativeStatus status;
        var engine = VeltisBootstrap.nativeEngine();
        try {
            status = engine.status();
            assertFalse(status.available(), "startup must not claim native support: " + status.summary());
            assertFalse(engine.nativeAvailable(),
                    "a failed native engine must not report itself as usable: " + status.summary());
            assertTrue(status.fallbackActive(), status.summary());
            assertEquals(libraryFound, status.loaded(),
                    "the reason text tells an operator where to look: " + status.summary());
            assertNotNull(status.reason());
            assertTrue(status.reason().contains(expectedReason),
                    "expected \"" + expectedReason + "\" in: " + status.summary());

            var input = sampleInput();
            assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42));
        } finally {
            VeltisBootstrap.onServerStopping();
        }

        assertFalse(engine.nativeAvailable(), "server stop must leave nothing loaded");
        assertTrue(engine.status().fallbackActive());
        assertArrayEquals(NativeEngine.javaTransform(sampleInput(), 7),
                engine.transform(sampleInput(), 7), "the stopped server still answers in Java");
    }

    private static int[] sampleInput() {
        return new int[] {0, 1, -1, 123456789, Integer.MIN_VALUE, Integer.MAX_VALUE};
    }

    /**
     * {@code VeltisBootstrap.boot} is deliberately once-per-JVM. Each scenario
     * needs a fresh startup, and the flag is the only server-side state that has
     * to be rewound; the native engine itself is reset by {@code shutdown()}.
     */
    private static void resetBootstrapFlag() {
        VeltisBootstrap.nativeEngine().shutdown();
        try {
            var booted = VeltisBootstrap.class.getDeclaredField("booted");
            booted.setAccessible(true);
            booted.setBoolean(null, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot rewind the bootstrap guard", e);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and synthetic binaries
    // ------------------------------------------------------------------

    private static String osOfHost() {
        var platform = NativeLoader.platformKey();
        var separator = platform.indexOf('-');
        return separator < 0 ? platform : platform.substring(0, separator);
    }

    private static String archOfHost() {
        var platform = NativeLoader.platformKey();
        var separator = platform.indexOf('-');
        return separator < 0 ? "" : platform.substring(separator + 1);
    }

    /** The library this host's build produced, or the test skips. */
    private static Path builtLibrary() {
        var configured = System.getProperty(PATH_PROPERTY, "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "no native library is configured; this scenario needs one"
                        + " (-D" + PATH_PROPERTY + "=<built library>)");
        var path = Path.of(configured);
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "configured native library is not a file: " + path);
        return path;
    }

    /**
     * A deliberately broken library, taken from the {@code test-fixtures}
     * directory beside the configured one.
     *
     * <p>Only that directory is consulted: a server test must not pick a fixture
     * up from an unrelated build output, which is why the real library has to be
     * named explicitly too.
     */
    private static Path fixture(String baseName) {
        var library = builtLibrary();
        var fileName = NativeLoader.libraryFileName().replace("veltis_native", baseName);
        var candidate = library.getParent().resolve("test-fixtures").resolve(fileName);
        Assumptions.assumeTrue(Files.isRegularFile(candidate),
                "native test fixture has not been built: " + candidate
                        + " (run :veltis-native:buildNative)");
        return candidate;
    }

    private Path write(String name, byte[] content) {
        try {
            var file = home.resolve(name + "-" + NativeLoader.libraryFileName());
            Files.write(file, content);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An image whose container and CPU match this host exactly. */
    private static byte[] hostContainerImage() {
        var aarch64Host = archOfHost().equals("aarch64");
        return switch (osOfHost()) {
            case "windows" -> peImage(aarch64Host ? 0xAA64 : 0x8664);
            case "macos" -> machOImage(aarch64Host ? 0x0100000CL : 0x01000007L);
            default -> elfImage(aarch64Host ? 0xB7 : 0x3E);
        };
    }

    /** A PE image: DOS header pointing at a COFF header with the given machine. */
    private static byte[] peImage(int machine) {
        var bytes = new byte[0x100];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        putLe32(bytes, 0x3C, 0x80);
        bytes[0x80] = 'P';
        bytes[0x81] = 'E';
        putLe16(bytes, 0x84, machine);
        return bytes;
    }

    /** An MZ header whose PE offset is beyond the last byte of the file. */
    private static byte[] peWithHeaderOffsetBeyondTheFile() {
        var bytes = peImage(0x8664);
        putLe32(bytes, 0x3C, bytes.length + 16);
        return bytes;
    }

    /** An ELF64 image with the given {@code e_machine}. */
    private static byte[] elfImage(int machine) {
        var bytes = new byte[64];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[6] = 1;
        putLe16(bytes, 18, machine);
        return bytes;
    }

    private static byte[] elfWithImpossibleByteOrder() {
        var bytes = elfImage(0x3E);
        bytes[5] = 9;
        return bytes;
    }

    /** A 32-bit Mach-O magic naming a CPU that only exists in 64-bit form. */
    private static byte[] machO32BitMagicNamingA64BitCpu() {
        var bytes = machOImage(0x01000007L);
        putLe32(bytes, 0, 0xFEEDFACE);
        return bytes;
    }

    /** A Mach-O header for the given CPU type. */
    private static byte[] machOImage(long cpuType) {
        var bytes = new byte[32];
        putLe32(bytes, 0, 0xFEEDFACF);
        putLe32(bytes, 4, (int) cpuType);
        putLe32(bytes, 12, 6);
        return bytes;
    }

    private static void putLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void putLe32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
