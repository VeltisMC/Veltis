package org.veltismc.world.api;

/**
 * The lifecycle of a chunk. Workers advance chunks through these states;
 * each transition is validated by the state machine and guarded with a CAS,
 * so a chunk is exclusively owned by its current state's in-flight job.
 */
public enum ChunkState {
    UNLOADED,
    LOADING,
    GENERATING,
    STRUCTURES,
    BIOMES,
    LIGHTING,
    READY,
    SIMULATING,
    DIRTY,
    SAVING,
    UNLOADING
}
