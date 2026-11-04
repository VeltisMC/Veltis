package org.veltismc.nativeengine;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Decides whether one more occurrence of a repeated native failure may be
 * logged.
 *
 * <p>A native implementation that fails permanently — a library built for a
 * different engine version, a driver-level fault inside the vendor code — would
 * otherwise write a warning for every single call, and a server that transforms
 * chunks continuously turns that into an unreadable log and a real I/O cost. The
 * caller still has to be told, so the first few failures are reported
 * immediately and later ones are reported on a bounded schedule that says how
 * many messages were skipped.
 *
 * <p>The engine is never silenced: suppression only ever delays a message, and
 * the count of delayed messages rides along with the next one.
 */
final class FailureLogThrottle {

    static final int DEFAULT_BURST_LIMIT = 3;
    static final long DEFAULT_INTERVAL_NANOS = 60L * 1_000_000_000L;

    /** Whether this failure may be logged, and how many earlier ones it speaks for. */
    record Decision(boolean log, long suppressedCount) {

        /** Empty for the first burst of messages, a count for everything after it. */
        String suppressedNote() {
            return suppressedCount == 0L ? "" : " (" + suppressedCount + " similar failures suppressed)";
        }
    }

    private final long burstLimit;
    private final long intervalNanos;
    private final LongSupplier clock;
    private final AtomicLong loggedFailures = new AtomicLong();
    private final AtomicLong suppressedFailures = new AtomicLong();
    private final AtomicLong windowStart = new AtomicLong(Long.MIN_VALUE);

    FailureLogThrottle(long burstLimit, long intervalNanos, LongSupplier clock) {
        this.burstLimit = Math.max(1L, burstLimit);
        this.intervalNanos = Math.max(0L, intervalNanos);
        this.clock = clock;
    }

    /**
     * Records one failure. A success in between resets the budget, so a fault
     * that returns after a recovery is reported again from the beginning rather
     * than being hidden by the previous fault's suppression.
     */
    Decision record() {
        var now = clock.getAsLong();
        if (loggedFailures.incrementAndGet() <= burstLimit) {
            windowStart.set(now);
            return new Decision(true, suppressedFailures.getAndSet(0L));
        }
        while (true) {
            var previous = windowStart.get();
            // One comparison instead of a sign check plus a magnitude check: a
            // clock that moved backwards produces a negative elapsed time, which
            // is below any interval and therefore has not expired.
            if (now - previous < intervalNanos) {
                suppressedFailures.incrementAndGet();
                return new Decision(false, 0L);
            }
            if (windowStart.compareAndSet(previous, now)) {
                return new Decision(true, suppressedFailures.getAndSet(0L));
            }
        }
    }

    /**
     * Forgets the warning budget, so the next failure is reported from the
     * beginning again. Called when a native operation succeeds — the fault is
     * over — and when the engine starts a new generation with a different
     * library. Cheap enough for the success path.
     */
    void clearBudget() {
        if (loggedFailures.get() != 0L || suppressedFailures.get() != 0L) {
            loggedFailures.set(0L);
            suppressedFailures.set(0L);
        }
    }

    long loggedCount() {
        return loggedFailures.get();
    }

    long suppressedCount() {
        return suppressedFailures.get();
    }
}
