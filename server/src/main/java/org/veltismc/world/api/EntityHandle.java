package org.veltismc.world.api;

/**
 * A handle to a simulated entity owned by a region.
 *
 * <p>Ownership rules: exactly one region owns an entity at any time; only that
 * region's worker ticks it. When the entity crosses a region boundary, ownership
 * is dropped by the old region and a migration message transfers it to the
 * destination region. Never can two workers own one entity.
 */
public interface EntityHandle {

    /** Engine-assigned unique id. */
    long id();

    /** The host-supplied entity delegate. */
    SimulatedEntity entity();

    /** Current position (host-owned state, read by the owning worker). */
    BlockPos position();

    /** The region that currently owns this entity, or {@code null} while in flight. */
    Region region();

    /** Whether this entity currently has an owner. */
    boolean isOwned();
}
