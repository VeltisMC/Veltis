package org.veltismc.world.api;

/**
 * World generation stage. The generation pipeline runs chunks through these
 * stages; every stage is an independently schedulable job and may run on any
 * worker (chunk state transitions provide exclusivity).
 */
public enum GenerationStage {
    NOISE,
    SURFACE,
    STRUCTURES,
    BIOME,
    DECORATION,
    LIGHTING,
    READY
}
