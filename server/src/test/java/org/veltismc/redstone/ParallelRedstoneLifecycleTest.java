package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deterministic, server-free tests for the parallel-phase lifecycle seams that cannot be
 * forced reliably against a live server: atomic deferred admission, drain semantics,
 * per-tick failure bookkeeping, scheduled-tick queries, and the per-level shutdown
 * protocol.
 *
 * <p>These tests exercise the production methods directly ({@link ParallelRedstone.Phase},
 * {@link ParallelRedstone#runRegion}, {@link ParallelRedstone#drainDeferred},
 * {@link ParallelRedstone.LevelState}); no fake reimplementation is involved.
 */
class ParallelRedstoneLifecycleTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------- deferred admission

    @Test
    public void deferredAdmissionIsExactlyOnceUnderConcurrentProducers() throws Exception {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        int producerCount = 8;
        int perProducer = 500;
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producerCount);

        List<Thread> producers = new ArrayList<>();
        for (int i = 0; i < producerCount; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < perProducer; n++) {
                        if (phase.offer(executed::incrementAndGet)) {
                            accepted.incrementAndGet();
                        } else {
                            // Admission is closed for the remainder of the tick. Keep
                            // attempting so every one of the producer's attempts is
                            // classified as accepted or rejected.
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    producersDone.countDown();
                }
            }, "lifecycle-producer-" + i);
            thread.setDaemon(true);
            producers.add(thread);
            thread.start();
        }

        start.countDown();
        // Let the producers make measurable progress so the drain genuinely overlaps
        // admission instead of closing the gate before any work exists.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (accepted.get() < 100 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(accepted.get() >= 100, "producers must make progress before the drain");
        // Close and drain while producers are still hammering the admission gate. Every
        // operation accepted before the close must be drained; every rejection must be
        // reported through offer() == false.
        int drained = ParallelRedstone.drainDeferred(phase);
        assertTrue(producersDone.await(10, TimeUnit.SECONDS), "producers must observe the closed admission gate");
        for (Thread thread : producers) {
            thread.join(5_000);
        }

        assertEquals(accepted.get(), executed.get(), "accepted operations must execute exactly once");
        assertEquals(drained, executed.get(), "the drain must observe every accepted operation");
        assertEquals(producerCount * perProducer, accepted.get() + rejected.get(),
                "every producer attempt must be either accepted or rejected");
        assertEquals(ParallelRedstone.Phase.STATE_CLOSED, phase.state.get(), "drain must close the phase");
        assertEquals(0, ParallelRedstone.drainDeferred(phase), "a second drain must execute nothing");
        assertFalse(phase.offer(() -> { }), "closed admission must reject new work");
    }

    @Test
    public void nestedWorkDuringDrainFallsBackToTheCaller() {        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        AtomicInteger inlineExecutions = new AtomicInteger();
        AtomicBoolean nestedOffered = new AtomicBoolean(true);

        assertTrue(phase.offer(() -> {
            // The documented policy: admission is closed during draining, so nested work is
            // rejected and the caller executes it immediately on the server thread.
            nestedOffered.set(phase.offer(inlineExecutions::incrementAndGet));
            if (!nestedOffered.get()) {
                inlineExecutions.incrementAndGet();
            }
        }));

        assertEquals(1, ParallelRedstone.drainDeferred(phase), "only the admitted operation is drained");
        assertFalse(nestedOffered.get(), "nested admission during draining must be rejected");
        assertEquals(1, inlineExecutions.get(), "the nested operation must run through the immediate fallback");
    }

    @Test
    public void closeAdmissionIsAtomicWithOffer() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        AtomicInteger executed = new AtomicInteger();
        assertTrue(phase.offer(executed::incrementAndGet));
        phase.closeAdmission();
        assertEquals(ParallelRedstone.Phase.STATE_DRAINING, phase.state.get());
        assertFalse(phase.offer(executed::incrementAndGet), "no operation may be admitted after close");
        assertEquals(1, ParallelRedstone.drainDeferred(phase));
        assertEquals(1, executed.get());
    }

    @Test
    public void drainPreservesEnqueueOrderAndExecutesEachOperationOnce() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int value = i;
            assertTrue(phase.offer(() -> order.add(value)));
        }

        assertEquals(100, ParallelRedstone.drainDeferred(phase));
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add(i);
        }
        assertEquals(expected, order, "deferred operations must run in enqueue order");
    }

    @Test
    public void drainFailureIsRecordedWithoutSkippingLaterOperations() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        RuntimeException boom = new RuntimeException("deferred failure");
        AtomicInteger later = new AtomicInteger();

        assertTrue(phase.offer(() -> {
            throw boom;
        }));
        assertTrue(phase.offer(later::incrementAndGet));

        assertEquals(2, ParallelRedstone.drainDeferred(phase),
                "a failing callback must not stop the rest of the drain");
        assertEquals(1, later.get());
        assertSame(boom, phase.failure.get(), "drain failures must be reported, never lost");
    }

    @Test
    public void closedPhaseNeverExecutesLateOffers() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        phase.closeAdmission();
        AtomicInteger executed = new AtomicInteger();

        assertFalse(phase.offer(executed::incrementAndGet), "closed admission must reject");
        assertEquals(0, ParallelRedstone.drainDeferred(phase));
        assertEquals(0, executed.get(), "rejected work must never be drained");
    }

    @Test
    public void failedPhaseDoesNotPreventTheNextPhase() {
        ParallelRedstone.LevelState state = new ParallelRedstone.LevelState(null);
        ParallelRedstone.Phase failed = state.beginPhase(null, 1);
        assertNotNull(failed);
        ParallelRedstone.runRegion(failed, ticks(2), (pos, type) -> {
            throw new IllegalStateException("boom");
        });
        assertNotNull(failed.failure.get());
        state.endPhase(failed);

        ParallelRedstone.Phase next = state.beginPhase(null, 1);
        assertNotNull(next, "a recoverable failure must not permanently close the level");
        AtomicInteger ran = new AtomicInteger();
        ParallelRedstone.runRegion(next, ticks(3), (pos, type) -> ran.incrementAndGet());
        assertEquals(3, ran.get(), "the next phase must execute normally");
        assertEquals(3, next.executed.size());
        assertNull(next.failure.get(), "a fresh phase starts without a failure");
        state.endPhase(next);
    }

    @Test
    public void releaseDuringActiveWorkWaitsForDrainThenCleansUp() throws Exception {
        ParallelRedstone.LevelState state = new ParallelRedstone.LevelState(null);
        ParallelRedstone.Phase phase = state.beginPhase(null, 1);
        assertNotNull(phase);
        AtomicInteger drained = new AtomicInteger();
        assertTrue(phase.offer(drained::incrementAndGet));

        AtomicBoolean released = new AtomicBoolean();
        Thread releaser = new Thread(() -> {
            state.release();
            released.set(true);
        }, "lifecycle-drain-releaser");
        releaser.setDaemon(true);
        releaser.start();

        Thread.sleep(100);
        assertFalse(released.get(), "release must wait for the in-flight phase");
        assertTrue(state.closing, "release must refuse new phases immediately");
        assertNull(state.beginPhase(null, 1));

        assertEquals(1, ParallelRedstone.drainDeferred(phase));
        assertEquals(1, drained.get(), "the shutdown must not drop accepted deferred work");
        state.endPhase(phase);

        releaser.join(5_000);
        assertTrue(released.get(), "release must complete after the phase ends");
    }

    @Test
    public void willTickThisTickIgnoresForeignTickContainers() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        ParallelRedstone.PhaseContext context = new ParallelRedstone.PhaseContext(null, null, phase);
        ParallelRedstone.PhaseContext previous = ParallelRedstone.installContextForTest(context);
        try {
            net.minecraft.world.ticks.LevelTicks<Block> foreign =
                    new net.minecraft.world.ticks.LevelTicks<>(pos -> true);
            // The hook must only answer for the phase's own LevelTicks; a query about
            // another level falls back to vanilla's own bookkeeping (null = defer).
            assertNull(ParallelRedstone.willTickThisTick(foreign, new BlockPos(0, 64, 0), Blocks.REDSTONE_WIRE));
        } finally {
            ParallelRedstone.installContextForTest(previous);
        }
    }

    // ------------------------------------------------------ tick failure policy

    @Test
    public void tickThrowingBeforeMutationIsNeverMarkedExecuted() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        RuntimeException boom = new RuntimeException("boom");
        List<ScheduledTick<Block>> ticks = ticks(3);
        AtomicInteger attempted = new AtomicInteger();

        ParallelRedstone.runRegion(phase, ticks, (pos, type) -> {
            if (attempted.incrementAndGet() == 2) {
                throw boom;
            }
        });

        assertEquals(2, attempted.get(), "execution must stop at the failing tick");
        assertEquals(1, phase.executed.size(), "only the tick that returned normally is executed");
        assertSame(ticks.get(0), phase.executed.peek(), "the completed tick must be the first one");
        assertSame(boom, phase.failure.get(), "the original failure must be retained");
    }

    @Test
    public void tickMutatingThenThrowingIsNotRetried() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        List<ScheduledTick<Block>> ticks = ticks(2);
        AtomicInteger mutations = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();

        ParallelRedstone.runRegion(phase, ticks, (pos, type) -> {
            attempts.incrementAndGet();
            mutations.incrementAndGet(); // world mutation happens before the failure
            throw new IllegalStateException("partial mutation");
        });

        assertEquals(1, attempts.get(), "a partially executed tick must never be retried");
        assertEquals(1, mutations.get());
        assertTrue(phase.executed.isEmpty(), "a partially executed tick must not be reported completed");
        assertNotNull(phase.failure.get());
    }

    @Test
    public void regionFailureStopsUnstartedRegions() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 2);
        RuntimeException boom = new RuntimeException("region A");
        ParallelRedstone.runRegion(phase, ticks(2), (pos, type) -> {
            throw boom;
        });

        AtomicInteger secondRegionAttempts = new AtomicInteger();
        ParallelRedstone.runRegion(phase, ticks(2), (pos, type) -> secondRegionAttempts.incrementAndGet());

        assertEquals(0, secondRegionAttempts.get(), "an unstarted region must not run after a failure");
        assertSame(boom, phase.failure.get());
        assertEquals(0, phase.executed.size());
    }

    @Test
    public void concurrentFailuresAggregateAsSuppressed() throws Exception {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 2);
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        CountDownLatch bothInside = new CountDownLatch(2);

        Runnable regionA = () -> ParallelRedstone.runRegion(phase, ticks(1), (pos, type) -> {
            bothInside.countDown();
            await(bothInside);
            throw first;
        });
        Runnable regionB = () -> ParallelRedstone.runRegion(phase, ticks(1), (pos, type) -> {
            bothInside.countDown();
            await(bothInside);
            throw second;
        });
        Thread threadA = new Thread(regionA, "lifecycle-region-a");
        Thread threadB = new Thread(regionB, "lifecycle-region-b");
        threadA.start();
        threadB.start();
        threadA.join(5_000);
        threadB.join(5_000);

        Throwable primary = phase.failure.get();
        assertNotNull(primary);
        Throwable other = primary == first ? second : first;
        boolean suppressed = false;
        for (Throwable candidate : primary.getSuppressed()) {
            suppressed |= candidate == other;
        }
        assertTrue(suppressed, "the second failure must be attached as a suppressed exception");
    }

    // ------------------------------------------------------ quiescence

    @Test
    public void quiescenceIgnoresInterruptsUntilWorkersFinishAndRestoresFlag() throws Exception {
        AtomicInteger remaining = new AtomicInteger(1);
        Thread testThread = Thread.currentThread();
        Thread worker = new Thread(() -> {
            LockSupport.parkNanos(20_000_000L);
            testThread.interrupt();
            // Give the main thread many pump iterations after the interrupt. If
            // quiescence returned early, the remaining counter would still be 1.
            LockSupport.parkNanos(100_000_000L);
            remaining.decrementAndGet();
        }, "lifecycle-quiescence-interrupter");
        worker.setDaemon(true);
        worker.start();

        Throwable failure = ParallelRedstone.awaitQuiescence(
                remaining, () -> LockSupport.parkNanos(1_000_000L));
        worker.join(5_000);

        assertNull(failure, "an interrupt is not a worker failure");
        assertEquals(0, remaining.get(), "quiescence must never return while work remains");
        assertTrue(Thread.currentThread().isInterrupted(),
                "the interrupt status must be restored after quiescence");
        assertTrue(Thread.interrupted(), "clear the flag for the rest of the test JVM");
    }

    @Test
    public void quiescenceRetainsFirstPumpFailureAndSuppressesLaterOnes() {
        AtomicInteger remaining = new AtomicInteger(1);
        AtomicInteger pumps = new AtomicInteger();
        RuntimeException first = new RuntimeException("first pump failure");
        RuntimeException second = new RuntimeException("second pump failure");

        Throwable failure = ParallelRedstone.awaitQuiescence(remaining, () -> {
            if (pumps.getAndIncrement() == 0) {
                throw first;
            }
            remaining.set(0);
            throw second;
        });

        assertSame(first, failure, "the first failure is the one reported");
        assertEquals(1, first.getSuppressed().length, "later failures must not be lost");
        assertSame(second, first.getSuppressed()[0]);
        assertEquals(0, remaining.get());
    }

    // --------------------------------------------------- willTickThisTick

    @Test
    public void willTickThisTickPreservesTypeAndExecutingSemantics() {
        ParallelRedstone.Phase phase = new ParallelRedstone.Phase(null, null, 1);
        BlockPos pos = new BlockPos(4, 70, 9);
        var wire = Blocks.REDSTONE_WIRE;
        var repeater = Blocks.REPEATER;
        phase.remaining.put(new ParallelRedstone.Phase.TickKey(pos.asLong(), wire), Boolean.TRUE);
        phase.remaining.put(new ParallelRedstone.Phase.TickKey(pos.asLong(), repeater), Boolean.TRUE);

        ParallelRedstone.PhaseContext context = new ParallelRedstone.PhaseContext(null, null, phase);
        ParallelRedstone.PhaseContext previous = ParallelRedstone.installContextForTest(context);
        try {
            // Different tick types at the same position keep separate identities.
            assertTrue(ParallelRedstone.willTickThisTick(null, pos, wire));
            assertTrue(ParallelRedstone.willTickThisTick(null, pos, repeater));
            assertFalse(ParallelRedstone.willTickThisTick(null, pos, Blocks.STONE));
            assertFalse(ParallelRedstone.willTickThisTick(null, pos.above(), wire));

            // Removing one identity leaves the other due.
            phase.remaining.remove(new ParallelRedstone.Phase.TickKey(pos.asLong(), wire));
            assertFalse(ParallelRedstone.willTickThisTick(null, pos, wire));
            assertTrue(ParallelRedstone.willTickThisTick(null, pos, repeater));

            // The currently executing tick answers false, every other due tick true.
            List<ScheduledTick<Block>> due = List.of(new ScheduledTick<>(repeater, pos, 0L, 0L));
            ParallelRedstone.runRegion(phase, due, (tickPos, type) ->
                    assertFalse(ParallelRedstone.willTickThisTick(null, tickPos, type),
                            "the currently executing tick must not report as pending"));
            assertFalse(ParallelRedstone.willTickThisTick(null, pos, repeater),
                    "a completed tick must not report as pending");
        } finally {
            ParallelRedstone.installContextForTest(previous);
        }

        // Outside a parallel phase the hook must defer to vanilla.
        assertNull(ParallelRedstone.willTickThisTick(null, pos, wire));
    }

    // ------------------------------------------------------ level lifecycle

    @Test
    public void releaseWaitsForTheActivePhaseWithoutSpinning() throws Exception {
        ParallelRedstone.LevelState state = new ParallelRedstone.LevelState(null);
        ParallelRedstone.Phase phase = state.beginPhase(null, 1);
        assertNotNull(phase, "an active level must admit a phase");
        assertNull(state.beginPhase(null, 1), "a second concurrent phase must be refused");

        AtomicBoolean released = new AtomicBoolean();
        Thread releaser = new Thread(() -> {
            state.release();
            released.set(true);
        }, "lifecycle-releaser");
        releaser.setDaemon(true);
        releaser.start();

        // The releaser must be blocked on the lifecycle monitor, not finishing early.
        Thread.sleep(100);
        assertFalse(released.get(), "release must wait for the active phase");

        state.endPhase(phase);
        releaser.join(5_000);
        assertTrue(released.get(), "release must complete once the phase ends");
        assertNull(state.beginPhase(null, 1), "a closed level must refuse new phases");

        // Idempotent repeated shutdown.
        state.release();
        state.release();
        assertNull(state.beginPhase(null, 1));
    }

    @Test
    public void releaseFromTheOwnerThreadDefersCleanupUntilPhaseEnd() {
        ParallelRedstone.LevelState state = new ParallelRedstone.LevelState(null);
        ParallelRedstone.Phase phase = state.beginPhase(null, 1);
        assertNotNull(phase);

        // A close triggered from inside the phase's own thread must not wait for itself.
        state.release();
        assertNull(state.beginPhase(null, 1), "closing must immediately refuse new phases");
        state.endPhase(phase);
        assertNull(state.beginPhase(null, 1), "cleanup must complete when the phase ends");
        state.release();
    }

    // ------------------------------------------------------------- helpers

    private static List<ScheduledTick<Block>> ticks(int count) {
        List<ScheduledTick<Block>> ticks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ticks.add(new ScheduledTick<>(null, new BlockPos(i, 64, 0), 0L, i));
        }
        return ticks;
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("synchronization latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
