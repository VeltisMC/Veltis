package org.veltismc.veltis.server.model.entity;

/**
 * Lifecycle state of an entity.
 *
 * <p>Valid transitions:
 * <pre>
 * PENDING_SPAWN → SPAWNED → DESPAWNING → DESPAWNED
 *                ↘                    ↗
 *                  ERROR
 * </pre>
 */
public enum EntityState {

    PENDING_SPAWN,
    SPAWNED,
    DESPAWNING,
    DESPAWNED,
    ERROR;

    /**
     * Returns true if this entity is currently active in the world.
     */
    public boolean isActive() {
        return this == SPAWNED;
    }

    /**
     * Returns true if this state represents a terminal state.
     */
    public boolean isTerminal() {
        return this == DESPAWNED || this == ERROR;
    }
}


