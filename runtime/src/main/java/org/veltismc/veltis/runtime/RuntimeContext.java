package org.veltismc.veltis.runtime;

/**
 * Immutable point-in-time snapshot of the Minecraft runtime state.
 *
 * <p>Captures runtime state, timing, version information, and
 * operational status for monitoring and diagnostics.
 *
 * @param state           the current runtime state
 * @param minecraftVersion the Minecraft version string (e.g. "26.2")
 * @param protocolVersion the network protocol version
 * @param uptimeMs        runtime uptime in milliseconds
 * @param tickCount       total ticks executed by the Minecraft server
 * @param playerCount     number of connected players
 * @param maxPlayers      maximum player capacity
 * @param isReady         whether the server is fully loaded and accepting connections
 * @param serverPort      the port the server is bound to
 */
public record RuntimeContext(
    RuntimeState state,
    String minecraftVersion,
    int protocolVersion,
    long uptimeMs,
    long tickCount,
    int playerCount,
    int maxPlayers,
    boolean isReady,
    int serverPort
) {

    private static final RuntimeContext EMPTY = new RuntimeContext(
        RuntimeState.CREATED, "", 0, 0, 0, 0, 0, false, 0
    );

    /**
     * Returns an empty context representing a pre-initialized runtime.
     */
    public static RuntimeContext empty() {
        return EMPTY;
    }

    /**
     * Returns a new context with an updated state.
     */
    public RuntimeContext withState(RuntimeState state) {
        return new RuntimeContext(
            state, minecraftVersion, protocolVersion, uptimeMs,
            tickCount, playerCount, maxPlayers, isReady, serverPort
        );
    }

    /**
     * Returns a new context with updated runtime metrics.
     */
    public RuntimeContext withMetrics(long uptimeMs, long tickCount) {
        return new RuntimeContext(
            state, minecraftVersion, protocolVersion, uptimeMs,
            tickCount, playerCount, maxPlayers, isReady, serverPort
        );
    }

    /**
     * Returns a new context with updated player information.
     */
    public RuntimeContext withPlayers(int playerCount, int maxPlayers) {
        return new RuntimeContext(
            state, minecraftVersion, protocolVersion, uptimeMs,
            tickCount, playerCount, maxPlayers, isReady, serverPort
        );
    }

    /**
     * Returns a new context with the ready flag set.
     */
    public RuntimeContext withReady(boolean ready) {
        return new RuntimeContext(
            state, minecraftVersion, protocolVersion, uptimeMs,
            tickCount, playerCount, maxPlayers, ready, serverPort
        );
    }
}


