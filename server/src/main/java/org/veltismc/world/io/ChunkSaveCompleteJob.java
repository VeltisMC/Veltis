package org.veltismc.world.io;

import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;

/**
 * Region-bound save completion (H1 fix): every state transition after a store
 * write runs on the chunk's owner thread instead of the store-completion thread.
 *
 * <p>Contract with {@link ChunkSaveJob}:
 * <ul>
 *   <li>{@code failed != null} — the encode or store write failed: SAVING -&gt; DIRTY
 *       and the chunk re-enters the save pipeline (bounded retry loop against
 *       permanent store failure is the store's/codec's concern, reported per attempt).</li>
 *   <li>{@code failed == null} — the write succeeded. A mutation after the snapshot
 *       re-dirtied the chunk: SAVING -&gt; DIRTY + follow-up save (the dirty-version
 *       guarantee: version 100 saved, version 101 stays pending). An unload request
 *       observed here performs the final SAVING -&gt; UNLOADING -&gt; UNLOADED. Otherwise
 *       SAVING -&gt; READY and any pending relight is queued.</li>
 * </ul>
 * Each completion job captures the chunk's load-cycle epoch: if the pooled instance
 * was recycled between scheduling and execution, the job bails (the write either
 * already landed or is irrelevant — the instance no longer represents that chunk).
 */
public final class ChunkSaveCompleteJob implements RegionJob {

    private final Chunk chunk;
    private final long epoch;
    private final Throwable failure;
    private final SaveServiceImpl saves;

    ChunkSaveCompleteJob(Chunk chunk, long epoch, Throwable failure, SaveServiceImpl saves) {
        this.chunk = chunk;
        this.epoch = epoch;
        this.failure = failure;
        this.saves = saves;
    }

    @Override
    public String name() {
        return "chunk-save-complete";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.HIGH;
    }

    @Override
    public boolean regionBound() {
        return true;
    }

    @Override
    public void execute(JobContext ctx) {
        saves.onSaveFinished();
        if (ctx.cancelled() || !chunk.matches(epoch)) {
            return;
        }
        if (failure != null) {
            if (chunk.trySet(ChunkState.SAVING, ChunkState.DIRTY)) {
                saves.scheduleSave(chunk.pos());
                saves.drainIfIdle();
            }
            return;
        }
        if (chunk.isDirty()) {
            // Mutated after the snapshot: this write is already obsolete.
            if (chunk.trySet(ChunkState.SAVING, ChunkState.DIRTY)) {
                saves.scheduleSave(chunk.pos());
                saves.drainIfIdle();
            }
            return;
        }
        if (chunk.isUnloadRequested()) {
            if (chunk.trySet(ChunkState.SAVING, ChunkState.UNLOADING)) {
                if (chunk.trySet(ChunkState.UNLOADING, ChunkState.UNLOADED)) {
                    chunk.region().removeChunk(chunk);
                }
            }
            return;
        }
        if (chunk.trySet(ChunkState.SAVING, ChunkState.READY)
            && chunk.region().world().lightingImpl().isPending(chunk.pos())) {
            chunk.region().world().lightingImpl().relight(chunk.pos());
        }
    }
}
