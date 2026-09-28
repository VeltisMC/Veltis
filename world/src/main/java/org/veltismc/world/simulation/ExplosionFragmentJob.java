package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

import java.util.List;

/**
 * Region-bound fragment of an explosion: breaks every block in the fragment's
 * positions to air (through the delta path), queues relights, and notifies
 * neighbors. The job body runs on the fragment's region owner.
 */
public final class ExplosionFragmentJob implements RegionJob {

    private final WorldImpl world;
    private final BlockPos center;
    private final List<BlockPos> positions;

    public ExplosionFragmentJob(WorldImpl world, BlockPos center, List<BlockPos> positions) {
        this.world = world;
        this.center = center;
        this.positions = positions;
    }

    @Override
    public String name() {
        return "explosion-fragment";
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
        for (BlockPos pos : positions) {
            ChunkPos cp = ChunkPos.containing(pos);
            Chunk chunk = world.chunkIfPresent(cp);
            if (chunk == null || !chunk.isLoaded()) {
                continue;
            }
            if (chunk.setBlock(pos, BlockState.AIR)) {
                world.lighting().relight(cp);
                notifyNeighbors(pos);
            }
        }
    }

    private void notifyNeighbors(BlockPos pos) {
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};
        for (int i = 0; i < 6; i++) {
            BlockPos neighbor = pos.offset(dx[i], dy[i], dz[i]);
            ChunkPos ncp = ChunkPos.containing(neighbor);
            Chunk nChunk = world.chunkIfPresent(ncp);
            if (nChunk == null || !nChunk.isLoaded()) {
                continue;
            }
            var targetRegion = nChunk.region();
            targetRegion.updateQueue().add(new BlockUpdateJob(neighbor, pos));
        }
    }
}
