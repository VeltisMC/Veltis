package org.veltismc.veltis.server.model.chunk;

/**
 * Lifecycle state of a chunk.
 *
 * <p>Valid transitions:
 * <pre>
 * UNLOADED → LOADING → LOADED → UNLOADING → UNLOADED
 *                   ↘                    ↗
 *                     ERROR
 * </pre>
 *
 * <p>A chunk may pass through GENERATING → GENERATED between
 * LOADING and LOADED if being generated for the first time.
 */
public enum ChunkState {

    UNLOADED,
    LOADING,
    GENERATING,
    GENERATED,
    LOADED,
    SAVING,
    UNLOADING,
    ERROR;

    /**
     * Returns true if this state represents a loaded chunk.
     */
    public boolean isLoaded() {
        return this == LOADED || this == GENERATED;
    }

    /**
     * Returns true if this state represents any active state.
     */
    public boolean isActive() {
        return switch (this) {
            case UNLOADED, ERROR -> false;
            default -> true;
        };
    }
}


