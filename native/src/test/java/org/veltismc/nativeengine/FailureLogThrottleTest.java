package org.veltismc.nativeengine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warning schedule on its own, with a clock that can be moved by hand.
 *
 * <p>{@link NativeEngineTest} proves that a real failing library is bounded by
 * this; these tests prove the bound itself, including the window arithmetic that
 * a test would otherwise have to wait a minute to observe.
 */
class FailureLogThrottleTest {

    /** A clock the test advances explicitly, so no wall time is ever waited out. */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private long now;

        void advance(long nanos) {
            now += nanos;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }

    @Test
    void theFirstFailuresAreReportedImmediately() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(3, 60L * 1_000_000_000L, clock);

        for (var i = 1; i <= 3; i++) {
            var decision = throttle.record();
            assertTrue(decision.log(), "failure " + i + " is inside the burst");
            assertEquals(0L, decision.suppressedCount(), "nothing was hidden before the burst ran out");
        }
        assertEquals(0L, throttle.suppressedCount());
    }

    @Test
    void laterFailuresAreHeldBackUntilTheWindowExpires() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(1, 1_000L, clock);

        assertTrue(throttle.record().log());
        assertFalse(throttle.record().log(), "the second failure is inside the window");
        assertEquals(1L, throttle.suppressedCount());

        clock.advance(999L);
        assertFalse(throttle.record().log(), "a window that has not elapsed still suppresses");

        clock.advance(1L);
        var decision = throttle.record();
        assertTrue(decision.log(), "an elapsed window must report again");
        assertEquals(2L, decision.suppressedCount(), "the message speaks for both failures it held back");
        assertEquals(0L, throttle.suppressedCount(), "the drained count is not reported twice");
    }

    @Test
    void theSuppressedCountRidesInTheMessage() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(1, 10L, clock);

        assertEquals("", throttle.record().suppressedNote(), "the burst message has nothing to report");

        throttle.record();
        throttle.record();
        clock.advance(10L);

        var window = throttle.record();
        assertTrue(window.log());
        assertEquals(2L, window.suppressedCount());
        assertEquals(" (2 similar failures suppressed)", window.suppressedNote());
    }

    @Test
    void clearingTheBudgetRestartsTheBurst() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(2, 1_000L, clock);

        throttle.record();
        throttle.record();
        assertFalse(throttle.record().log(), "the burst is spent");
        assertEquals(1L, throttle.suppressedCount());

        throttle.clearBudget();
        assertTrue(throttle.record().log(), "a new generation reports from the beginning");
        assertEquals(0L, throttle.suppressedCount());
    }

    @Test
    void aClockThatMovedBackwardsDoesNotOpenTheWindow() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(1, 1_000L, clock);

        throttle.record();
        clock.advance(2_000L);
        assertTrue(throttle.record().log());
        clock.advance(-2_000L);
        assertFalse(throttle.record().log(),
                "a negative elapsed time is not evidence that a window expired");
    }

    @Test
    void anIntervalOfZeroReportsEveryFailureAfterTheBurst() {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(1, 0L, clock);

        throttle.record();
        assertTrue(throttle.record().log(), "every failure still gets a message, with the count attached");
        assertTrue(throttle.record().log());
    }

    @Test
    void concurrentFailuresShareOneWindow() throws Exception {
        var clock = new FakeClock();
        var throttle = new FailureLogThrottle(1, 10_000L, clock);
        var threads = 16;
        var rounds = 50;
        var calls = (long) threads * rounds;

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var logged = new AtomicInteger();
        var reportedSuppressed = new java.util.concurrent.atomic.AtomicLong();
        var errors = new Throwable[threads];

        for (var t = 0; t < threads; t++) {
            var index = t;
            var worker = new Thread(() -> {
                try {
                    start.await();
                    for (var i = 0; i < rounds; i++) {
                        var decision = throttle.record();
                        if (decision.log()) {
                            logged.incrementAndGet();
                            reportedSuppressed.addAndGet(decision.suppressedCount());
                        }
                    }
                } catch (Throwable error) {
                    errors[index] = error;
                } finally {
                    done.countDown();
                }
            }, "throttle-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent recording did not finish");
        for (var error : errors) {
            assertEquals(null, error);
        }

        assertEquals(calls, logged.get() + reportedSuppressed.get() + throttle.suppressedCount(),
            "every failure is either logged, counted inside a message, or still held back");
        assertTrue(logged.get() >= 1, "a failing implementation is never silenced completely");
        assertTrue(logged.get() < calls / 2,
                "a permanently failing implementation must not report once per call, got "
                        + logged.get() + " of " + calls);
    }
}
