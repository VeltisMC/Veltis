package org.veltismc.world.api;

/**
 * An immutable change to the world, produced instead of direct chunk mutation.
 *
 * <p>Deltas are routed to the region that owns the target chunk, where they are
 * applied by that region's worker only. The engine never mutates chunks directly.
 */
public sealed interface ChunkDelta permits BlockDelta, EntityDelta, LightDelta {
}
