package org.veltismc.world.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectPoolTest {

    private static final ObjectPool.Factory<StringBuilder> FACTORY = StringBuilder::new;

    @Test
    void borrowCreatesWhenEmpty() {
        ObjectPool<StringBuilder> pool = new ObjectPool<>(FACTORY, 4);
        StringBuilder a = pool.borrow();
        assertEquals(1, pool.created());
        assertEquals(1, pool.inUse());
        assertEquals(0, pool.size());
        a.append("x");
    }

    @Test
    void releaseAndReuse() {
        ObjectPool<StringBuilder> pool = new ObjectPool<>(FACTORY, 4);
        StringBuilder a = pool.borrow();
        a.append("reused");
        pool.release(a);
        assertEquals(0, pool.inUse());
        assertEquals(1, pool.size());
        StringBuilder b = pool.borrow();
        assertSame(a, b);
        assertEquals("reused", b.toString());
        assertEquals(1, pool.created());
    }

    @Test
    void capacityDiscardsExcess() {
        ObjectPool<StringBuilder> pool = new ObjectPool<>(FACTORY, 2);
        StringBuilder a = pool.borrow();
        StringBuilder b = pool.borrow();
        StringBuilder c = pool.borrow();
        pool.release(a);
        pool.release(b);
        pool.release(c);
        assertEquals(2, pool.size());
        assertEquals(0, pool.inUse());
        StringBuilder d = pool.borrow();
        assertNotSame(c, d);
        assertEquals(3, pool.created(), "a, b and c were created; d must come from the pool");
        pool.release(d);
        assertEquals(0, pool.inUse());
    }

    @Test
    void concurrentBorrowRelease() throws Exception {
        ObjectPool<StringBuilder> pool = new ObjectPool<>(FACTORY, 8);
        int threads = 8;
        int iterations = 2_000;
        ExecutorService poolExec = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            poolExec.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        StringBuilder sb = pool.borrow();
                        sb.append('a');
                        pool.release(sb);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        poolExec.shutdown();
        poolExec.awaitTermination(30, TimeUnit.SECONDS);
        assertEquals(0, pool.inUse());
        assertTrue(pool.size() <= 8, "pool exceeded capacity: " + pool.size());
        assertTrue(pool.created() < threads * iterations, "pool should reuse instances: " + pool.created());
    }
}
