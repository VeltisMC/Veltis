package org.veltismc.world.lighting;

import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.chunk.ChunkSection;
import org.veltismc.world.util.ObjectPool;

import java.util.ArrayDeque;

/**
 * Region-bound relight job for a loaded chunk. Recomputes sky light (top-down
 * occlusion) and block light (flood fill from emitters) within the chunk. Light
 * does not cross chunk borders in this approximation, which keeps the pass
 * chunk-local and deterministic. Runs on the region owner, so it never races
 * the owning worker's delta writes.
 */
public final class LightJob implements RegionJob {

    private final Chunk chunk;

    public LightJob(Chunk chunk) {
        this.chunk = chunk;
    }

    @Override
    public String name() {
        return "chunk-relight";
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
        if (ctx.cancelled() || !chunk.isLoaded() || chunk.reached(org.veltismc.world.api.ChunkState.UNLOADING)) {
            chunk.region().world().lightingImpl().onLightDone(chunk.pos());
            return;
        }
        if (chunk.state() == org.veltismc.world.api.ChunkState.SAVING) {
            // Will be retried by the lighting engine once the save completes.
            chunk.region().world().lightingImpl().onLightDeferred(chunk.pos());
            return;
        }
        computeSkyLight();
        computeBlockLight();
        chunk.markLit();
        chunk.region().world().lightingImpl().onLightDone(chunk.pos());
    }

    private void computeSkyLight() {
        int minY = chunk.minSectionY() * 16;
        int maxY = (chunk.minSectionY() + chunk.sectionCount()) * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int level = 15;
                for (int y = maxY - 1; y >= minY; y--) {
                    ChunkSection s = sectionAt(y);
                    if (s == null) {
                        continue;
                    }
                    if (s.getBlock(x, y & 15, z).opaque()) {
                        level = 0;
                    }
                    s.setSkyLight(x, y & 15, z, level);
                }
            }
        }
    }

    private void computeBlockLight() {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int minY = chunk.minSectionY() * 16;
        int maxY = (chunk.minSectionY() + chunk.sectionCount()) * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    ChunkSection s = sectionAt(y);
                    if (s == null) {
                        continue;
                    }
                    int level = s.getBlockLight(x, y & 15, z);
                    if (level > 0 && !s.getBlock(x, y & 15, z).opaque()) {
                        queue.add(pack(x, y, z, level));
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            int packed = queue.poll();
            int x = (packed >>> 0) & 15;
            int y = (packed >>> 4) & 0x3FFFF;
            int z = (packed >>> 22) & 15;
            int level = (packed >>> 26) & 15;
            if (level <= 0) {
                continue;
            }
            for (int[] d : DELTAS) {
                int nx = x + d[0];
                int ny = y + d[1];
                int nz = z + d[2];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < minY || ny >= maxY) {
                    continue;
                }
                ChunkSection ns = sectionAt(ny);
                if (ns == null || ns.getBlock(nx, ny & 15, nz).opaque()) {
                    continue;
                }
                int next = level - 1;
                if (ns.getBlockLight(nx, ny & 15, nz) < next) {
                    ns.setBlockLight(nx, ny & 15, nz, next);
                    queue.add(pack(nx, ny, nz, next));
                }
            }
        }
    }

    private static final int[][] DELTAS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static int pack(int x, int y, int z, int level) {
        return (x & 15) | ((y & 0x3FFFF) << 4) | ((z & 15) << 22) | (level << 26);
    }

    private ChunkSection sectionAt(int y) {
        int sy = (y >> 4) - chunk.minSectionY();
        return sy >= 0 && sy < chunk.sectionCount() ? chunk.section(sy) : null;
    }
}
