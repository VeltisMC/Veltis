package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.region.RegionImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Region-bound explosion job: partitions the blast sphere into per-region
 * fragments and dispatches one {@link ExplosionFragmentJob} to each affected
 * region, so the explosion is applied by each region's owner without cross-thread
 * block access. The job itself runs on the region owning the blast center.
 */
public final class ExplosionJob implements RegionJob {

    private final WorldImpl world;
    private final BlockPos center;
    private final int radius;

    public ExplosionJob(WorldImpl world, BlockPos center, int radius) {
        this.world = world;
        this.center = center;
        this.radius = radius;
    }

    @Override
    public String name() {
        return "explosion";
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
        if (ctx.cancelled() || radius <= 0) {
            return;
        }
        Map<RegionImpl, List<BlockPos>> fragments = new HashMap<>();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    RegionImpl target = world.regionImpl(ChunkPos.containing(pos));
                    fragments.computeIfAbsent(target, k -> new ArrayList<>()).add(pos);
                }
            }
        }
        for (Map.Entry<RegionImpl, List<BlockPos>> e : fragments.entrySet()) {
            e.getKey().submit(new ExplosionFragmentJob(world, center, e.getValue()));
        }
    }
}
