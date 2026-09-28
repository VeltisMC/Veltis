package org.veltismc.runtime.model.world;

/**
 * Lifecycle state of a world.
 *
 * <p>Valid transitions:
 * <pre>
 * UNLOADED → LOADING → LOADED → SAVING → UNLOADING → UNLOADED
 *                   ↘                    ↗
 *                     ERROR
 * </pre>
 */
public enum WorldState {

    UNLOADED,
    LOADING,
    LOADED,
    SAVING,
    UNLOADING,
    ERROR;

    /**
     * Returns true if this state represents an active world.
     */
    public boolean isActive() {
        return this == LOADING || this == LOADED;
    }

    /**
     * Returns true if a transition to the target state is valid.
     */
    public boolean allowsTransitionTo(WorldState target) {
        return switch (this) {
            case UNLOADED -> target == LOADING;
            case LOADING -> target == LOADED || target == ERROR || target == UNLOADING;
            case LOADED -> target == SAVING || target == UNLOADING;
            case SAVING -> target == LOADED || target == UNLOADING;
            case UNLOADING -> target == UNLOADED || target == ERROR;
            case ERROR -> target == UNLOADING || target == LOADING;
        };
    }
}


