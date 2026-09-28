package org.veltismc.world.generation;

import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.GenerationStage;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

/**
 * One generation stage of one chunk. Migratable: may run on any worker; the
 * chunk's pre-READY state transitions (CAS) give this job exclusive write access
 * while it runs. Chunks flow GENERATING -&gt; STRUCTURES -&gt; BIOMES -&gt; LIGHTING,
 * one stage job per transition, handed off by the pipeline.
 *
 * <p>Phase A hardening: the job captures the chunk's load-cycle epoch and refuses
 * to touch a pooled instance that was recycled since construction. A job that
 * bails before acquiring (cancelled, stale, or lost the state claim) notifies the
 * pipeline via {@link GenerationPipelineImpl#onStageAbandoned(Chunk)} so the
 * pipeline's in-flight claim is released and a requested unload can proceed —
 * a stage job can never leave a chunk permanently stuck in GENERATING.
 */
public final class ChunkStageJob implements RegionJob {

    private final WorldImpl world;
    private final Chunk chunk;
    private final GenerationStage stage;
    private final GenerationPipelineImpl pipeline;
    private final long epoch;

    public ChunkStageJob(WorldImpl world, Chunk chunk, GenerationStage stage, GenerationPipelineImpl pipeline) {
        this.world = world;
        this.chunk = chunk;
        this.stage = stage;
        this.pipeline = pipeline;
        this.epoch = chunk.epoch();
    }

    @Override
    public String name() {
        return "chunk-generation-" + stage.name().toLowerCase();
    }

    @Override
    public JobPriority priority() {
        return JobPriority.NORMAL;
    }

    @Override
    public boolean regionBound() {
        return false;
    }

    @Override
    public void execute(JobContext ctx) throws Exception {
        if (ctx.cancelled() || pipeline.isCancelled(chunk.pos()) || !chunk.matches(epoch)) {
            pipeline.onStageAbandoned(chunk);
            return;
        }
        if (!acquire()) {
            // Lost the claim (chunk unloaded/recycled between scheduling and now).
            pipeline.onStageAbandoned(chunk);
            return;
        }
        world.generator().apply(stage, new GenerationContextImpl(world, chunk, stage, pipeline.chunkSeed(chunk.pos())));
        if (pipeline.isCancelled(chunk.pos())) {
            pipeline.onStageAbandoned(chunk);
            return;
        }
        pipeline.onStageComplete(chunk, stage);
    }

    /** Claims the chunk for this stage via the state machine. */
    private boolean acquire() {
        switch (stage) {
            case NOISE:
                return chunk.state() == ChunkState.GENERATING;
            case SURFACE:
                return chunk.trySet(ChunkState.GENERATING, ChunkState.GENERATING);
            case STRUCTURES:
                return chunk.trySet(ChunkState.GENERATING, ChunkState.STRUCTURES);
            case BIOME:
                return chunk.trySet(ChunkState.STRUCTURES, ChunkState.BIOMES);
            case DECORATION:
                return chunk.trySet(ChunkState.BIOMES, ChunkState.BIOMES);
            case LIGHTING:
                return chunk.trySet(ChunkState.BIOMES, ChunkState.LIGHTING);
            default:
                return false;
        }
    }
}
