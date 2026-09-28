package org.veltismc.runtime.model.entity;

import org.veltismc.runtime.model.world.InternalWorld;

import java.util.Optional;
import java.util.UUID;

/**
 * VeltisMC's game model representation of an entity.
 *
 * <p>Combines entity identity ({@link EntityIdentifier}), type
 * ({@link EntityType}), lifecycle state ({@link EntityState}),
 * position, and world reference.
 */
public interface InternalEntity {

    /**
     * The entity's compound identity (server ID + UUID).
     */
    EntityIdentifier identifier();

    /**
     * Shortcut for the server-assigned entity ID.
     */
    int entityId();

    /**
     * Shortcut for the persistent UUID.
     */
    UUID uniqueId();

    /**
     * The entity's type classification.
     */
    EntityType type();

    /**
     * The world this entity is in.
     */
    InternalWorld world();

    /**
     * The entity's current position.
     */
    Position position();

    /**
     * Sets the entity's position.
     */
    void position(Position position);

    /**
     * Whether the entity is on the ground.
     */
    boolean onGround();

    /**
     * Sets the on-ground state.
     */
    void onGround(boolean onGround);

    /**
     * Whether the entity is alive.
     */
    boolean alive();

    /**
     * Current entity lifecycle state.
     */
    EntityState state();

    /**
     * Updates the entity lifecycle state.
     */
    void state(EntityState newState);

    /**
     * Whether the entity is currently active in the world.
     */
    boolean isActive();

    /**
     * Returns the underlying server-internal entity if available.
     */
    Optional<org.veltismc.runtime.internal.InternalEntity> serverEntity();

    /**
     * 3D position with rotation for entities.
     */
    record Position(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {

        public Position withX(double x) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withY(double y) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withZ(double z) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withYaw(float yaw) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withPitch(float pitch) {
            return new Position(x, y, z, yaw, pitch);
        }

        public static Position at(double x, double y, double z) {
            return new Position(x, y, z, 0.0f, 0.0f);
        }

        public static final Position ZERO = new Position(0, 0, 0, 0, 0);
    }
}



