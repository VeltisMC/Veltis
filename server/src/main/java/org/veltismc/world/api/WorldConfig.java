package org.veltismc.world.api;

import java.util.Objects;

/**
 * Immutable configuration for the world engine and each world.
 *
 * <p>All values are advisory: the engine may clamp them (e.g. worker counts) when building.
 */
public record WorldConfig(
    int regionSizeChunks,
    int minWorkers,
    int maxWorkers,
    boolean adaptiveWorkers,
    long simTickIntervalMillis,
    int randomTicksPerChunkPerTick,
    int minSectionY,
    int sectionCount,
    long worldSeed,
    long longJobThresholdMillis,
    long watchdogIntervalMillis,
    int saveBatchSize,
    long saveFlushIntervalMillis,
    long workerParkTimeoutNanos,
    int maxQueuedBlockUpdatesPerTick,
    int maxPooledChunks,
    int maxPooledSections,
    int maxPooledSaveBuffers
) {

    public static final int DEFAULT_REGION_SIZE_CHUNKS = 32;
    public static final int DEFAULT_MIN_SECTION_Y = -4;
    public static final int DEFAULT_SECTION_COUNT = 24;
    public static final int DEFAULT_SIM_TICK_INTERVAL_MILLIS = 50;
    public static final int DEFAULT_RANDOM_TICKS_PER_CHUNK = 3;

    public WorldConfig {
        if (regionSizeChunks <= 0 || (regionSizeChunks & (regionSizeChunks - 1)) != 0) {
            throw new IllegalArgumentException("regionSizeChunks must be a power of two, got " + regionSizeChunks);
        }
        if (minWorkers < 1) {
            throw new IllegalArgumentException("minWorkers must be >= 1, got " + minWorkers);
        }
        if (maxWorkers < minWorkers) {
            throw new IllegalArgumentException("maxWorkers must be >= minWorkers, got " + maxWorkers);
        }
        if (sectionCount < 1) {
            throw new IllegalArgumentException("sectionCount must be >= 1, got " + sectionCount);
        }
        if (simTickIntervalMillis <= 0) {
            throw new IllegalArgumentException("simTickIntervalMillis must be > 0, got " + simTickIntervalMillis);
        }
        if (maxPooledChunks < 0 || maxPooledSections < 0 || maxPooledSaveBuffers < 0) {
            throw new IllegalArgumentException(
                "pool ceilings must be >= 0, got chunks=" + maxPooledChunks
                    + ", sections=" + maxPooledSections + ", saveBuffers=" + maxPooledSaveBuffers);
        }
        Objects.requireNonNull(worldSeed, "worldSeed");
    }

    /** Returns a configuration with sensible defaults for local development and tests. */
    public static WorldConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int regionSizeChunks = DEFAULT_REGION_SIZE_CHUNKS;
        private int minWorkers = 2;
        // Sized from the machine, not from a constant: on a one-core container
        // this is 2 (the floor), on a workstation it is the core count up to 8.
        // See ResourceProfile for why the JVM's own probe is container-aware.
        private int maxWorkers = ResourceProfile.defaultMaxWorkers();
        private boolean adaptiveWorkers = true;
        private long simTickIntervalMillis = DEFAULT_SIM_TICK_INTERVAL_MILLIS;
        private int randomTicksPerChunkPerTick = DEFAULT_RANDOM_TICKS_PER_CHUNK;
        private int minSectionY = DEFAULT_MIN_SECTION_Y;
        private int sectionCount = DEFAULT_SECTION_COUNT;
        private long worldSeed = 0x5EEDL;
        private long longJobThresholdMillis = 5000L;
        private long watchdogIntervalMillis = 5000L;
        private int saveBatchSize = 64;
        private long saveFlushIntervalMillis = 1000L;
        private long workerParkTimeoutNanos = 200_000L;
        private int maxQueuedBlockUpdatesPerTick = 4096;
        // Pool ceilings scale with the JVM's maximum heap. On a two-gigabyte
        // container the section pool drops from the old flat 4096 (~100 MB if
        // fully populated) to a few hundred; on a workstation it stays roomy.
        // A pool is lazy, so this only bounds the worst case.
        private int maxPooledChunks = ResourceProfile.defaultMaxPooledChunks();
        private int maxPooledSections = ResourceProfile.defaultMaxPooledSections();
        private int maxPooledSaveBuffers = ResourceProfile.defaultMaxPooledSaveBuffers();

        public Builder regionSizeChunks(int v) { this.regionSizeChunks = v; return this; }
        public Builder minWorkers(int v) { this.minWorkers = v; return this; }
        public Builder maxWorkers(int v) { this.maxWorkers = v; return this; }
        public Builder adaptiveWorkers(boolean v) { this.adaptiveWorkers = v; return this; }
        public Builder simTickIntervalMillis(long v) { this.simTickIntervalMillis = v; return this; }
        public Builder randomTicksPerChunkPerTick(int v) { this.randomTicksPerChunkPerTick = v; return this; }
        public Builder minSectionY(int v) { this.minSectionY = v; return this; }
        public Builder sectionCount(int v) { this.sectionCount = v; return this; }
        public Builder worldSeed(long v) { this.worldSeed = v; return this; }
        public Builder longJobThresholdMillis(long v) { this.longJobThresholdMillis = v; return this; }
        public Builder watchdogIntervalMillis(long v) { this.watchdogIntervalMillis = v; return this; }
        public Builder saveBatchSize(int v) { this.saveBatchSize = v; return this; }
        public Builder saveFlushIntervalMillis(long v) { this.saveFlushIntervalMillis = v; return this; }
        public Builder workerParkTimeoutNanos(long v) { this.workerParkTimeoutNanos = v; return this; }
        public Builder maxQueuedBlockUpdatesPerTick(int v) { this.maxQueuedBlockUpdatesPerTick = v; return this; }
        public Builder maxPooledChunks(int v) { this.maxPooledChunks = v; return this; }
        public Builder maxPooledSections(int v) { this.maxPooledSections = v; return this; }
        public Builder maxPooledSaveBuffers(int v) { this.maxPooledSaveBuffers = v; return this; }

        public WorldConfig build() {
            return new WorldConfig(
                regionSizeChunks, minWorkers, maxWorkers, adaptiveWorkers, simTickIntervalMillis,
                randomTicksPerChunkPerTick, minSectionY, sectionCount, worldSeed, longJobThresholdMillis,
                watchdogIntervalMillis, saveBatchSize, saveFlushIntervalMillis, workerParkTimeoutNanos,
                maxQueuedBlockUpdatesPerTick, maxPooledChunks, maxPooledSections, maxPooledSaveBuffers);
        }
    }
}
