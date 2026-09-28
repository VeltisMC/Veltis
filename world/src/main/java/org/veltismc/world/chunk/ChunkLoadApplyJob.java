package org.veltismc.world.chunk;

import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;

/**
 * Region-bound load completion: applies a decoded snapshot (LOADING -> READY),
 * or starts generation for a fresh chunk (LOADING -> GENERATING). Runs on the
 * region owner, so snapshot writes never race the owner.
 */
public final class ChunkLoadApplyJob implements RegionJob {

    private final Chunk chunk;
    private final ChunkSnapshot snapshot;
    private final boolean failed;

    public ChunkLoadApplyJob(Chunk chunk, ChunkSnapshot snapshot) {
        this(chunk, snapshot, false);
    }

    public ChunkLoadApplyJob(Chunk chunk, ChunkSnapshot snapshot, boolean failed) {
        this.chunk = chunk;
        this.snapshot = snapshot;
        this.failed = failed;
    }

    @Override
    public String name() {
        return "chunk-load-apply";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.NORMAL;
    }

    @Override
    public boolean regionBound() {
        return true;
    }

    @Override
    public void execute(JobContext ctx) {
        if (ctx.cancelled() || failed) {
            chunk.trySet(ChunkState.LOADING, ChunkState.UNLOADED);
            chunk.region().removeChunk(chunk);
            return;
        }
        if (snapshot == null) {
            if (chunk.trySet(ChunkState.LOADING, ChunkState.GENERATING)) {
                chunk.region().world().generationImpl().generateChunk(chunk);
            }
            return;
        }
        chunk.applySnapshot(snapshot);
        if (chunk.trySet(ChunkState.LOADING, ChunkState.READY)) {
            chunk.region().world().simulationImpl().onChunkReady(chunk.region());
        } else {
            chunk.trySet(ChunkState.LOADING, ChunkState.UNLOADED);
            chunk.region().removeChunk(chunk);
        }
    }
}
