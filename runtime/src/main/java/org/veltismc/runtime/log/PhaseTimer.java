package org.veltismc.runtime.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Simple reusable timing for startup phases: {@link #start} logs the phase
 * beginning immediately, then {@link #complete} logs the same phase with a
 * human-readable elapsed time — {@code 42ms} for short phases,
 * {@code 1.243s} (three decimals, like vanilla's Done line) for longer ones.
 *
 * <p>Every message goes through Log4j2, the one logging system Minecraft and
 * VeltisMC share, so phases read like {@code [15:52:26 INFO]: [VeltisMC]
 * World engine initialized (42ms)}.
 */
public final class PhaseTimer {

    private static final Logger LOG = LogManager.getLogger(PhaseTimer.class);

    private final long startNanos = System.nanoTime();

    private PhaseTimer() {}

    /** Starts a phase and logs its beginning immediately. */
    public static PhaseTimer start(String message) {
        LOG.info(message);
        return new PhaseTimer();
    }

    /** Logs {@code <message> (<elapsed>)} — the phase's real completion. */
    public void complete(String message) {
        LOG.info("{} ({})", message, elapsed());
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
