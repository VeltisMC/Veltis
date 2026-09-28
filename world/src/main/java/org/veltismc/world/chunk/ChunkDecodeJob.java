package org.veltismc.world.chunk;

import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.core.WorldImpl;

/**
 * Migratable decode job: deserializes stored chunk bytes off the simulation
 * threads, then posts a region-bound load-apply job to the chunk's owner.
 */
public final class ChunkDecodeJob implements RegionJob {

    private final WorldImpl world;
    private final Chunk chunk;
    private final byte[] data;

    public ChunkDecodeJob(WorldImpl world, Chunk chunk, byte[] data) {
        this.world = world;
        this.chunk = chunk;
        this.data = data;
    }

    @Override
    public String name() {
        return "chunk-decode";
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
        if (ctx.cancelled()) {
            return;
        }
        try {
            ChunkSnapshot snapshot = world.codec().decode(data);
            chunk.region().submit(new ChunkLoadApplyJob(chunk, snapshot));
        } catch (Exception e) {
            System.err.println("[veltis-world-engine] decode failed for " + chunk.pos() + ": " + e);
            chunk.region().submit(new ChunkLoadApplyJob(chunk, null, true));
        }
    }
}
