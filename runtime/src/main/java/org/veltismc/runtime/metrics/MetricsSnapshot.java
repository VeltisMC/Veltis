package org.veltismc.runtime.metrics;

/**
 * Immutable point-in-time snapshot of server metrics.
 *
 * <p>Created by {@link ServerMetrics#snapshot()} to capture a consistent
 * view of the server's performance state at a given moment.
 *
 * @param currentTick     the current server tick number
 * @param tps             current ticks per second
 * @param msp             current milliseconds per tick
 * @param minMsp          minimum MSPT recorded
 * @param maxMsp          maximum MSPT recorded
 * @param averageMsp      average MSPT over the rolling window
 * @param playerCount     number of connected players
 * @param maxPlayers      maximum player capacity
 * @param worldCount      number of loaded worlds
 * @param uptimeMs        server uptime in milliseconds
 * @param lagSpikeCount   number of lag spikes since start
 * @param lastLagSpikeTick tick number of the most recent lag spike
 */
public record MetricsSnapshot(
    long currentTick,
    double tps,
    double msp,
    double minMsp,
    double maxMsp,
    double averageMsp,
    int playerCount,
    int maxPlayers,
    int worldCount,
    long uptimeMs,
    int lagSpikeCount,
    long lastLagSpikeTick
) {

    /**
     * Creates a new snapshot with an updated player count.
     */
    public MetricsSnapshot withPlayerCount(int playerCount) {
        return new MetricsSnapshot(
            currentTick, tps, msp, minMsp, maxMsp, averageMsp,
            playerCount, maxPlayers, worldCount, uptimeMs,
            lagSpikeCount, lastLagSpikeTick
        );
    }

    /**
     * Creates a new snapshot with an updated world count.
     */
    public MetricsSnapshot withWorldCount(int worldCount) {
        return new MetricsSnapshot(
            currentTick, tps, msp, minMsp, maxMsp, averageMsp,
            playerCount, maxPlayers, worldCount, uptimeMs,
            lagSpikeCount, lastLagSpikeTick
        );
    }
}


