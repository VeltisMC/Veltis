package org.veltismc.runtime.model.player;

import org.veltismc.runtime.model.world.InternalWorld;

import java.util.Optional;
import java.util.UUID;

/**
 * VeltisMC's game model representation of a player.
 *
 * <p>Combines the player's stable identity ({@link PlayerProfile}),
 * mutable session data ({@link PlayerSession}), and lifecycle
 * state ({@link PlayerState}) into a single interface.
 *
 * <p>This is distinct from
 * {@link org.veltismc.runtime.internal.InternalPlayer} — the model
 * layer focuses on data representation, not server internals.
 * Runtime adapters bridge between Minecraft's player objects
 * and this model.
 */
public interface InternalPlayer {

    /**
     * The player's unique Mojang UUID.
     */
    UUID uniqueId();

    /**
     * Immutable profile data.
     */
    PlayerProfile profile();

    /**
     * Mutable session data.
     */
    PlayerSession session();

    /**
     * Current connection state.
     */
    PlayerState state();

    /**
     * Updates the player state with validation.
     */
    void state(PlayerState newState);

    /**
     * The world the player is currently in.
     */
    InternalWorld world();

    /**
     * The player's current position.
     */
    Position position();

    /**
     * Teleports the player to the given position.
     */
    void position(Position position);

    /**
     * Teleports the player to a world at the given position.
     */
    void teleport(InternalWorld world, Position position);

    /**
     * Current health (0 = dead, 20 = full).
     */
    float health();

    /**
     * Maximum health.
     */
    float maxHealth();

    /**
     * Current food level (0-20).
     */
    int foodLevel();

    /**
     * Current experience level.
     */
    int experienceLevel();

    /**
     * Current experience progress (0.0 - 1.0).
     */
    float experienceProgress();

    /**
     * Whether the player is sneaking.
     */
    boolean sneaking();

    /**
     * Whether the player is sprinting.
     */
    boolean sprinting();

    /**
     * Whether the player is flying.
     */
    boolean flying();

    /**
     * Whether the player is currently online.
     */
    boolean online();

    /**
     * Returns the original server-internal player if available.
     */
    Optional<org.veltismc.runtime.internal.InternalPlayer> serverPlayer();

    /**
     * 3D position with rotation.
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

        public static Position facing(double x, double y, double z, float yaw, float pitch) {
            return new Position(x, y, z, yaw, pitch);
        }

        public static final Position ZERO = new Position(0, 0, 0, 0, 0);
    }
}



