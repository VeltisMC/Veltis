package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockPos;

/**
 * A queued neighbor-change notification: {@code from} changed, so {@code pos}
 * should be re-evaluated. Drained by the region tick, bounded per tick by the
 * configured cap.
 */
public record BlockUpdateJob(BlockPos pos, BlockPos from) {

    public static BlockUpdateJob of(BlockPos pos, BlockPos from) {
        return new BlockUpdateJob(pos, from);
    }
}
