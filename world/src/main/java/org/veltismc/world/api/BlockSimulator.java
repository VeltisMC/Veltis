package org.veltismc.world.api;

/**
 * Host-supplied block behaviors: random ticks and neighbor change updates.
 *
 * <p>The engine schedules and isolates these callbacks; the host decides what a
 * block does. All state changes must go through {@link SimulationContext#setBlock}.
 */
public interface BlockSimulator {

    /** Called for each random-ticked block position. */
    default void onRandomTick(SimulationContext ctx, BlockPos pos, BlockState state) {
    }

    /** Called for each block-update drained from a region's update queue. */
    default void onNeighborChanged(SimulationContext ctx, BlockPos pos, BlockState state, BlockPos from) {
    }
}
