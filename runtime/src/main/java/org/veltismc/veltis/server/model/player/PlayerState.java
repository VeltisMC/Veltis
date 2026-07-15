package org.veltismc.veltis.server.model.player;

/**
 * Connection lifecycle state for a player.
 *
 * <p>Valid transitions:
 * <pre>
 * OFFLINE → CONNECTING → ONLINE → DISCONNECTING → OFFLINE
 *                      ↘          ↘
 *                        DISCONNECTING
 * </pre>
 */
public enum PlayerState {

    OFFLINE,
    CONNECTING,
    ONLINE,
    DISCONNECTING;

    /**
     * Returns true if this state represents an active connection.
     */
    public boolean isActive() {
        return this == CONNECTING || this == ONLINE;
    }

    /**
     * Returns true if a transition from this state to the target is valid.
     */
    public boolean allowsTransitionTo(PlayerState target) {
        return switch (this) {
            case OFFLINE -> target == CONNECTING;
            case CONNECTING -> target == ONLINE || target == DISCONNECTING;
            case ONLINE -> target == DISCONNECTING;
            case DISCONNECTING -> target == OFFLINE;
        };
    }
}


