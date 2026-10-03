package org.veltismc.world.generation;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkGenerator;
import org.veltismc.world.api.GenerationContext;
import org.veltismc.world.api.GenerationStage;

/**
 * Default {@link ChunkGenerator}: a flat world with a grass surface at y=0,
 * dirt below it, and stone to the world floor. Deterministic and thread-safe;
 * the seed is ignored because the terrain is uniform.
 */
public final class FlatWorldGenerator implements ChunkGenerator {

    private static final BlockState STONE = new BlockState(1, 0, true);
    private static final BlockState DIRT = new BlockState(2, 0, true);
    private static final BlockState GRASS = new BlockState(3, 0, true);

    private final int minY;
    private final int maxYExclusive;

    public FlatWorldGenerator(int minY, int maxYExclusive) {
        this.minY = minY;
        this.maxYExclusive = maxYExclusive;
    }

    @Override
    public void apply(GenerationStage stage, GenerationContext ctx) {
        if (stage != GenerationStage.NOISE) {
            return;
        }
        for (int y = minY; y < maxYExclusive; y++) {
            BlockState block;
            if (y < 0) {
                block = STONE;
            } else if (y == 0) {
                block = GRASS;
            } else if (y == 1) {
                block = DIRT;
            } else {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    ctx.setBlock(new BlockPos(ctx.pos().x() * 16 + x, y, ctx.pos().z() * 16 + z), block);
                }
            }
        }
    }
}
