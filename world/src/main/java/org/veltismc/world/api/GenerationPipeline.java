package org.veltismc.world.api;

/**
 * The generation pipeline: chunks flow through load -> stages -> lighting -> ready.
 * Every stage is scheduled independently, so generation spreads across workers.
 */
public interface GenerationPipeline {

    /** Starts the pipeline for a chunk (load first if needed). Idempotent per chunk. */
    void generate(ChunkPos pos);

    /** Cancels any in-flight generation for a chunk. */
    void cancel(ChunkPos pos);

    boolean isGenerating(ChunkPos pos);

    /** Number of chunks currently in the pipeline. */
    int pendingCount();
}
