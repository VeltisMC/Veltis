package org.veltismc.runtime.model.world;

/**
 * Immutable configuration and metadata for a world.
 *
 * <p>Captures the world's dimension type, height bounds, seed,
 * difficulty, and game rules.
 *
 * @param identifier    the world's identity
 * @param environment   the dimension environment (overworld, nether, end, custom)
 * @param seed          the world seed
 * @param minY          minimum build height
 * @param maxY          maximum build height
 * @param logicalHeight height for portal/travel logic
 * @param difficulty    difficulty string (peaceful, easy, normal, hard)
 * @param allowMonsters whether monsters can spawn
 * @param allowAnimals  whether animals can spawn
 * @param pvpAllowed    whether PVP is enabled
 */
public record WorldProperties(
    WorldIdentifier identifier,
    String environment,
    long seed,
    int minY,
    int maxY,
    int logicalHeight,
    String difficulty,
    boolean allowMonsters,
    boolean allowAnimals,
    boolean pvpAllowed
) {

    public static final String ENV_OVERWORLD = "overworld";
    public static final String ENV_NETHER = "nether";
    public static final String ENV_THE_END = "the_end";
    public static final String ENV_CUSTOM = "custom";

    /**
     * Default overworld properties.
     */
    public static WorldProperties overworld(WorldIdentifier identifier, long seed) {
        return new WorldProperties(
            identifier, ENV_OVERWORLD, seed,
            -64, 320, 384,
            "easy", true, true, true
        );
    }

    /**
     * Default nether properties.
     */
    public static WorldProperties nether(WorldIdentifier identifier, long seed) {
        return new WorldProperties(
            identifier, ENV_NETHER, seed,
            0, 256, 256,
            "easy", true, false, true
        );
    }

    /**
     * Default end properties.
     */
    public static WorldProperties theEnd(WorldIdentifier identifier, long seed) {
        return new WorldProperties(
            identifier, ENV_THE_END, seed,
            0, 256, 256,
            "easy", true, false, false
        );
    }

    /**
     * Returns the world height (maxY - minY).
     */
    public int height() {
        return maxY - minY;
    }

    /**
     * Returns true if the given Y is within this world's build limits.
     */
    public boolean containsY(int y) {
        return y >= minY && y < maxY;
    }

    /**
     * Returns a copy with updated difficulty.
     */
    public WorldProperties withDifficulty(String difficulty) {
        return new WorldProperties(
            identifier, environment, seed,
            minY, maxY, logicalHeight,
            difficulty, allowMonsters, allowAnimals, pvpAllowed
        );
    }

    /**
     * Returns a copy with updated PVP setting.
     */
    public WorldProperties withPvpAllowed(boolean pvpAllowed) {
        return new WorldProperties(
            identifier, environment, seed,
            minY, maxY, logicalHeight,
            difficulty, allowMonsters, allowAnimals, pvpAllowed
        );
    }
}


