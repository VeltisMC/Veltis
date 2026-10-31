package org.veltismc.world.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.veltismc.world.api.ChunkDataCodec;
import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.chunk.ChunkSection;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Old-vs-new codec benchmark. Not a normal test: it only runs when
 * {@code -Dveltis.bench=true} is set (the {@code -PveltisBench} Gradle property
 * does that), and it prints results instead of asserting performance.
 *
 * <p>Both implementations see identical snapshots and identical encoded bytes.
 * Each measurement is warmed up first, then split into rounds so every reported
 * percentile comes from steady-state code. Allocation is read from the JVM's
 * per-thread allocation counter; GC counts/times are read from the collector
 * beans around the measured rounds.
 */
class ChunkCodecBenchmark {

    private static final int[] SECTION_COUNTS = {1, 24, 64};
    private static final int WARMUP_CALLS = Integer.getInteger("veltis.codec.bench.warmup", 200);
    private static final int ROUNDS = Integer.getInteger("veltis.codec.bench.rounds", 3);

    /** Prevents the JIT from discarding the measured calls. */
    private static volatile Object blackhole;

    private final ChunkDataCodecImpl optimized = new ChunkDataCodecImpl();
    private final LegacyChunkDataCodec legacy = new LegacyChunkDataCodec();

    @Test
    @EnabledIfSystemProperty(named = "veltis.bench", matches = "true")
    void compare() throws Exception {
        System.out.println("== VeltisMC chunk codec benchmark ==");
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors()
            + " maxHeapMiB=" + (Runtime.getRuntime().maxMemory() >> 20)
            + " warmupCalls=" + WARMUP_CALLS + " rounds=" + ROUNDS);
        System.out.println("allocation is per-thread allocated bytes; GC is young+old delta during the measured rounds");
        System.out.printf("%-8s %-6s %-8s %12s %10s %9s %9s %9s %12s %10s%n",
            "sections", "mode", "impl", "ops/s", "avg us", "p50 us", "p95 us", "p99 us",
            "alloc B/op", "GC c/ms");

        for (int sections : SECTION_COUNTS) {
            ChunkSnapshot snapshot = snapshot(sections, 0xBEEF + sections);
            byte[] encoded = legacy.encode(snapshot);
            assertArrayEquals(encoded, optimized.encode(snapshot), "encoded bytes differ");

            int iterations = iterationsFor(sections);
            Stats legacyEncode = measure(true, legacy, snapshot, encoded, iterations);
            Stats optimizedEncode = measure(true, optimized, snapshot, encoded, iterations);
            Stats legacyDecode = measure(false, legacy, snapshot, encoded, iterations);
            Stats optimizedDecode = measure(false, optimized, snapshot, encoded, iterations);

            print(sections, "encode", "legacy", legacyEncode);
            print(sections, "encode", "bytebuf", optimizedEncode);
            print(sections, "decode", "legacy", legacyDecode);
            print(sections, "decode", "bytebuf", optimizedDecode);
            System.out.printf("  -> encode speedup %.2fx, decode speedup %.2fx%n",
                optimizedEncode.opsPerSecond / legacyEncode.opsPerSecond,
                optimizedDecode.opsPerSecond / legacyDecode.opsPerSecond);

            assertTrue(optimizedEncode.opsPerSecond > 0);
            assertTrue(optimizedDecode.opsPerSecond > 0);
        }
    }

    private static int iterationsFor(int sections) {
        return switch (sections) {
            case 1 -> 5_000;
            case 24 -> 300;
            default -> 120;
        };
    }

    private static Stats measure(boolean encode, ChunkDataCodec codec, ChunkSnapshot snapshot,
                                 byte[] encoded, int iterations) throws Exception {
        for (int i = 0; i < WARMUP_CALLS; i++) {
            blackhole = encode ? codec.encode(snapshot) : codec.decode(encoded);
        }

        com.sun.management.ThreadMXBean allocationBean =
            ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        long threadId = Thread.currentThread().threadId();
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTime();
        long allocatedBefore = allocationBean == null ? -1 : allocationBean.getThreadAllocatedBytes(threadId);

        long[] samples = new long[ROUNDS * iterations];
        int sampleIndex = 0;
        long totalNanos = 0;
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                blackhole = encode ? codec.encode(snapshot) : codec.decode(encoded);
                long elapsed = System.nanoTime() - start;
                samples[sampleIndex++] = elapsed;
                totalNanos += elapsed;
            }
        }

        long allocatedAfter = allocationBean == null ? -1 : allocationBean.getThreadAllocatedBytes(threadId);
        long gcCountAfter = gcCount();
        long gcTimeAfter = gcTime();
        int calls = samples.length;

        Arrays.sort(samples);
        return new Stats(
            calls * 1_000_000_000.0 / Math.max(totalNanos, 1L),
            totalNanos / (double) calls / 1_000.0,
            percentile(samples, 0.50),
            percentile(samples, 0.95),
            percentile(samples, 0.99),
            allocatedBefore < 0 ? Double.NaN : (allocatedAfter - allocatedBefore) / (double) calls,
            gcCountAfter - gcCountBefore,
            gcTimeAfter - gcTimeBefore);
    }

    private static void print(int sections, String mode, String impl, Stats stats) {
        System.out.printf("%-8d %-6s %-8s %12.0f %10.2f %9.2f %9.2f %9.2f %12.0f %10s%n",
            sections, mode, impl, stats.opsPerSecond, stats.avgMicros,
            stats.p50Micros, stats.p95Micros, stats.p99Micros, stats.allocBytesPerOp,
            stats.gcCount + "/" + stats.gcMillis);
    }

    private static double percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(index, 0)] / 1_000.0;
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(bean.getCollectionCount(), 0);
        }
        return total;
    }

    private static long gcTime() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(bean.getCollectionTime(), 0);
        }
        return total;
    }

    private static ChunkSnapshot snapshot(int sections, long seed) {
        Random random = new Random(seed);
        int[][] blocks = new int[sections][];
        byte[][] blockLight = new byte[sections][];
        byte[][] skyLight = new byte[sections][];
        byte[][] opaque = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            int[] sectionBlocks = new int[ChunkSection.BLOCK_COUNT];
            for (int b = 0; b < sectionBlocks.length; b++) {
                sectionBlocks[b] = random.nextInt();
            }
            byte[] bl = new byte[ChunkSection.BLOCK_COUNT];
            byte[] sl = new byte[ChunkSection.BLOCK_COUNT];
            byte[] op = new byte[ChunkSection.BLOCK_COUNT / 8];
            random.nextBytes(bl);
            random.nextBytes(sl);
            random.nextBytes(op);
            blocks[i] = sectionBlocks;
            blockLight[i] = bl;
            skyLight[i] = sl;
            opaque[i] = op;
        }
        return new ChunkSnapshot(null, blocks, blockLight, skyLight, opaque, random.nextLong());
    }

    private record Stats(double opsPerSecond, double avgMicros, double p50Micros,
                         double p95Micros, double p99Micros, double allocBytesPerOp,
                         long gcCount, long gcMillis) {
    }
}
