package org.veltismc.world.api;

/**
 * A change to an entity's lifecycle within a chunk.
 *
 * <p>Entity ownership always belongs to a region; this delta is informational and is
 * applied by the owning region's worker. Cross-region moves are handled by the
 * migration protocol, never by direct ownership transfer from another worker.
 */
public record EntityDelta(EntityHandle entity, Type type, BlockPos position) implements ChunkDelta {

    public enum Type {
        SPAWN,
        MOVE,
        DESPAWN
    }

    public static EntityDelta spawn(EntityHandle entity, BlockPos position) {
        return new EntityDelta(entity, Type.SPAWN, position);
    }

    public static EntityDelta move(EntityHandle entity, BlockPos position) {
        return new EntityDelta(entity, Type.MOVE, position);
    }

    public static EntityDelta despawn(EntityHandle entity) {
        return new EntityDelta(entity, Type.DESPAWN, null);
    }
}
