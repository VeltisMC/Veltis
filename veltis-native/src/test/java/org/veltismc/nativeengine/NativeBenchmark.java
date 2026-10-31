package org.veltismc.nativeengine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Repeatable comparison of the Java fallback, an allocation-free Java baseline
 * and the native engine across batch sizes.
 *
 * <p>This is a measurement tool, not a test. It is run by
 * {@code :veltis-native:benchmarkNative} so a normal build never pays for it. It
 * reports throughput, per-call latency (average, p50, p95, p99), thread
 * allocation, heap use, process virtual memory and a cheap-downcall estimate of
 * FFM transition overhead, and it verifies every implementation against the Java
 * contract before timing it.
 *
 * <p>Interpretation notes:
 * <ul>
 *   <li>Every implementation is warmed up with the same inputs and measured for
 *       several rounds; percentiles are over individual calls, so at very small
 *       batch sizes {@code System.nanoTime} overhead is part of the number. The
 *       timer overhead is measured and printed separately.</li>
 *   <li>"java" is {@link NativeEngine#javaTransform}, which allocates a result
 *       array, exactly like the server's fallback path. "java-optimized" writes
 *       into a caller-supplied array, which is the fastest shape a pure-Java
 *       consumer can use.</li>
 *   <li>"native-engine" is the production integration path
 *       ({@link NativeEngine#transform}): confined arena, copy in, call, copy
 *       out. "native-reusable" keeps one shared arena and both segments alive,
 *       so it isolates compute plus the two unavoidable copies from per-call
 *       arena setup.</li>
 *   <li>Thread allocation counts Java heap only. Native segment memory is
 *       described analytically because the arena API does not expose a
 *       per-allocation counter.</li>
 * </ul>
 */
public final class NativeBenchmark {

    private static final int[] SIZES = {
            1, 4, 16, 64, 256, 1_024, 4_096, 16_384, 65_536, 262_144, 1_048_576,
    };
    private static final int ROUNDS = 3;
    private static final int SALT = -987_654_321;
    private static final long INPUT_SEED = 0x9E3779B97F4A7C15L;

    private NativeBenchmark() {}

    public static void main(String[] args) throws Exception {
        var library = resolveLibrary(args);
        var loaded = library == null ? null : load(library);
        if (loaded == null) {
            System.out.println("native library: absent; measuring Java implementations only");
        } else {
            System.out.println("native library: " + library);
        }

        var engine = new NativeEngine();
        var engineStatus = engine.initialize(new NativeConfig(
                true, true, true, false, library == null ? "" : library.toString()));
        var nativeAvailable = engineStatus.available();
        System.out.println("native engine: " + engineStatus.summary());
        System.out.println("java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")"
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " processors=" + Runtime.getRuntime().availableProcessors());
        System.out.println();

        var timerOverhead = timerOverheadNanos();
        System.out.printf(Locale.ROOT, "timer overhead: %.1f ns per System.nanoTime call"
                + " (each timed call uses two; small-batch latencies are quantized)%n", timerOverhead);
        System.out.println();

        var ffmOverhead = nativeAvailable && loaded != null ? ffmDowncallNanos(loaded) : Double.NaN;

        printHeader();

        var heapBefore = usedHeap();
        var committedBefore = committedVirtualMemory();
        long totalAllocated = 0;
        double nativeThreshold = Double.NaN;

        try {
            for (var size : SIZES) {
                var input = input(size);
                var expected = NativeEngine.javaTransform(input, SALT);

                var results = new java.util.ArrayList<Measurement>();
                results.add(measure("java", size, input, (values, salt) -> NativeEngine.javaTransform(values, salt), expected));

                var optimizedOutput = new int[size];
                results.add(measure("java-optimized", size, input,
                        (values, salt) -> optimizedTransform(values, salt, optimizedOutput), expected));

                if (nativeAvailable && loaded != null) {
                    results.add(measure("native-engine", size, input,
                            (values, salt) -> engine.transform(values, salt), expected));

                    try (var reusable = new NativeReusable(loaded, size)) {
                        results.add(measure("native-reusable", size, input, reusable::apply, expected));
                    }
                }

                for (var result : results) {
                    print(result);
                    totalAllocated += result.heapBytesPerCall() * result.calls();
                }

                if (nativeAvailable && loaded != null) {
                    var javaThroughput = find(results, "java-optimized").elementsPerSecond();
                    var nativeThroughput = find(results, "native-engine").elementsPerSecond();
                    if (Double.isNaN(nativeThreshold) && nativeThroughput >= javaThroughput) {
                        nativeThreshold = size;
                    }
                }
                System.out.println();
            }
        } finally {
            engine.shutdown();
            if (loaded != null) {
                loaded.close();
            }
        }

        var heapAfter = usedHeap();
        var committedAfter = committedVirtualMemory();

        System.out.println("resources");
        System.out.printf(Locale.ROOT, "  heap used before/after: %.1f MiB / %.1f MiB%n",
                mib(heapBefore), mib(heapAfter));
        System.out.printf(Locale.ROOT, "  process committed virtual memory before/after: %.1f MiB / %.1f MiB%n",
                mib(committedBefore), mib(committedAfter));
        System.out.printf(Locale.ROOT, "  measured thread allocation total: %.1f MiB%n", mib(totalAllocated));
        System.out.println("  native segment memory: arena-scoped; native-engine allocates input+output"
                + " (8 bytes per element) per call, native-reusable pre-allocates once per batch size");
        System.out.println();

        if (nativeAvailable && loaded != null) {
            System.out.printf(Locale.ROOT,
                    "cheap FFM downcall estimate (veltis_native_capabilities): %.1f ns per call%n", ffmOverhead);
            if (Double.isNaN(nativeThreshold)) {
                System.out.println("threshold: native-engine did not beat the allocation-free Java baseline"
                        + " at any measured batch size");
            } else {
                System.out.printf(Locale.ROOT,
                        "threshold: native-engine first matches or beats java-optimized throughput at %d elements%n",
                        (int) nativeThreshold);
            }
        } else {
            System.out.println("threshold: not measured (no native library)");
        }
    }

    private static Measurement measure(String name, int size, int[] input, Operation operation, int[] expected) {
        var iterations = iterationsFor(size);
        var warmup = Math.max(16, iterations / 4);

        for (var i = 0; i < warmup; i++) {
            verify(operation.apply(input, SALT), expected, name, size);
        }

        var samples = new long[iterations * ROUNDS];
        var index = 0;
        int[] last = null;
        var allocatedBefore = allocatedBytes();

        for (var round = 0; round < ROUNDS; round++) {
            for (var i = 0; i < iterations; i++) {
                var start = System.nanoTime();
                last = operation.apply(input, SALT);
                samples[index++] = System.nanoTime() - start;
            }
            verify(last, expected, name, size);
        }

        var allocatedAfter = allocatedBytes();
        Arrays.sort(samples);

        long total = 0;
        for (var sample : samples) {
            total += sample;
        }
        var calls = samples.length;
        var elements = (long) size * calls;
        var elementsPerSecond = total == 0 ? Double.POSITIVE_INFINITY : elements * 1_000_000_000.0 / total;

        return new Measurement(
                name,
                size,
                calls,
                total / 1_000_000.0,
                total / (double) calls,
                percentile(samples, 50),
                percentile(samples, 95),
                percentile(samples, 99),
                elementsPerSecond,
                calls / (total / 1_000_000_000.0),
                allocatedAfter < allocatedBefore ? 0 : (allocatedAfter - allocatedBefore) / calls,
                (long) size * Integer.BYTES * (name.equals("native-engine") ? 2 : 0));
    }

    private static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        var rank = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    private static void verify(int[] actual, int[] expected, String name, int size) {
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException(name + " disagreed with the Java contract at size " + size);
        }
    }

    private static int iterationsFor(int size) {
        if (size <= 4) return 100_000;
        if (size <= 64) return 50_000;
        if (size <= 1_024) return 10_000;
        if (size <= 16_384) return 1_000;
        if (size <= 65_536) return 300;
        return 40;
    }

    private static int[] optimizedTransform(int[] input, int salt, int[] output) {
        var length = input.length;
        for (var i = 0; i < length; i++) {
            output[i] = input[i] * 31 + salt + i;
        }
        return output;
    }

    private static int[] input(int size) {
        var input = new int[size];
        var state = INPUT_SEED;
        for (var i = 0; i < size; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            input[i] = (int) state;
        }
        return input;
    }

    private static Path resolveLibrary(String[] args) {
        var value = args.length > 0 ? args[0] : System.getProperty("veltis.native.test.library");
        if (value == null || value.isBlank()) {
            return null;
        }
        var path = Path.of(value);
        return Files.isRegularFile(path) ? path : null;
    }

    private static NativeLoader.LoadedLibrary load(Path library) {
        var result = NativeLoader.tryLoad(new NativeConfig(true, true, true, false, library.toString()));
        if (result.library() == null) {
            System.out.println("native library rejected: " + result.status().summary());
            return null;
        }
        if (result.library().initialize() != 0) {
            var detail = result.library().lastError();
            result.library().close();
            System.out.println("native library initialization failed: " + detail);
            return null;
        }
        return result.library();
    }

    private static double timerOverheadNanos() {
        var iterations = 100_000;
        long sink = 0;
        var start = System.nanoTime();
        for (var i = 0; i < iterations; i++) {
            sink += System.nanoTime();
        }
        var elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            System.out.print("");
        }
        return elapsed / (double) iterations;
    }

    private static double ffmDowncallNanos(NativeLoader.LoadedLibrary loaded) {
        var iterations = 100_000;
        long sink = 0;
        for (var i = 0; i < 10_000; i++) {
            sink += loaded.capabilitiesMask();
        }
        var start = System.nanoTime();
        for (var i = 0; i < iterations; i++) {
            sink += loaded.capabilitiesMask();
        }
        var elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) {
            System.out.print("");
        }
        return elapsed / (double) iterations;
    }

    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean threads) {
            return threads.getCurrentThreadAllocatedBytes();
        }
        return 0;
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long committedVirtualMemory() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getCommittedVirtualMemorySize();
        }
        return 0;
    }

    private static double mib(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static Measurement find(Iterable<Measurement> results, String name) {
        for (var result : results) {
            if (result.name().equals(name)) {
                return result;
            }
        }
        throw new IllegalStateException("missing measurement " + name);
    }

    private static void printHeader() {
        System.out.printf(Locale.ROOT,
                "%-16s %9s %8s %11s %10s %9s %9s %9s %14s %11s %12s%n",
                "implementation", "batch", "calls", "total ms", "avg ns", "p50 ns", "p95 ns", "p99 ns",
                "M elements/s", "K calls/s", "heap B/call");
    }

    private static void print(Measurement result) {
        System.out.printf(Locale.ROOT,
                "%-16s %9d %8d %11.2f %10.1f %9d %9d %9d %14.1f %11.1f %12d%n",
                result.name(),
                result.batchSize(),
                result.calls(),
                result.totalMillis(),
                result.averageNanos(),
                result.p50Nanos(),
                result.p95Nanos(),
                result.p99Nanos(),
                result.elementsPerSecond() / 1_000_000.0,
                result.callsPerSecond() / 1_000.0,
                result.heapBytesPerCall());
    }

    @FunctionalInterface
    private interface Operation {
        int[] apply(int[] input, int salt);
    }

    private record Measurement(
            String name,
            int batchSize,
            long calls,
            double totalMillis,
            double averageNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            double elementsPerSecond,
            double callsPerSecond,
            long heapBytesPerCall,
            long estimatedNativeBytesPerCall) {}

    /** Native call with one shared arena and pre-allocated segments. */
    private static final class NativeReusable implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private final NativeLoader.LoadedLibrary loaded;
        private final MemorySegment input;
        private final MemorySegment output;
        private final int[] result;

        private NativeReusable(NativeLoader.LoadedLibrary loaded, int size) {
            this.loaded = loaded;
            this.input = arena.allocate(ValueLayout.JAVA_INT, size);
            this.output = arena.allocate(ValueLayout.JAVA_INT, size);
            this.result = new int[size];
        }

        private int[] apply(int[] values, int salt) {
            MemorySegment.copy(values, 0, input, ValueLayout.JAVA_INT, 0, values.length);
            var rc = loaded.transform(input, values.length, salt, output, values.length);
            if (rc != 0) {
                throw new IllegalStateException("native reusable transform failed: " + loaded.lastError());
            }
            MemorySegment.copy(output, ValueLayout.JAVA_INT, 0, result, 0, values.length);
            return result;
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
