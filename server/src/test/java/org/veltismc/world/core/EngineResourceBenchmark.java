package org.veltismc.world.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;

/**
 * Before/after measurement for the low-resource defaults.
 *
 * <p>It is <b>not</b> a normal test: it is disabled unless
 * {@code -Dveltis.bench=true} is set, because a benchmark that runs on every
 * build is a flaky build. The build script turns {@code -PveltisBench} into a
 * JVM with the deployment's shape — {@code -XX:ActiveProcessorCount=1 -Xmx2g}
 * — so {@link Runtime#availableProcessors()} and {@link Runtime#maxMemory()}
 * are the container's, not the developer's.
 *
 * <p>One configuration per invocation ({@code -Dveltis.bench.config=legacy} or
 * {@code resource}), so each number comes from a fresh heap and the two are
 * comparable. The workload loads a whole batch concurrently, which is what
 * fills the pools and lets adaptive workers scale, then saves and unloads it.
 */
class EngineResourceBenchmark {

    private static final int BATCH = Integer.getInteger("veltis.bench.batch", 256);
    private static final int ROUNDS = Integer.getInteger("veltis.bench.rounds", 2);

    @Test
    @EnabledIfSystemProperty(named = "veltis.bench", matches = "true")
    void measure() throws Exception {
        String which = System.getProperty("veltis.bench.config", "resource");
        WorldConfig config = "legacy".equals(which) ? legacyConfig() : resourceAwareConfig();
        Result result = run(which, config);

        System.out.println("== VeltisMC engine resource benchmark ==");
        System.out.println("config=" + which
            + " availableProcessors=" + Runtime.getRuntime().availableProcessors()
            + " maxHeapMiB=" + (Runtime.getRuntime().maxMemory() >> 20));
        System.out.printf("configured: maxWorkers=%d minWorkers=%d sections=%d chunks=%d saveBuffers=%d%n",
            config.maxWorkers(), config.minWorkers(),
            config.maxPooledSections(), config.maxPooledChunks(), config.maxPooledSaveBuffers());
        System.out.printf("%-10s %10s %8s %12s %12s%n", "config", "chunks/s", "workers", "peak used", "retained");
        System.out.printf("%-10s %10.1f %8d %9d MiB %9d MiB%n",
            result.label, result.chunksPerSecond(), result.workerHighWater,
            result.peakUsedMiB, result.retainedMiB);
    }

    private static WorldConfig legacyConfig() {
        return base()
            .maxWorkers(Math.max(4, Runtime.getRuntime().availableProcessors()))
            .maxPooledChunks(1024)
            .maxPooledSections(4096)
            .maxPooledSaveBuffers(512)
            .build();
    }

    private static WorldConfig resourceAwareConfig() {
        return base().build();
    }

    private static WorldConfig.Builder base() {
        return WorldConfig.builder()
            .regionSizeChunks(1)
            .minWorkers(2)
            .adaptiveWorkers(true)
            .simTickIntervalMillis(10)
            .workerParkTimeoutNanos(50_000L)
            .watchdogIntervalMillis(200)
            .longJobThresholdMillis(1_000)
            .saveFlushIntervalMillis(50)
            .saveBatchSize(64);
    }

    private static Result run(String label, WorldConfig config) throws Exception {
        WorldEngine engine = WorldEngines.create(config);
        engine.start();

        long[] peak = {0};
        int[] workerHighWater = {0};
        // Sampled from a separate thread for the whole run, so the high-water is
        // the peak the pools and the scheduler actually reached -- not the state
        // at one convenient moment. This is the number the adaptive-worker
        // comparison rests on, so it is measured rather than inferred.
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                peak[0] = Math.max(peak[0], heap.getUsed());
                workerHighWater[0] = Math.max(workerHighWater[0], engine.metrics().workerCount());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "bench-sampler");
        sampler.setDaemon(true);

        long start = System.nanoTime();
        long end;
        World world = engine.createWorld("bench");
        try {
            sampler.start();
            for (int round = 0; round < ROUNDS; round++) {
                ArrayList<ChunkHandle> handles = new ArrayList<>(BATCH);
                for (int i = 0; i < BATCH; i++) {
                    int index = round * BATCH + i;
                    handles.add(world.chunk(new ChunkPos(index % 256, index / 256)));
                }
                for (ChunkHandle chunk : handles) {
                    chunk.load().get(120, java.util.concurrent.TimeUnit.SECONDS);
                }
                for (ChunkHandle chunk : handles) {
                    chunk.setBlock(BlockPos.of(0, 1, 0), new BlockState(7, 0, false))
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                }
                world.saves().flush().get(180, java.util.concurrent.TimeUnit.SECONDS);
                workerHighWater[0] = Math.max(workerHighWater[0], engine.metrics().workerCount());
                for (ChunkHandle chunk : handles) {
                    chunk.unload().get(120, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            world.saves().flush().get(180, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            end = System.nanoTime();
            sampler.interrupt();
            sampler.join(1000);
        }

        // Retained after the pools have absorbed the whole batch: this is what
        // the pool ceilings actually bound.
        System.gc();
        Thread.sleep(100);
        System.gc();
        long retained = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();

        engine.stop();
        long elapsedNanos = Math.max(end - start, 1L);
        return new Result(label, BATCH * ROUNDS, elapsedNanos, workerHighWater[0], peak[0] >> 20, retained >> 20);
    }

    private record Result(String label, int chunks, long elapsedNanos, int workerHighWater,
                          long peakUsedMiB, long retainedMiB) {
        double chunksPerSecond() {
            return chunks * 1_000_000_000.0 / elapsedNanos;
        }
    }
}
