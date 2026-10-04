package org.veltismc.world.generation;

import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.GenerationPipeline;
import org.veltismc.world.api.GenerationStage;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.chunk.ChunkLoadJob;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.util.SeedHash;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link GenerationPipeline}. Chunks flow through the six generation
 * stages (NOISE, SURFACE, STRUCTURES, BIOME, DECORATION, LIGHTING), each as an
 * independently schedulable migratable job, so generation spreads across all
 * workers. Stage exclusivity is enforced by the chunk state machine (CAS).
 */
public final class GenerationPipelineImpl implements GenerationPipeline {

    private static final GenerationStage[] STAGES = {
        GenerationStage.NOISE,
        GenerationStage.SURFACE,
        GenerationStage.STRUCTURES,
        GenerationStage.BIOME,
        GenerationStage.DECORATION,
        GenerationStage.LIGHTING
    };

    private final WorldImpl world;
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> cancelled = new ConcurrentHashMap<>();

    public GenerationPipelineImpl(WorldImpl world) {
        this.world = world;
    }

    @Override
    public void generate(ChunkPos pos) {
        Chunk chunk = world.chunkIfPresent(pos);
        if (chunk == null) {
            world.regionImpl(pos).chunk(pos);
            chunk = world.chunkIfPresent(pos);
        }
        final Chunk target = chunk;
        if (!target.isLoaded()) {
            final long epoch = target.epoch();
            world.scheduler().schedule(world.regionImpl(pos), new ChunkLoadJob(world, target));
            target.awaitState(epoch, ChunkState.READY).whenComplete((v, err) -> {
                if (err == null && target.matches(epoch)) {
                    generateChunk(target);
                }
                // On recycle (err != null) the old instance is gone; a future
                // generate()/chunk() call re-requests generation for the new cycle.
            });
            return;
        }
        generateChunk(target);
    }

    /** Starts the stage pipeline for an already-created chunk (GENERATING state). */
    public void generateChunk(Chunk chunk) {
        if (chunk.state() != ChunkState.GENERATING) {
            return;
        }
        if (inFlight.putIfAbsent(chunk.pos().key(), Boolean.TRUE) != null) {
            return;
        }
        cancelled.remove(chunk.pos().key());
        world.scheduler().schedule(new ChunkStageJob(world, chunk, GenerationStage.NOISE, this));
    }

    /**
     * Called by a stage job that bailed before completing its stage (cancelled,
     * stale epoch, or lost the state claim). Releases the pipeline claim and, if
     * an unload was requested while the stage was in flight, hands the final
     * unload back to the region owner. Without this a cancelled stage would
     * leave the chunk permanently stuck in a pre-READY state.
     */
    void onStageAbandoned(Chunk chunk) {
        inFlight.remove(chunk.pos().key());
        if (chunk.state() != org.veltismc.world.api.ChunkState.UNLOADED
            && chunk.isUnloadRequested()) {
            // Hand the pending unload back to the region owner, which re-evaluates
            // the state machine (the chunk may be dirty or mid-save). If this very
            // instance is about to be recycled, ChunkUnloadJob's epoch guard makes
            // the no-op safe.
            world.scheduler().schedule(chunk.region(),
                new org.veltismc.world.chunk.ChunkUnloadJob(chunk));
        }
    }

    void onStageComplete(Chunk chunk, GenerationStage stage) {
        if (stage == GenerationStage.LIGHTING) {
            inFlight.remove(chunk.pos().key());
            if (chunk.trySet(ChunkState.LIGHTING, ChunkState.READY)) {
                chunk.markLit();
                world.simulationImpl().onChunkReady(chunk.region());
            }
            return;
        }
        GenerationStage next = STAGES[stage.ordinal() + 1];
        world.scheduler().schedule(new ChunkStageJob(world, chunk, next, this));
    }

    boolean isCancelled(ChunkPos pos) {
        return cancelled.containsKey(pos.key());
    }

    long chunkSeed(ChunkPos pos) {
        return SeedHash.hash(world.config().worldSeed(), pos.x(), pos.z());
    }

    @Override
    public void cancel(ChunkPos pos) {
        cancelled.put(pos.key(), Boolean.TRUE);
        inFlight.remove(pos.key());
    }

    @Override
    public boolean isGenerating(ChunkPos pos) {
        if (inFlight.containsKey(pos.key())) {
            return true;
        }
        Chunk chunk = world.chunkIfPresent(pos);
        if (chunk == null) {
            return false;
        }
        ChunkState s = chunk.state();
        return s == ChunkState.GENERATING || s == ChunkState.STRUCTURES
            || s == ChunkState.BIOMES || s == ChunkState.LIGHTING;
    }

    @Override
    public int pendingCount() {
        return inFlight.size();
    }
}
