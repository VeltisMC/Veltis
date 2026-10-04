package org.veltismc.world.chunk;

import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;

/**
 * Region-bound unload job.
 *
 * <p>Safety rules (Phase A hardening):
 * <ul>
 *   <li>A chunk being ticked (SIMULATING) is never stolen from the tick: the
 *       request is recorded and the region tick completes the unload on the owner
 *       thread after its work (see {@code RegionTickJob}).</li>
 *   <li>A chunk inside the generation pipeline is cancelled and left to the
 *       stage chain: {@link ChunkStageJob} observes the cancellation and performs
 *       the unload itself. The final unload only proceeds once no stage job is in
 *       flight, so a stage job can never write into a recycled section.</li>
 *   <li>Dirty chunks are routed through the save pipeline (SAVING state); the
 *       save-completion job performs the final UNLOADING -&gt; UNLOADED step.</li>
 * </ul>
 * All decisions are guarded by the chunk's {@link Chunk#epoch() load-cycle epoch}:
 * a stale job targeting a recycled pooled instance bails without touching it.
 */
public final class ChunkUnloadJob implements RegionJob {

    private final Chunk chunk;
    private final long epoch;

    public ChunkUnloadJob(Chunk chunk) {
        this.chunk = chunk;
        this.epoch = chunk.epoch();
    }

    @Override
    public String name() {
        return "chunk-unload";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.LOW;
    }

    @Override
    public boolean regionBound() {
        return true;
    }

    @Override
    public void execute(JobContext ctx) {
        if (ctx.cancelled() || !chunk.matches(epoch)) {
            return;
        }
        chunk.region().world().generation().cancel(chunk.pos());
        chunk.markUnloadRequested();
        ChunkState s = chunk.state();
        if (s == ChunkState.SAVING || s == ChunkState.UNLOADING || s == ChunkState.UNLOADED) {
            return;
        }
        if (s == ChunkState.SIMULATING) {
            // The region tick holds this chunk; its tail completes the unload.
            return;
        }
        if (s == ChunkState.GENERATING || s == ChunkState.STRUCTURES
            || s == ChunkState.BIOMES || s == ChunkState.LIGHTING) {
            // Only proceed when no generation stage job is in flight; otherwise
            // the cancelled stage chain performs the unload itself.
            if (chunk.region().world().generationImpl().isGenerating(chunk.pos())) {
                return;
            }
        }
        if (chunk.isDirty()) {
            claimForSave(s);
            return;
        }
        finishUnload();
    }

    /** Routes a dirty chunk through the save pipeline; completion performs the unload. */
    private void claimForSave(ChunkState s) {
        boolean claimed = (s == ChunkState.READY && chunk.trySet(ChunkState.READY, ChunkState.SAVING))
            || (s == ChunkState.DIRTY && chunk.trySet(ChunkState.DIRTY, ChunkState.SAVING));
        // Schedule even if the claim failed: the concurrent claimer (save batch or
        // tick re-dirty path) will write the chunk, and the save-completion job
        // honors unloadRequested afterwards.
        chunk.region().world().saves().scheduleSave(chunk.pos());
        if (claimed) {
            chunk.region().world().savesImpl().drainIfIdle();
        }
    }

    private void finishUnload() {
        ChunkState[] candidates = {
            ChunkState.READY, ChunkState.LOADING, ChunkState.GENERATING, ChunkState.STRUCTURES,
            ChunkState.BIOMES, ChunkState.LIGHTING
        };
        for (ChunkState c : candidates) {
            if (chunk.trySet(c, ChunkState.UNLOADING)) {
                if (chunk.trySet(ChunkState.UNLOADING, ChunkState.UNLOADED)) {
                    chunk.region().removeChunk(chunk);
                }
                return;
            }
        }
        // READY was taken by the tick between the state read and the CAS: the tick
        // tail observes unloadRequested and completes the unload.
    }
}
