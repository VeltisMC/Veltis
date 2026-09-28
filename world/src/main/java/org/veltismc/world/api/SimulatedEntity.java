package org.veltismc.world.api;

/**
 * Host-supplied entity behavior. The engine owns scheduling, isolation, and
 * region migration; the host owns the entity's internal state and its {@code tick}.
 *
 * <p>For determinism, entities are ticked in ascending id order within a region
 * tick. The entity should only mutate state reachable through {@link SimulationContext}.
 */
public interface SimulatedEntity {

    /** Current position; read by the engine after each tick to detect region crossing. */
    BlockPos position();

    /** Advance the entity by {@code dtMillis} simulation time. */
    void tick(SimulationContext ctx, long dtMillis);
}
