package org.veltismc.runtime.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Simple reusable timing for startup phases: {@link #start} records the phase
 * beginning, then {@link #complete} records the same phase with a
 * human-readable elapsed time — {@code 42ms} for short phases,
 * {@code 1.243s} (three decimals, like vanilla's Done line) for longer ones.
 *
 * <p>Both lines are logged at <em>debug</em> level: the timing is what matters,
 * and it belongs in a log file rather than on an operator's console. Normal
 * startup output stays vanilla's — {@code [15:52:26 INFO]: ...} — with no
 * startup-phase chatter interleaved into it.
 */
public final class PhaseTimer {

    private static final Logger LOG = LogManager.getLogger(PhaseTimer.class);

    private final long startNanos = System.nanoTime();

    private PhaseTimer() {}

    /** Starts a phase and records its beginning at debug level. */
    public static PhaseTimer start(String message) {
        LOG.debug(message);
        return new PhaseTimer();
    }

    /** Records {@code <message> (<elapsed>)} at debug level — the phase's real completion. */
    public void complete(String message) {
        LOG.debug("{} ({})", message, elapsed());
    }

    /** Human-readable elapsed time so far ({@code 42ms} / {@code 1.243s}). */
    public String elapsed() {
        return format(System.nanoTime() - startNanos);
    }

    /** Human-readable duration for one-shot measurements. */
    public static String format(long nanos) {
        var millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        if (millis < 1000) return millis + "ms";
        return String.format(Locale.ROOT, "%.3fs", millis / 1000.0);
    }
}
