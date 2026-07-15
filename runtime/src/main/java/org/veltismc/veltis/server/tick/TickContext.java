package org.veltismc.veltis.server.tick;

/**
 * Immutable context provided to tick consumers on every server tick.
 *
 * <p>Contains the tick number, timing information, and current TPS
 * for each tick cycle. A tick is considered a lag spike when its
 * execution duration exceeds 50 milliseconds.
 *
 * @param tickNumber    sequential tick number (0-based)
 * @param startedAtNanos nanosecond timestamp when this tick started
 * @param elapsedNanos   nanoseconds elapsed since the previous tick started
 * @param currentTps     instantaneous TPS calculated from the elapsed time
 */
public record TickContext(
    long tickNumber,
    long startedAtNanos,
    long elapsedNanos,
    double currentTps
) {

    /**
     * Returns the tick duration in milliseconds.
     */
    public double elapsedMs() {
        return elapsedNanos / 1_000_000.0;
    }

    /**
     * Returns the tick start time in milliseconds.
     */
    public double startedAtMs() {
        return startedAtNanos / 1_000_000.0;
    }

    /**
     * Returns true if this tick exceeded the 50ms lag spike threshold.
     */
    public boolean isLagSpike() {
        return elapsedNanos > 50_000_000;
    }

    /**
     * Creates a tick context from the given values.
     */
    public static TickContext of(long tick, long startedAt, long elapsed, double tps) {
        return new TickContext(tick, startedAt, elapsed, tps);
    }
}


