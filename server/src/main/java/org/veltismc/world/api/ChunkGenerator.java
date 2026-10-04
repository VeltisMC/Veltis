package org.veltismc.world.api;

/**
 * Host-supplied world generator. Implementations produce terrain for each
 * {@link GenerationStage}; they must be thread-safe and deterministic for a
 * given seed.
 */
public interface ChunkGenerator {

    void apply(GenerationStage stage, GenerationContext ctx) throws Exception;
}
