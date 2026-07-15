package org.veltismc.veltis.server.tick;

/**
 * Rolling metrics for the server tick engine.
 *
 * <p>Tracks per-tick and aggregate timing data including min/max/average
 * milliseconds per tick, lag spike counts, and total tick duration.
 */
public interface TickMetrics {

    /**
     * Returns the most recent tick context.
     */
    TickContext currentContext();

    /**
     * Returns the average tick duration in milliseconds over the
     * rolling measurement window.
     */
    double averageMs();

    /**
     * Returns the minimum tick duration in milliseconds recorded
     * since the engine started.
     */
    double minMs();

    /**
     * Returns the maximum tick duration in milliseconds recorded
     * since the engine started.
     */
    double maxMs();

    /**
     * Returns the total number of ticks executed.
     */
    long totalTicks();

    /**
     * Returns the number of lag spikes detected since start.
     * A lag spike occurs when a single tick exceeds 50ms.
     */
    int lagSpikeCount();

    /**
     * Returns the total nanoseconds spent in all lag spikes combined.
     */
    long lagSpikeTotalNs();

    /**
     * Returns the total nanoseconds spent executing all ticks.
     */
    long totalTickDurationNs();

    /**
     * Returns the tick number of the most recent lag spike,
     * or -1 if no lag spike has occurred.
     */
    long lastLagSpikeTick();
}


