package org.veltismc.world.io;

import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

/**
 * Region-bound write of a single claimed chunk (SAVING state).
 *
 * <p>Phase A (H1): the owner-side part is only snapshot + encode handoff. The
 * store write completes asynchronously; all post-save state transitions run in a
 * region-bound {@link ChunkSaveCompleteJob} on the chunk's owner thread, never on
 * the store-completion thread. The dirty flag is cleared at snapshot time, so only
 * mutations after the snapshot re-dirty the chunk for a follow-up save (the
 * dirty-version guarantee).
 */
public final class ChunkSaveJob implements RegionJob {

    private final WorldImpl world;
    private final SaveServiceImpl saves;
    private final Chunk chunk;
    private final long epoch;

    public ChunkSaveJob(WorldImpl world, SaveServiceImpl saves, Chunk chunk) {
        this.world = world;
        this.saves = saves;
        this.chunk = chunk;
        this.epoch = chunk.epoch();
    }

    @Override
    public String name() {
        return "chunk-save";
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
        if (ctx.cancelled() || !chunk.matches(epoch) || chunk.state() != ChunkState.SAVING) {
            // Another job already handled this chunk, or the pooled instance was
            // recycled: nothing to write.
            saves.onSaveFinished();
            return;
        }
        try {
            ChunkSnapshot snapshot = chunk.snapshot();
            chunk.markClean();
            byte[] data = world.codec().encode(snapshot);
            world.store().save(chunk.pos(), data).whenComplete((v, err) ->
                chunk.region().submit(new ChunkSaveCompleteJob(chunk, epoch, err, saves)));
        } catch (Exception e) {
            chunk.region().submit(new ChunkSaveCompleteJob(chunk, epoch, e, saves));
        }
    }
}
