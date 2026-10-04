package org.veltismc.world.io;

import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Migratable save job: claims up to {@code batchSize} dirty chunks (SAVING state)
 * and hands each to a region-bound {@link ChunkSaveJob} that runs on the chunk's
 * owner thread. Re-submits itself while chunks remain.
 */
public final class SaveBatchJob implements RegionJob {

    private final WorldImpl world;
    private final SaveServiceImpl saves;

    public SaveBatchJob(WorldImpl world, SaveServiceImpl saves) {
        this.world = world;
        this.saves = saves;
    }

    @Override
    public String name() {
        return "chunk-save-batch";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.LOW;
    }

    @Override
    public boolean regionBound() {
        return false;
    }

    @Override
    public void execute(JobContext ctx) throws Exception {
        if (ctx.cancelled() || saves.isQuiescent()) {
            saves.completeFlushWaiters();
            return;
        }
        int batchSize = world.config().saveBatchSize();
        int drained = 0;
        List<Chunk> batch = new ArrayList<>(batchSize);
        for (ChunkPos pos : saves.pending().values()) {
            if (drained >= batchSize) {
                break;
            }
            Chunk chunk = world.chunkIfPresent(pos);
            if (chunk == null) {
                continue;
            }
            ChunkState s = chunk.state();
            // SAVING chunks were claimed by another requester (e.g. an unload job)
            // which delegated the write to the save pipeline: claim them here.
            boolean claimed = s == ChunkState.SAVING
                || (s == ChunkState.READY && chunk.trySet(ChunkState.READY, ChunkState.SAVING))
                || (s == ChunkState.SIMULATING && chunk.trySet(ChunkState.SIMULATING, ChunkState.SAVING))
                || (s == ChunkState.DIRTY && chunk.trySet(ChunkState.DIRTY, ChunkState.SAVING));
            if (claimed) {
                saves.pending().remove(chunk.pos().key());
                batch.add(chunk);
                drained++;
            }
        }
        for (Chunk chunk : batch) {
            saves.onSaveStarted();
            if (chunk.state() != ChunkState.SAVING) {
                saves.onSaveFinished();
                continue;
            }
            chunk.region().submit(new ChunkSaveJob(world, saves, chunk));
        }
        // Resubmit only while there is still work to claim. In-flight writes are
        // tracked by the save service, which completes flush waiters when they
        // finish; chunks re-dirtied during a write are re-queued by scheduleSave.
        if (!saves.pending().isEmpty()) {
            world.scheduler().schedule(new SaveBatchJob(world, saves));
        } else {
            saves.completeFlushWaiters();
        }
    }
}
