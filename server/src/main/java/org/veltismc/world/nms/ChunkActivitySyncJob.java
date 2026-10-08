package org.veltismc.world.nms;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.region.RegionImpl;

/**
 * Applies one snapshot of Minecraft's ticking-chunk state to a single Veltis
 * region, executed region-bound so region activity changes on the region's
 * owner thread.
 *
 * <p>The job only consumes the desired state its caller observed — it never
 * asks Minecraft whether a chunk is ticking, and it never creates chunks or
 * regions. Chunks that have unloaded by the time the job runs are skipped by
 * {@link RegionImpl#setChunkActive}.
 */
public final class ChunkActivitySyncJob implements RegionJob {

    private final RegionImpl region;
    private final LongSet activeKeys;

    /**
     * @param region the region whose activity state is being applied
     * @param activeKeys Veltis {@link ChunkPos#key()} values of the chunks that
     *     should be active in this region (the caller's observed snapshot)
     */
    public ChunkActivitySyncJob(RegionImpl region, LongSet activeKeys) {
        this.region = region;
        this.activeKeys = activeKeys;
    }

    @Override
    public String name() {
        return "chunk-activity-sync";
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
        if (ctx.cancelled()) {
            return;
        }
        LongIterator wanted = activeKeys.iterator();
        while (wanted.hasNext()) {
            region.setChunkActive(ChunkPos.ofKey(wanted.nextLong()), true);
        }
        // Anything the region still considers active but Minecraft no longer
        // ticks is deactivated here.
        for (Chunk chunk : region.activeChunksSnapshot()) {
            if (!activeKeys.contains(chunk.pos().key())) {
                region.setChunkActive(chunk.pos(), false);
            }
        }
    }
}
