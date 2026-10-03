package org.veltismc.world.chunk;

import org.veltismc.world.api.BlockDelta;
import org.veltismc.world.api.ChunkDelta;
import org.veltismc.world.api.EntityDelta;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.LightDelta;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.core.WorldImpl;

/**
 * Region-bound application of a {@link ChunkDelta}: applies the delta on the
 * owning worker and triggers a relight for block changes. Deltas targeting
 * unloaded chunks are dropped (documented: writes only apply to loaded chunks).
 */
public final class DeltaApplyJob implements RegionJob {

    private final WorldImpl world;
    private final ChunkDelta delta;

    public DeltaApplyJob(WorldImpl world, ChunkDelta delta) {
        this.world = world;
        this.delta = delta;
    }

    @Override
    public String name() {
        return "delta-apply";
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
        Chunk chunk = null;
        if (delta instanceof BlockDelta bd) {
            chunk = world.chunkIfPresent(bd.chunk());
        } else if (delta instanceof LightDelta ld) {
            chunk = world.chunkIfPresent(ld.chunk());
        } else if (delta instanceof EntityDelta) {
            return; // entity lifecycles are applied by the simulation layer
        }
        if (chunk == null || !chunk.isLoaded()) {
            return;
        }
        if (DeltaApplier.apply(chunk, delta) && delta instanceof BlockDelta) {
            world.lighting().relight(chunk.pos());
        }
    }
}
