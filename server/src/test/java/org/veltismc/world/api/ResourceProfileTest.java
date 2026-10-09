package org.veltismc.world.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceProfileTest {

    private static final long GiB = 1024L * 1024L * 1024L;

    @Test
    void workerCeilingIsClampedToFloorAndCap() {
        assertEquals(ResourceProfile.MIN_WORKERS_FLOOR, ResourceProfile.workerCeiling(0));
        assertEquals(ResourceProfile.MIN_WORKERS_FLOOR, ResourceProfile.workerCeiling(1));
        assertEquals(2, ResourceProfile.workerCeiling(2));
        assertEquals(4, ResourceProfile.workerCeiling(4));
        assertEquals(ResourceProfile.MAX_WORKERS_CAP, ResourceProfile.workerCeiling(8));
        assertEquals(ResourceProfile.MAX_WORKERS_CAP, ResourceProfile.workerCeiling(64));
        assertEquals(ResourceProfile.MAX_WORKERS_CAP, ResourceProfile.workerCeiling(Integer.MAX_VALUE));
    }

    @Test
    void workerCeilingIsMonotonicAndBounded() {
        int previous = 0;
        for (int cpus = 0; cpus <= 256; cpus++) {
            int ceiling = ResourceProfile.workerCeiling(cpus);
            assertTrue(ceiling >= ResourceProfile.MIN_WORKERS_FLOOR);
            assertTrue(ceiling <= ResourceProfile.MAX_WORKERS_CAP);
            assertTrue(ceiling >= previous, "ceiling dropped at " + cpus);
            previous = ceiling;
        }
    }

    @Test
    void defaultsAreUsableOnThisMachine() {
        assertTrue(ResourceProfile.processors() >= 1);
        assertTrue(ResourceProfile.defaultMaxWorkers() >= ResourceProfile.MIN_WORKERS_FLOOR);
        assertTrue(ResourceProfile.defaultMaxWorkers() <= ResourceProfile.MAX_WORKERS_CAP);
        assertTrue(ResourceProfile.defaultMaxPooledSections() >= 64);
        assertTrue(ResourceProfile.defaultMaxPooledChunks() >= 64);
        assertTrue(ResourceProfile.defaultMaxPooledSaveBuffers() >= 16);
    }

    @Test
    void unknownHeapReturnsTheHistoricalCeiling() {
        assertEquals(4096, ResourceProfile.poolCapacity(-1, 25_000, 64, 4096));
        assertEquals(1024, ResourceProfile.poolCapacity(0, 2_000, 64, 1024));
    }

    @Test
    void smallHeapProducesSmallPools() {
        // 512 MiB / 32 = 16 MiB budget; 16 MiB / 25_000 bytes ~= 671 sections.
        int sections = ResourceProfile.poolCapacity(512L * 1024 * 1024, 25_000, 64, 4096);
        assertEquals(671, sections);
    }

    @Test
    void largeHeapIsCappedRatherThanUnbounded() {
        int sections = ResourceProfile.poolCapacity(64L * GiB, 25_000, 64, 4096);
        assertEquals(4096, sections);
    }

    @Test
    void capacityNeverLeavesTheRequestedRange() {
        long[] heaps = {-1, 0, 64L * 1024 * 1024, 512L * 1024 * 1024, 2L * GiB, 64L * GiB, Long.MAX_VALUE};
        for (long heap : heaps) {
            for (int item : new int[]{1, 1_000, 65_536, 10_000_000}) {
                int capacity = ResourceProfile.poolCapacity(heap, item, 16, 4096);
                assertTrue(capacity >= 16, "heap=" + heap + " item=" + item + " -> " + capacity);
                assertTrue(capacity <= 4096, "heap=" + heap + " item=" + item + " -> " + capacity);
            }
        }
    }

    @Test
    void rejectsNonPositiveItemSize() {
        assertThrows(IllegalArgumentException.class,
            () -> ResourceProfile.poolCapacity(GiB, 0, 16, 4096));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceProfile.poolCapacity(GiB, -1, 16, 4096));
    }

    @Test
    void rejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class,
            () -> ResourceProfile.poolCapacity(GiB, 1, 4096, 16));
        assertThrows(IllegalArgumentException.class,
            () -> ResourceProfile.poolCapacity(GiB, 1, -1, 16));
    }

    @Test
    void defaultsProduceAValidWorldConfig() {
        WorldConfig config = WorldConfig.defaults();
        assertTrue(config.maxWorkers() >= config.minWorkers());
        assertTrue(config.maxPooledChunks() >= 0);
        assertTrue(config.maxPooledSections() >= 0);
        assertTrue(config.maxPooledSaveBuffers() >= 0);
    }

    @Test
    void negativePoolsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> WorldConfig.builder().maxPooledChunks(-1).build());
        assertThrows(IllegalArgumentException.class,
            () -> WorldConfig.builder().maxPooledSections(-1).build());
        assertThrows(IllegalArgumentException.class,
            () -> WorldConfig.builder().maxPooledSaveBuffers(-1).build());
    }
}
