package org.veltismc.veltis.server.metrics;

/**
 * Aggregate server performance metrics.
 *
 * <p>Provides a combined view of tick engine performance (TPS, MSPT)
 * and server state (players, worlds, uptime, lag spikes).
 *
 * <p>A {@link #snapshot()} method provides a consistent,
 * immutable point-in-time view of all values.
 */
public interface ServerMetrics {

    /**
     * Returns the current ticks per second.
     */
    double tps();

    /**
     * Returns the current milliseconds per tick.
     */
    double msp();

    /**
     * Returns the minimum milliseconds per tick recorded.
     */
    double minMsp();

    /**
     * Returns the maximum milliseconds per tick recorded.
     */
    double maxMsp();

    /**
     * Returns the average milliseconds per tick over the rolling window.
     */
    double averageMsp();

    /**
     * Returns the current server tick number.
     */
    long currentTick();

    /**
     * Returns the number of connected players.
     */
    int playerCount();

    /**
     * Sets the number of connected players.
     */
    void playerCount(int count);

    /**
     * Returns the maximum player capacity.
     */
    int maxPlayers();

    /**
     * Sets the maximum player capacity.
     */
    void maxPlayers(int max);

    /**
     * Returns the number of loaded worlds.
     */
    int worldCount();

    /**
     * Sets the number of loaded worlds.
     */
    void worldCount(int count);

    /**
     * Returns the server uptime in milliseconds.
     */
    long uptimeMs();

    /**
     * Returns the number of lag spikes since server start.
     */
    int lagSpikeCount();

    /**
     * Returns the tick number of the most recent lag spike.
     */
    long lastLagSpikeTick();

    /**
     * Returns an immutable point-in-time snapshot of all metrics.
     */
    MetricsSnapshot snapshot();

    /**
     * Timing data for a single server tick.
     *
     * @param tickNumber  the tick number
     * @param elapsedNs   nanoseconds since the previous tick
     * @param durationNs  nanoseconds spent processing this tick
     * @param currentTps  instantaneous TPS for this tick
     */
    record TickTiming(long tickNumber, long elapsedNs, long durationNs, double currentTps) {

        /**
         * Returns the elapsed time in milliseconds.
         */
        public double elapsedMs() {
            return elapsedNs / 1_000_000.0;
        }

        /**
         * Returns the tick duration in milliseconds.
         */
        public double durationMs() {
            return durationNs / 1_000_000.0;
        }
    }

    /**
     * Consolidated snapshot of all server metrics at a point in time.
     *
     * @deprecated Use {@link MetricsSnapshot} instead.
     */
    @Deprecated
    record Snapshot(
        long tickNumber,
        double tps,
        double msp,
        double averageMsp,
        int playerCount,
        int maxPlayers,
        int worldCount,
        long uptimeMs
    ) {}
}


