package org.veltismc.world.api;

/**
 * Defaults derived from the machine the server actually runs on.
 *
 * <p>The world engine's pools and worker counts used to be constants chosen for a
 * development machine: a section pool of 4096 ({@value #SECTION_BYTES} bytes per
 * section is roughly 100&nbsp;MB of retained arrays) and a worker ceiling of
 * {@code max(4, availableProcessors())}. Neither is wrong on a big host, and both
 * are wrong on the deployment this exists for — one core and two gigabytes, often
 * inside a container. This class turns "how much machine is there" into the two
 * numbers that matter, so the same jar is frugal there and unchanged on a
 * workstation.
 *
 * <p><b>CPU.</b> {@link Runtime#availableProcessors()} is used directly, which is
 * what makes this container-aware: with {@code UseContainerSupport} (the default
 * on every modern JVM) it reports the cgroup CPU quota rather than the host's
 * core count, so a two-core limit on a sixty-four-core machine sizes like two
 * cores. The result is clamped to
 * [{@value #MIN_WORKERS_FLOOR}, {@value #MAX_WORKERS_CAP}]: below the floor the
 * engine cannot overlap disk with compute; above the cap the extra threads are
 * stacks and context switches for no throughput.
 *
 * <p><b>Memory.</b> Pool ceilings are a fraction of the JVM's maximum heap, so a
 * small container gets small pools and a large one keeps roomy ones. The ceiling
 * is only a ceiling — a pool grows on demand and an unused one costs nothing but
 * a reference — so this bounds the worst case without pre-allocating anything.
 *
 * <p>All methods are pure and side-effect free; the JVM probes are separated from
 * the arithmetic so the arithmetic can be tested without a machine of a given
 * size.
 */
public final class ResourceProfile {

    /**
     * Hard ceiling on scheduler workers. A region scheduler that needs more than
     * this on one machine is already past the point where more threads help;
     * what it needs is fewer regions in flight.
     */
    public static final int MAX_WORKERS_CAP = 8;

    /** Floor on scheduler workers: one to run, one to overlap disk with compute. */
    public static final int MIN_WORKERS_FLOOR = 2;

    /** Bytes a {@link org.veltismc.world.chunk.ChunkSection} retains (four arrays). */
    public static final int SECTION_BYTES = 25_000;

    /** Bytes a pooled {@link org.veltismc.world.chunk.Chunk} shell retains (no sections). */
    public static final int CHUNK_BYTES = 2_000;

    /** Bytes a pooled save buffer retains. */
    public static final int SAVE_BUFFER_BYTES = 65_536;

    /**
     * The share of the maximum heap the engine's pools may retain, as a divisor:
     * {@code heap / 32} is about three percent. Deliberately small — Minecraft's
     * own working set, not the pool, is what needs the memory.
     */
    public static final long POOL_HEAP_DIVISOR = 32;

    private ResourceProfile() {
    }

    /** The machine's usable processor count, never below one. */
    public static int processors() {
        int detected = Runtime.getRuntime().availableProcessors();
        return Math.max(detected, 1);
    }

    /** The JVM's maximum heap in bytes, or {@code -1} when the JVM does not say. */
    public static long maxHeapBytes() {
        long max = Runtime.getRuntime().maxMemory();
        return max > 0 ? max : -1L;
    }

    /** The default scheduler worker ceiling for this machine. */
    public static int defaultMaxWorkers() {
        return workerCeiling(processors());
    }

    /**
     * The worker ceiling for a given processor count, clamped to
     * [{@value #MIN_WORKERS_FLOOR}, {@value #MAX_WORKERS_CAP}].
     */
    public static int workerCeiling(int processors) {
        return Math.max(MIN_WORKERS_FLOOR, Math.min(MAX_WORKERS_CAP, processors));
    }

    /**
     * The number of {@code itemBytes}-sized pooled items a heap can hold, within
     * [{@code min}, {@code max}].
     *
     * @param heapBytes the JVM maximum heap, or {@code -1} when unknown; an
     *                  unknown heap returns {@code max} (the historical ceiling)
     *                  rather than guessing low
     * @param itemBytes the retained size of one pooled item; must be positive
     */
    public static int poolCapacity(long heapBytes, int itemBytes, int min, int max) {
        if (itemBytes <= 0) {
            throw new IllegalArgumentException("itemBytes must be positive, got " + itemBytes);
        }
        if (min < 0 || max < min) {
            throw new IllegalArgumentException(
                "invalid range [" + min + ", " + max + "]");
        }
        if (heapBytes <= 0) {
            return max;
        }
        long count = (heapBytes / POOL_HEAP_DIVISOR) / itemBytes;
        if (count < min) {
            return min;
        }
        if (count > max) {
            return max;
        }
        return (int) count;
    }

    /** Default section-pool ceiling for this machine. */
    public static int defaultMaxPooledSections() {
        return poolCapacity(maxHeapBytes(), SECTION_BYTES, 64, 4096);
    }

    /** Default chunk-pool ceiling for this machine. */
    public static int defaultMaxPooledChunks() {
        return poolCapacity(maxHeapBytes(), CHUNK_BYTES, 64, 1024);
    }

    /** Default save-buffer-pool ceiling for this machine. */
    public static int defaultMaxPooledSaveBuffers() {
        return poolCapacity(maxHeapBytes(), SAVE_BUFFER_BYTES, 16, 512);
    }
}
