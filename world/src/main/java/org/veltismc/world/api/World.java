package org.veltismc.world.api;

/**
 * A world: the top-level simulation container. Worlds own regions, which own
 * chunks, entities, and simulation state.
 *
 * <p>Everything in a world is reachable only through async handles and deltas;
 * there is no direct chunk mutation API.
 */
public interface World {

    String name();

    WorldConfig config();

    /** Returns the region owning the given chunk, creating it (and assigning a worker) if needed. */
    Region region(ChunkPos pos);

    /** Returns an async handle to the chunk, creating its region/chunk object lazily. */
    ChunkHandle chunk(ChunkPos pos);

    WorldSimulation simulation();

    LightingEngine lighting();

    SaveService saves();

    GenerationPipeline generation();

    /** Replaces the world generator used by the generation pipeline (default: flat world). */
    void setGenerator(ChunkGenerator generator);

    ChunkGenerator generator();

    /** Stops simulation and flushes all pending saves. */
    void close();
}
