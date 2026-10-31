package org.veltismc.nativeengine;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Locates, loads and validates the optional native library through the Java
 * Foreign Function &amp; Memory API.
 *
 * <p>Loading is deliberately a value-returning operation: a missing file, an
 * unsupported platform, a missing symbol and an incompatible ABI are all normal
 * outcomes that the caller reports and falls back from. Nothing here calls
 * {@code System.exit}, throws during server startup, or keeps a half-loaded
 * library alive.
 */
public final class NativeLoader {

    /** The only ABI this Java side understands. */
    public static final int EXPECTED_ABI_VERSION = 1;

    /** Engine major version required by this integration layer. */
    public static final int SUPPORTED_ENGINE_MAJOR = 1;

    /** Oldest engine minor version this layer accepts. Newer minors are compatible. */
    public static final int MINIMUM_ENGINE_MINOR = 0;

    /**
     * Platform keys with an installation layout. Only Windows x86-64 and Linux
     * x86-64 have been built and tested by this repository so far; the other
     * keys are accepted so a future build can ship without a loader change.
     */
    private static final Set<String> RECOGNIZED_PLATFORMS = Set.of(
            "windows-x86_64",
            "windows-aarch64",
            "linux-x86_64",
            "linux-aarch64",
            "macos-x86_64",
            "macos-aarch64");

    private static final String LIBRARY_BASE_NAME = "veltis_native";
    private static final long STRING_READ_LIMIT = 4096L;

