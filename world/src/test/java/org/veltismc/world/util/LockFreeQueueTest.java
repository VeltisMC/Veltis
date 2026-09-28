package org.veltismc.world.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockFreeQueueTest {

    @Test
    void emptyQueuePollsNull() {
        LockFreeQueue<String> q = new LockFreeQueue<>();
        assertNull(q.poll());
        assertEquals(0, q.size());
        assertTrue(!q.hasItems());
    }

    @Test
    void fifoOrderSingleProducer() {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        for (int i = 0; i < 100; i++) {
            q.add(i);
        }
        assertEquals(100, q.size());
        assertTrue(q.hasItems());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, q.poll());
        }
        assertNull(q.poll());
        assertEquals(0, q.size());
    }

    @Test
    void drainFullyRepeatedly() {
        LockFreeQueue<String> q = new LockFreeQueue<>();
        for (int round = 0; round < 10; round++) {
            q.add("a");
            q.add("b");
            assertEquals("a", q.poll());
            assertEquals("b", q.poll());
            assertNull(q.poll());
        }
    }

    @Test
    void multiProducerSingleConsumer() throws Exception {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        int producers = 8;
        int perProducer = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch done = new CountDownLatch(producers);
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perProducer; i++) {
                        q.add(base + i);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        ConcurrentLinkedQueue<Integer> seen = new ConcurrentLinkedQueue<>();
        int total = producers * perProducer;
        while (seen.size() < total) {
            Integer v = q.poll();
            if (v != null) {
                seen.add(v);
            } else {
                assertTrue(done.getCount() > 0, "drained empty while producers still active");
                Thread.sleep(1);
            }
        }
        done.await(10, TimeUnit.SECONDS);
        assertEquals(total, seen.size());
        List<Integer> sorted = new ArrayList<>(seen);
        sorted.sort(Integer::compareTo);
        assertEquals(total, sorted.size());
        for (int i = 0; i < total; i++) {
            assertEquals(i, sorted.get(i));
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }
}
