package org.veltismc.world.scheduler;

import org.junit.jupiter.api.Test;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobHandle;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.api.RegionScheduler;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static WorldEngine engine() {
        WorldConfig config = WorldConfig.builder()
            .minWorkers(2)
            .maxWorkers(4)
            .adaptiveWorkers(false)
            .workerParkTimeoutNanos(50_000L)
            .build();
        WorldEngine engine = WorldEngines.create(config);
        engine.start();
        return engine;
    }

    @Test
    void migratableJobExecutesOnWorker() throws Exception {
        WorldEngine engine = engine();
        try {
            RegionScheduler scheduler = engine.scheduler();
            CompletableFuture<Void> ran = new CompletableFuture<>();
            JobHandle handle = scheduler.schedule(new RegionJob() {
                @Override
                public String name() {
                    return "test-job";
                }

                @Override
                public JobPriority priority() {
                    return JobPriority.NORMAL;
                }

                @Override
                public boolean regionBound() {
                    return false;
                }

                @Override
                public void execute(JobContext ctx) {
                    assertNull(ctx.region());
                    ran.complete(null);
                }
            });
            ran.get(10, TimeUnit.SECONDS);
            // The body completes the future before the scheduler marks the handle
            // done, so completion must be observed rather than assumed.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!handle.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(handle.isDone());
        } finally {
            engine.stop();
        }
    }

    @Test
    void cancelledJobDoesNotRunBody() throws Exception {
        WorldEngine engine = engine();
        try {
            RegionScheduler scheduler = engine.scheduler();
            AtomicInteger executed = new AtomicInteger();
            JobHandle handle = scheduler.schedule(new RegionJob() {
                @Override
                public String name() {
                    return "cancelled-test-job";
                }

                @Override
                public JobPriority priority() {
                    return JobPriority.NORMAL;
                }

                @Override
                public boolean regionBound() {
                    return false;
                }

                @Override
                public void execute(JobContext ctx) {
                    if (ctx.cancelled()) {
                        return;
                    }
                    executed.incrementAndGet();
                }
            });
            handle.cancel();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!handle.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(handle.isDone());
            assertTrue(handle.isCancelled());
            assertEquals(0, executed.get());
        } finally {
            engine.stop();
        }
    }

    @Test
    void poolSizeReflectsConfiguration() {
        WorldEngine engine = engine();
        try {
            assertTrue(engine.metrics().workerCount() >= 2);
            assertTrue(engine.scheduler().pendingJobs() >= 0);
        } finally {
            engine.stop();
        }
    }

    @Test
    void shutdownStopsWorkers() {
        WorldEngine engine = engine();
        engine.stop();
        assertEquals(0, engine.scheduler().activeWorkers());
    }
}