    private static final FunctionDescriptor INT_RESULT = FunctionDescriptor.of(ValueLayout.JAVA_INT);
    private static final FunctionDescriptor POINTER_RESULT = FunctionDescriptor.of(ValueLayout.ADDRESS);
    private static final FunctionDescriptor VOID_RESULT = FunctionDescriptor.ofVoid();
    private static final FunctionDescriptor TRANSFORM = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG);

    private NativeLoader() {}

    /** The result of one load attempt. {@code library} is null on every failure. */
    public record LoadResult(LoadedLibrary library, NativeStatus status) {}

    /**
     * A successfully validated and bound library. Closing it shuts the native
     * engine down and releases the FFM arena; it is safe to close twice.
     */
    public static final class LoadedLibrary implements AutoCloseable {
        private final Arena arena;
        private final Bindings bindings;
        private final NativeStatus status;
        private volatile boolean closed;

        private LoadedLibrary(Arena arena, Bindings bindings, NativeStatus status) {
            this.arena = arena;
            this.bindings = bindings;
            this.status = status;
        }

        public NativeStatus status() {
            return status;
        }

        public int initialize() {
            ensureOpen();
            return bindings.initialize();
        }

        public boolean isInitialized() {
            ensureOpen();
            return bindings.isInitialized() != 0;
        }

        public int selfTest() {
            ensureOpen();
            return bindings.selfTest();
        }

        public int capabilitiesMask() {
            ensureOpen();
            return bindings.capabilities();
        }

        public String lastError() {
            ensureOpen();
            return bindings.lastError();
        }

        /**
         * Calls the bulk transform. Both segments must be alive and large enough
         * for the supplied element counts; the caller owns their lifetime.
         *
         * <p>The extent checks here are not redundant with the native ones: the C
         * side receives only raw pointers and element counts, so it cannot detect a
         * segment that is shorter than the length the caller declares. Validating
         * the actual segment sizes in Java is what keeps a caller mistake from
         * becoming an out-of-bounds write in native memory.
         */
        public int transform(MemorySegment input, long inputLength, int salt, MemorySegment output, long outputLength) {
            ensureOpen();
            requireExtent(input, inputLength, "input");
            requireExtent(output, outputLength, "output");
            return bindings.transform(input, inputLength, salt, output, outputLength);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    bindings.shutdown();
                } finally {
                    arena.close();
                }
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("native library has been closed");
            }
        }

        private static void requireExtent(MemorySegment segment, long elements, String name) {
            if (elements < 0) {
                throw new IllegalArgumentException(name + " length must be non-negative, got " + elements);
            }
            if (segment == null) {
                throw new IllegalArgumentException(name + " segment is null");
            }
            long required;
            try {
                required = Math.multiplyExact(elements, ValueLayout.JAVA_INT.byteSize());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        name + " length is too large: " + elements + " int32 elements", overflow);
            }
            if (segment.byteSize() < required) {
                throw new IllegalArgumentException(
                        name + " segment is " + segment.byteSize() + " bytes, but " + elements
                                + " int32 elements need " + required);
            }
        }
    }

    /**
     * Attempts to load a compatible library. Never throws for an expected
     * failure; the returned status explains why native support is unavailable.
     */
    public static LoadResult tryLoad(NativeConfig config) {
        var platform = platformKey();
        if (!RECOGNIZED_PLATFORMS.contains(platform)) {
            return failure(false, 0, "0.0.0",
                    "unsupported platform '" + platform + "' (recognized: " + RECOGNIZED_PLATFORMS + ")");
        }

        var located = locate(config);
        if (located.isEmpty()) {
            var reason = config.hasLibraryPath()
                    ? "configured native library not found: " + config.libraryPath()
                    : "no native library for " + platform + " (looked in native/" + platform
                            + " and build/native/" + platform + ")";
            return failure(false, 0, "0.0.0", reason);
        }

        var path = located.get();
        var arena = Arena.ofShared();
        var opened = false;
        var abiVersion = 0;
        var engineVersion = "0.0.0";
        try {
            var lookup = SymbolLookup.libraryLookup(path, arena);
            opened = true;
            var linker = Linker.nativeLinker();

            // The ABI is checked before any version symbol is resolved. A library
            // built for a different ABI may predate those exports, so asking for
            // them first would report a missing symbol instead of the real,
            // actionable incompatibility.
            abiVersion = invokeInt(downcall(lookup, linker, "veltis_native_abi_version", INT_RESULT));
            if (abiVersion != EXPECTED_ABI_VERSION) {
                closeQuietly(arena);
                return failure(true, abiVersion, engineVersion,
                        checkCompatibility(abiVersion, SUPPORTED_ENGINE_MAJOR, MINIMUM_ENGINE_MINOR));
            }

            var major = invokeInt(downcall(lookup, linker, "veltis_native_version_major", INT_RESULT));
            var minor = invokeInt(downcall(lookup, linker, "veltis_native_version_minor", INT_RESULT));
            // Resolved even though compatibility only needs major/minor: every
            // symbol the ABI promises must exist before the library is accepted.
            invokeInt(downcall(lookup, linker, "veltis_native_version_patch", INT_RESULT));
            engineVersion = readString(downcall(lookup, linker, "veltis_native_version_string", POINTER_RESULT));

            var incompatibility = checkCompatibility(abiVersion, major, minor);
            if (incompatibility != null) {
                closeQuietly(arena);
                return failure(true, abiVersion, engineVersion, incompatibility);
            }

            var bindings = Bindings.bind(lookup, linker);
            var status = NativeStatus.available(
                    abiVersion, engineVersion, NativeCapabilities.fromMask(bindings.capabilities()));
            return new LoadResult(new LoadedLibrary(arena, bindings, status), status);
        } catch (Throwable t) {
            closeQuietly(arena);
            var detail = t.getMessage() == null || t.getMessage().isBlank()
                    ? t.getClass().getSimpleName()
                    : t.getMessage();
            return failure(opened, abiVersion, engineVersion,
                    "could not use native library " + path.getFileName() + ": " + detail);
        }
    }

    /**
     * Finds the library without loading it. An explicit configured path may name
     * a file or the directory that contains the platform's library.
     */
    public static Optional<Path> locate(NativeConfig config) {
        if (config.hasLibraryPath()) {
            var configured = Path.of(config.libraryPath());
            if (Files.isDirectory(configured)) {
                configured = configured.resolve(libraryFileName());
            }
            return Files.isRegularFile(configured)
                    ? Optional.of(configured.toAbsolutePath().normalize())
                    : Optional.empty();
        }

        var platform = platformKey();
        var fileName = libraryFileName(platform);
        var candidates = new ArrayList<Path>();

        var home = System.getProperty("veltismc.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home).resolve("native").resolve(platform).resolve(fileName));
        }

        var workingDirectory = Path.of("").toAbsolutePath();
        candidates.add(workingDirectory.resolve("native").resolve(platform).resolve(fileName));
        candidates.add(workingDirectory.resolve("build").resolve("native").resolve(platform).resolve(fileName));

        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /**
     * The repository's platform spelling. It matches the Gradle output directory
     * and the installation directory so an operator has one name to match.
     */
    public static String platformKey() {
        var osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        var archName = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        var os = osName.contains("win") ? "windows"
                : osName.contains("mac") || osName.contains("darwin") ? "macos"
                : osName.contains("linux") ? "linux"
                : sanitize(osName);
        var arch = switch (archName) {
            case "x86_64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> sanitize(archName);
        };
        return os + "-" + arch;
    }

    /** The file name the operating system's dynamic loader expects. */
    public static String libraryFileName() {
        return libraryFileName(platformKey());
    }

    private static String libraryFileName(String platform) {
        if (platform.startsWith("windows")) {
            return LIBRARY_BASE_NAME + ".dll";
        }
        if (platform.startsWith("macos")) {
            return "lib" + LIBRARY_BASE_NAME + ".dylib";
        }
        return "lib" + LIBRARY_BASE_NAME + ".so";
    }

    /**
     * Compatibility rule, isolated so it can be tested without a compiler:
     * the ABI is exact, the engine major must match, and a newer minor is
     * accepted because the ABI is additive. Returns null when compatible.
     */
    static String checkCompatibility(int abiVersion, int engineMajor, int engineMinor) {
        if (abiVersion != EXPECTED_ABI_VERSION) {
            return "ABI mismatch: native library reports " + abiVersion
                    + ", this server expects " + EXPECTED_ABI_VERSION;
        }
        if (engineMajor != SUPPORTED_ENGINE_MAJOR) {
            return "engine version mismatch: native library reports major " + engineMajor
                    + ", this server supports " + SUPPORTED_ENGINE_MAJOR + ".x";
        }
        if (engineMinor < MINIMUM_ENGINE_MINOR) {
            return "engine version too old: native library reports minor " + engineMinor
                    + ", this server requires at least " + MINIMUM_ENGINE_MINOR;
        }
        return null;
    }

    private static LoadResult failure(boolean loaded, int abiVersion, String engineVersion, String reason) {
        return new LoadResult(null, NativeStatus.unavailable(loaded, abiVersion, engineVersion, reason));
    }

    private static String sanitize(String value) {
        var sanitized = value.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }

    private static void closeQuietly(Arena arena) {
        try {
            arena.close();
        } catch (RuntimeException ignored) {
            // The failure being reported is the useful one.
        }
    }

    private static MethodHandle downcall(
            SymbolLookup lookup,
            Linker linker,
            String symbol,
            FunctionDescriptor descriptor) {
        var address = lookup.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("missing native export: " + symbol));
        return linker.downcallHandle(address, descriptor);
    }

    private static int invokeInt(MethodHandle handle) throws Throwable {
        return (int) handle.invokeExact();
    }

    private static String readString(MethodHandle handle) throws Throwable {
        var pointer = (MemorySegment) handle.invokeExact();
        if (pointer.equals(MemorySegment.NULL)) {
            return "";
        }
        return pointer.reinterpret(STRING_READ_LIMIT).getString(0);
    }

    private static final class Bindings {
        private final MethodHandle initialize;
        private final MethodHandle isInitialized;
        private final MethodHandle shutdown;
        private final MethodHandle capabilities;
        private final MethodHandle selfTest;
        private final MethodHandle transform;
        private final MethodHandle lastError;

        private Bindings(
                MethodHandle initialize,
                MethodHandle isInitialized,
                MethodHandle shutdown,
                MethodHandle capabilities,
                MethodHandle selfTest,
                MethodHandle transform,
                MethodHandle lastError) {
            this.initialize = initialize;
            this.isInitialized = isInitialized;
            this.shutdown = shutdown;
            this.capabilities = capabilities;
            this.selfTest = selfTest;
            this.transform = transform;
            this.lastError = lastError;
        }

        static Bindings bind(SymbolLookup lookup, Linker linker) {
            return new Bindings(
                    downcall(lookup, linker, "veltis_native_init", INT_RESULT),
                    downcall(lookup, linker, "veltis_native_is_initialized", INT_RESULT),
                    downcall(lookup, linker, "veltis_native_shutdown", VOID_RESULT),
                    downcall(lookup, linker, "veltis_native_capabilities", INT_RESULT),
                    downcall(lookup, linker, "veltis_native_self_test", INT_RESULT),
                    downcall(lookup, linker, "veltis_native_transform_i32", TRANSFORM),
                    downcall(lookup, linker, "veltis_native_last_error", POINTER_RESULT));
        }

        int initialize() {
            try {
                return (int) initialize.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("native init failed", t);
            }
        }

        int isInitialized() {
            try {
                return (int) isInitialized.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("native is_initialized failed", t);
            }
        }

        void shutdown() {
            try {
                shutdown.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("native shutdown failed", t);
            }
        }

        int capabilities() {
            try {
                return (int) capabilities.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("native capability query failed", t);
            }
        }

        int selfTest() {
            try {
                return (int) selfTest.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException("native self-test failed", t);
            }
        }

        int transform(MemorySegment input, long inputLength, int salt, MemorySegment output, long outputLength) {
            try {
                return (int) transform.invokeExact(input, inputLength, salt, output, outputLength);
            } catch (Throwable t) {
                throw new IllegalStateException("native transform failed", t);
            }
        }

        String lastError() {
            try {
                var pointer = (MemorySegment) lastError.invokeExact();
                if (pointer.equals(MemorySegment.NULL)) {
                    return "";
                }
                return pointer.reinterpret(STRING_READ_LIMIT).getString(0);
            } catch (Throwable t) {
                return t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }
}
