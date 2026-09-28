package org.veltismc.world.chunk;

import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.core.WorldImpl;

/**
 * Region-bound load job: claims the chunk (UNLOADED -> LOADING), reads raw data
 * from the store asynchronously, then hands off to a decode job (migratable) and
 * a load-apply job (region-bound). Fresh chunks skip decoding and go straight to
 * generation.
 */
public final class ChunkLoadJob implements RegionJob {

    private final WorldImpl world;
    private final Chunk chunk;

    public ChunkLoadJob(WorldImpl world, Chunk chunk) {
        this.world = world;
        this.chunk = chunk;
    }

    @Override
    public String name() {
        return "chunk-load";
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
        if (ctx.cancelled()) {
            return;
        }
        if (!chunk.trySet(ChunkState.UNLOADED, ChunkState.LOADING)) {
            return;
        }
        world.store().load(chunk.pos()).whenComplete((data, err) -> {
            if (err != null) {
                System.err.println("[veltis-world-engine] store load failed for " + chunk.pos() + ": " + err);
                chunk.trySet(ChunkState.LOADING, ChunkState.UNLOADED);
                chunk.region().removeChunk(chunk);
                return;
            }
            if (data == null) {
                chunk.region().submit(new ChunkLoadApplyJob(chunk, null));
            } else {
                world.scheduler().schedule(new ChunkDecodeJob(world, chunk, data));
            }
        });
    }
}
