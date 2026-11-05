package org.veltismc.nativeengine;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
     * Host platforms with an installation layout and a supported JVM.
     *
     * <p>OS and architecture are detected independently in {@link #platformKey}
     * and combined here. A key in this set means the loader will look for and
     * validate a library; it does not by itself claim that this repository has
     * built and run the native library on that platform. The README's support
     * table is the authority for that. Other operating systems (FreeBSD, the
     * BSDs, RISC-V Linux) keep the Java fallback and receive an explicit
     * "unsupported platform" diagnostic instead of a confusing loader error.
     */
    private static final Set<String> RECOGNIZED_PLATFORMS = Set.of(
            "windows-x86_64",
            "windows-aarch64",
            "linux-x86_64",
            "linux-aarch64",
            "macos-x86_64",
            "macos-aarch64");

    /** The same keys in a stable order, because a log line is read by a human. */
    private static final String RECOGNIZED_PLATFORM_LIST =
            String.join(", ", RECOGNIZED_PLATFORMS.stream().sorted().toList());

    private static final String LIBRARY_BASE_NAME = "veltis_native";

    /**
     * The window a returned C string is reinterpreted to before reading it. The
     * public header promises a NUL terminator within 256 bytes; this is deliberately
     * larger, because {@code getString} throws when the terminator falls outside
     * the window, and a library that lengthened its messages must not turn a
     * diagnostic read into a load failure.
     */
    private static final long STRING_READ_LIMIT = 4096L;

    /** {@code VELTIS_NATIVE_OK} in the public header; the only status queried here. */
    private static final int VELTIS_NATIVE_OK = 0;

    /**
     * The largest prefix any probe reads. ELF and Mach-O keep their headers at
     * offset zero, so this covers them; a PE header position is a variable, so it
     * is sought to rather than assumed. No probe ever reads a whole library.
     */
    private static final int HEADER_BYTES = 4096;

    /** The DOS header is 64 bytes and e_lfanew is its last field, at 0x3C. */
    private static final int DOS_HEADER_BYTES = 0x40;

    /** A PE header cannot begin inside the DOS header that points at it. */
    private static final int MINIMUM_PE_HEADER_OFFSET = DOS_HEADER_BYTES;

    /** The PE signature (4 bytes) plus the COFF file header (20 bytes). */
    private static final int PE_HEADER_BYTES = 24;

    /** mach_header and mach_header_64 are both eight fields wide. */
    private static final int MACHO_HEADER_BYTES = 32;

    /**
     * Apple's own limit on the slice count of a fat container. A larger value is
     * either corruption or a file that merely begins with the bytes CA FE BA BE:
     * a Java class file does, and its version field lands exactly where nfat_arch
     * is read.
     */
    private static final int MAX_FAT_ARCHITECTURES = 16;

    private static final int PE_OFFSET_FIELD = 0x3C;

    private static final int PE_MACHINE_X86_64 = 0x8664;
    private static final int PE_MACHINE_ARM64 = 0xAA64;
    private static final int PE_MACHINE_X86 = 0x014C;

    private static final int ELF_MACHINE_X86_64 = 0x3E;
    private static final int ELF_MACHINE_AARCH64 = 0xB7;
    private static final int ELF_MACHINE_RISCV = 0xF3;

    private static final long MACHO_CPU_X86_64 = 0x01000007L;
    private static final long MACHO_CPU_ARM64 = 0x0100000CL;
    private static final long MACHO_MAGIC = 0xFEEDFACEL;
    private static final long MACHO_MAGIC_64 = 0xFEEDFACFL;
    private static final long MACHO_FAT_MAGIC = 0xCAFEBABEL;

    private static final FunctionDescriptor INT_RESULT = FunctionDescriptor.of(ValueLayout.JAVA_INT);
    private static final FunctionDescriptor POINTER_RESULT = FunctionDescriptor.of(ValueLayout.ADDRESS);
    private static final FunctionDescriptor VOID_RESULT = FunctionDescriptor.ofVoid();
    private static final FunctionDescriptor STATUS_TEXT =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    // size_t is JAVA_LONG, not JAVA_INT: the element counts cross the ABI as the
    // platform's native pointer-width integer. That mapping is only correct on a
    // 64-bit target, which is why probeBinary reports the architecture and
    // checkBinary rejects every file whose machine type is not x86_64 or aarch64
    // before this descriptor is ever invoked.
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

    /** The binary container and architectures found in a candidate file. */
    record BinaryInfo(String format, Set<String> architectures) {
        String describe() {
            return format + " " + String.join("/", architectures);
        }
    }

    /**
     * A successfully validated and bound library. Closing it shuts the native
     * engine down and releases the FFM arena; it is safe to close twice.
     *
     * <p>The read/write lock is what makes {@link #close()} safe while another
     * thread is inside a downcall. Readers hold the lock for the whole native
     * call; close takes the write lock, so it cannot release the arena or shut
     * the engine down until every in-flight call has returned. Closing from the
     * same thread inside a call would deadlock, but a native call cannot re-enter
     * Java here: none of the exports take a callback.
     */
    public static final class LoadedLibrary implements AutoCloseable {
        private final Arena arena;
        private final Bindings bindings;
        private final NativeStatus status;
        private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
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
            return underReadLock(bindings::initialize);
        }

        public boolean isInitialized() {
            return underReadLock(() -> bindings.isInitialized() != 0);
        }

        public int selfTest() {
            return underReadLock(bindings::selfTest);
        }

        public int capabilitiesMask() {
            return underReadLock(bindings::capabilities);
        }

        public String lastError() {
            return underReadLock(bindings::lastError);
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
            return underReadLock(() -> {
                requireExtent(input, inputLength, "input");
                requireExtent(output, outputLength, "output");
                return bindings.transform(input, inputLength, salt, output, outputLength);
            });
        }

        @Override
        public void close() {
            var lock = lifecycle.writeLock();
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    bindings.shutdown();
                } finally {
                    arena.close();
                }
            } finally {
                lock.unlock();
            }
        }

        private <T> T underReadLock(Operation<T> operation) {
            var lock = lifecycle.readLock();
            lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("native library has been closed");
                }
                return operation.run();
            } finally {
                lock.unlock();
            }
        }

        @FunctionalInterface
        private interface Operation<T> {
            T run();
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
                    "unsupported platform '" + platform
                            + "'; this integration supports " + RECOGNIZED_PLATFORM_LIST
                            + " and the Java fallback remains active");
        }

        Optional<Path> located;
        try {
            located = locate(config);
        } catch (RuntimeException invalidPath) {
            return failure(false, 0, "0.0.0",
                    "invalid native library path '" + config.libraryPath() + "': "
                            + invalidPath.getMessage());
        }
        if (located.isEmpty()) {
            var searched = new ArrayList<String>();
            for (var candidate : candidatePaths(platform, libraryFileName(platform))) {
                searched.add(candidate.toString());
            }
            var reason = config.hasLibraryPath()
                    ? "configured native library not found: " + config.libraryPath()
                    : "no native library for " + platform + " (looked in "
                            + String.join(", ", searched) + ")";
            return failure(false, 0, "0.0.0", reason);
        }

        var path = located.get();
        var binaryError = checkBinary(path, platform);
        if (binaryError != null) {
            // The file exists, so this is a "found but unusable" outcome, not a
            // "not installed" one. An operator needs that distinction.
            return failure(true, 0, "0.0.0", binaryError);
        }

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
                        path + ": " + checkCompatibility(
                                abiVersion, SUPPORTED_ENGINE_MAJOR, MINIMUM_ENGINE_MINOR));
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
                return failure(true, abiVersion, engineVersion, path + ": " + incompatibility);
            }

            // The one export no call path in this class uses yet still has to
            // resolve and answer: a library that dropped it is not this ABI, and
            // finding that out on first use would raise UnsatisfiedLinkError from
            // inside a running server instead of falling back at startup. It is
            // checked after compatibility so an incompatible library is reported
            // as the incompatibility it actually is.
            var statusText = (MemorySegment) downcall(
                    lookup, linker, "veltis_native_status_string", STATUS_TEXT).invokeExact(VELTIS_NATIVE_OK);
            if (statusText.equals(MemorySegment.NULL)
                    || statusText.reinterpret(STRING_READ_LIMIT).getString(0).isBlank()) {
                throw new IllegalStateException("veltis_native_status_string returned no text");
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
                    "could not use native library " + path + ": " + detail);
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
        var candidates = candidatePaths(platform, fileName);

        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    /**
     * The locations searched when no library path is configured, in order. The
     * "not found" report is built from this same list so that the message a
     * debugging operator reads cannot describe a search the loader did not run.
     */
    static List<Path> candidatePaths(String platform, String fileName) {
        var candidates = new ArrayList<Path>();

        var home = System.getProperty("veltismc.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home).resolve("native").resolve(platform).resolve(fileName));
        }

        var workingDirectory = Path.of("").toAbsolutePath();
        candidates.add(workingDirectory.resolve("native").resolve(platform).resolve(fileName));
        candidates.add(workingDirectory.resolve("build").resolve("native").resolve(platform).resolve(fileName));

        return List.copyOf(candidates);
    }

    /**
     * Validates the binary container and architecture before the operating
     * system's loader is asked to map it.
     *
     * <p>A wrong-architecture or wrong-format file normally fails deep inside
     * {@code SymbolLookup.libraryLookup} with an error that names the OS but not
     * the mismatch. Reading the header first lets the loader say exactly what the
     * file is and what this JVM needs. Returns null when the file is usable.
     */
    static String checkBinary(Path path, String platform) {
        BinaryInfo info;
        try {
            info = probeBinary(path);
        } catch (IOException | RuntimeException unreadable) {
            return "native library " + path + " is not usable: " + unreadable.getMessage();
        }

        var separator = platform.indexOf('-');
        var os = separator < 0 ? platform : platform.substring(0, separator);
        var arch = separator < 0 ? "" : platform.substring(separator + 1);
        var expectedFormat = switch (os) {
            case "windows" -> "PE";
            case "macos" -> "Mach-O";
            case "linux" -> "ELF";
            default -> null;
        };

        if (expectedFormat != null && !info.format().equals(expectedFormat)) {
            return "native library " + path + " is a " + info.describe()
                    + " binary, but " + platform + " loads " + expectedFormat;
        }
        if (!info.architectures().contains(arch)) {
            return "native library " + path + " is a " + info.describe()
                    + " binary, but this JVM is " + platform;
        }
        return null;
    }

    /** Reads the container/architecture headers of PE, ELF and Mach-O files. */
    static BinaryInfo probeBinary(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var size = channel.size();
            var header = readAt(channel, 0, (int) Math.min(size, (long) HEADER_BYTES));
            if (header.length >= 2 && (header[0] & 0xFF) == 'M' && (header[1] & 0xFF) == 'Z') {
                return probePe(channel, size, header);
            }
            if (header.length >= 4
                    && (header[0] & 0xFF) == 0x7F
                    && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                return probeElf(header);
            }
            if (header.length >= 8) {
                var littleEndian = u32le(header, 0);
                var bigEndian = u32be(header, 0);
                if (littleEndian == MACHO_MAGIC || littleEndian == MACHO_MAGIC_64) {
                    return probeMachO(header, true, littleEndian == MACHO_MAGIC_64);
                }
                if (bigEndian == MACHO_MAGIC || bigEndian == MACHO_MAGIC_64) {
                    return probeMachO(header, false, bigEndian == MACHO_MAGIC_64);
                }
                if (bigEndian == MACHO_FAT_MAGIC || littleEndian == MACHO_FAT_MAGIC) {
                    return probeFat(header, bigEndian == MACHO_FAT_MAGIC);
                }
            }
            throw new IOException("not a recognised PE, ELF or Mach-O shared library");
        }
    }

    /**
     * Reads up to {@code length} bytes starting at {@code offset}, stopping at
     * end of file. The returned array is short exactly when the file is, which is
     * what every caller's truncation check is written against.
     */
    private static byte[] readAt(FileChannel channel, long offset, int length) throws IOException {
        var bytes = new byte[length];
        var buffer = ByteBuffer.wrap(bytes);
        var position = offset;
        while (buffer.hasRemaining()) {
            var read = channel.read(buffer, position);
            if (read <= 0) {
                break;
            }
            position += read;
        }
        return buffer.position() == length ? bytes : Arrays.copyOf(bytes, buffer.position());
    }

    /*
     * Random access, because e_lfanew is a field the linker chooses rather than a
     * constant: a legitimate binary can carry its PE header well past the prefix
     * this method already read. Every offset is kept in long arithmetic and
     * checked against the real file size, so a hostile value can neither wrap an
     * int cast nor send the probe past end of file.
     */
    private static BinaryInfo probePe(FileChannel channel, long fileSize, byte[] header) throws IOException {
        if (header.length < DOS_HEADER_BYTES) {
            throw new IOException("truncated DOS header: the file is " + header.length
                    + " bytes, but an MZ header needs at least " + DOS_HEADER_BYTES);
        }
        var peOffset = u32le(header, PE_OFFSET_FIELD);
        if (peOffset < MINIMUM_PE_HEADER_OFFSET) {
            throw new IOException("invalid PE header offset " + peOffset
                    + " at file position " + PE_OFFSET_FIELD + "; it must be at least "
                    + MINIMUM_PE_HEADER_OFFSET);
        }
        if (peOffset >= fileSize) {
            throw new IOException("PE header offset " + peOffset + " points past the end of a "
                    + fileSize + "-byte file");
        }
        var available = fileSize - peOffset;
        if (available < 4) {
            throw new IOException("truncated PE signature at offset " + peOffset + ": only "
                    + available + " of 4 signature bytes are present");
        }
        if (available < PE_HEADER_BYTES) {
            throw new IOException("truncated COFF header at offset " + peOffset + ": only "
                    + available + " of " + PE_HEADER_BYTES
                    + " bytes are present, so the machine type is unreadable");
        }

        var peHeader = readAt(channel, peOffset, PE_HEADER_BYTES);
        if (peHeader.length < PE_HEADER_BYTES) {
            throw new IOException("PE header at offset " + peOffset + " shrank while it was being "
                    + "read: got " + peHeader.length + " of " + PE_HEADER_BYTES + " bytes");
        }
        if (peHeader[0] != 'P' || peHeader[1] != 'E' || peHeader[2] != 0 || peHeader[3] != 0) {
            throw new IOException(String.format(
                    "missing PE signature at offset %d: found %02X %02X %02X %02X",
                    peOffset,
                    peHeader[0] & 0xFF, peHeader[1] & 0xFF, peHeader[2] & 0xFF, peHeader[3] & 0xFF));
        }
        var machine = u16le(peHeader, 4);
        var architecture = switch (machine) {
            case PE_MACHINE_X86_64 -> "x86_64";
            case PE_MACHINE_ARM64 -> "aarch64";
            case PE_MACHINE_X86 -> "x86";
            default -> "unknown-0x" + Integer.toHexString(machine);
        };
        return new BinaryInfo("PE", Set.of(architecture));
    }

    private static BinaryInfo probeElf(byte[] header) throws IOException {
        if (header.length < 20) {
            throw new IOException("truncated ELF header");
        }
        var byteOrder = header[5] & 0xFF;
        if (byteOrder != 1 && byteOrder != 2) {
            throw new IOException("unsupported ELF byte order " + byteOrder);
        }
        var machine = byteOrder == 1 ? u16le(header, 18) : u16be(header, 18);
        var architecture = switch (machine) {
            case ELF_MACHINE_X86_64 -> "x86_64";
            case ELF_MACHINE_AARCH64 -> "aarch64";
            case ELF_MACHINE_RISCV -> "riscv64";
            default -> "unknown-0x" + Integer.toHexString(machine);
        };
        return new BinaryInfo("ELF", Set.of(architecture));
    }

    private static BinaryInfo probeMachO(byte[] header, boolean littleEndian, boolean is64Bit)
            throws IOException {
        if (header.length < MACHO_HEADER_BYTES) {
            throw new IOException("truncated Mach-O header: read " + header.length
                    + " of " + MACHO_HEADER_BYTES + " bytes");
        }
        var cpu = littleEndian ? u32le(header, 4) : u32be(header, 4);
        if (!is64Bit && requires64BitSlice(cpu)) {
            throw new IOException(String.format(
                    "Mach-O header is 32-bit but declares CPU type 0x%X, which exists only as a 64-bit ABI",
                    cpu));
        }
        return new BinaryInfo("Mach-O", Set.of(machOArchitecture(cpu)));
    }

    private static BinaryInfo probeFat(byte[] header, boolean bigEndian) throws IOException {
        if (header.length < 8) {
            throw new IOException("truncated universal Mach-O header");
        }
        var count = bigEndian ? u32be(header, 4) : u32le(header, 4);
        if (count <= 0 || count > MAX_FAT_ARCHITECTURES) {
            throw new IOException("invalid universal Mach-O architecture count " + count
                    + "; a fat container holds between 1 and " + MAX_FAT_ARCHITECTURES + " slices");
        }
        var architectures = new LinkedHashSet<String>();
        for (var i = 0; i < count; i++) {
            var offset = 8 + i * 20;
            if (offset + 4 > header.length) {
                throw new IOException("truncated universal Mach-O architecture table: slice "
                        + i + " of " + count + " is not readable within " + header.length + " bytes");
            }
            var cpu = bigEndian ? u32be(header, offset) : u32le(header, offset);
            architectures.add(machOArchitecture(cpu));
        }
        return new BinaryInfo("Mach-O", architectures);
    }

    /** CPU types with no 32-bit ABI, so a 32-bit magic naming one is corruption. */
    private static boolean requires64BitSlice(long cpu) {
        return cpu == MACHO_CPU_X86_64 || cpu == MACHO_CPU_ARM64;
    }

    private static String machOArchitecture(long cpu) {
        if (cpu == MACHO_CPU_X86_64) {
            return "x86_64";
        }
        if (cpu == MACHO_CPU_ARM64) {
            return "aarch64";
        }
        return "unknown-0x" + Long.toHexString(cpu);
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int u16be(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long u32le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static long u32be(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL) << 24)
                | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }

    /**
     * The repository's platform spelling. It matches the Gradle output directory
     * and the installation directory so an operator has one name to match.
     */
    public static String platformKey() {
        return platformKey(System.getProperty("os.name", "unknown"), System.getProperty("os.arch", "unknown"));
    }

    /** OS and architecture are normalized independently of each other. */
    static String platformKey(String osName, String archName) {
        return normalizeOs(osName) + "-" + normalizeArch(archName);
    }

    /**
     * Whether the loader has an installation layout for this platform key.
     *
     * <p>The distribution asks this before it looks for an artifact, so an
     * operating system the loader would refuse (FreeBSD, a 32-bit host, an
     * architecture outside the six keys) never triggers a download decision —
     * the Java fallback is the whole answer for it.
     */
    static boolean isRecognizedPlatform(String platform) {
        return RECOGNIZED_PLATFORMS.contains(platform);
    }

    /** The recognized keys in one sorted list, for a diagnostic message. */
    static String recognizedPlatformList() {
        return RECOGNIZED_PLATFORM_LIST;
    }

    private static String normalizeOs(String osName) {
        var lower = osName.toLowerCase(Locale.ROOT);
        // Order matters: "darwin" contains "win", so the macOS spellings must be
        // tested before the Windows ones. The Windows check also requires the
        // full word or a leading "win" so it cannot match a substring.
        if (lower.contains("darwin") || lower.contains("mac") || lower.contains("osx")) {
            return "macos";
        }
        if (lower.contains("windows") || lower.startsWith("win")) {
            return "windows";
        }
        if (lower.contains("linux")) {
            return "linux";
        }
        return sanitize(lower);
    }

    private static String normalizeArch(String archName) {
        return switch (archName.toLowerCase(Locale.ROOT)) {
            case "x86_64", "x86-64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "riscv64", "riscv" -> "riscv64";
            case "ppc64le" -> "ppc64le";
            default -> sanitize(archName.toLowerCase(Locale.ROOT));
        };
    }

    /** The file name the operating system's dynamic loader expects. */
    public static String libraryFileName() {
        return libraryFileName(platformKey());
    }

    /** File naming is driven by the operating system part of the key only. */
    static String libraryFileName(String platform) {
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
