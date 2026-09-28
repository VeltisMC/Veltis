package org.veltismc.runtime.model.world;

import org.veltismc.runtime.model.chunk.ChunkPosition;
import org.veltismc.runtime.model.chunk.InternalChunk;
import org.veltismc.runtime.model.entity.InternalEntity;
import org.veltismc.runtime.model.player.InternalPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * VeltisMC's game model representation of a world.
 *
 * <p>Combines world identity ({@link WorldIdentifier}), properties
 * ({@link WorldProperties}), and lifecycle state ({@link WorldState})
 * with access to loaded chunks and entities.
 */
public interface InternalWorld {

    /**
     * The world's unique UUID.
     */
    UUID uniqueId();

    /**
     * The world's logical name.
     */
    String name();

    /**
     * The world's identity (UUID + name).
     */
    WorldIdentifier identifier();

    /**
     * Immutable world properties.
     */
    WorldProperties properties();

    /**
     * Current lifecycle state.
     */
    WorldState state();

    /**
     * Updates the world state with validation.
     */
    void state(WorldState newState);

    /**
     * Current world time in ticks.
     */
    long time();

    /**
     * Sets the world time.
     */
    void time(long ticks);

    /**
     * Game time (total ticks since world creation).
     */
    long gameTime();

    /**
     * Whether it is currently storming.
     */
    boolean storm();

    /**
     * Sets the storm state.
     */
    void storm(boolean storm);

    /**
     * Whether it is thundering.
     */
    boolean thundering();

    /**
     * Sets the thundering state.
     */
    void thundering(boolean thundering);

    /**
     * Returns a loaded chunk at the given position.
     */
    Optional<InternalChunk> chunk(ChunkPosition position);

    /**
     * Returns a loaded chunk at the given coordinates.
     */
    Optional<InternalChunk> chunk(int x, int z);

    /**
     * All currently loaded chunks.
     */
    Collection<InternalChunk> loadedChunks();

    /**
     * Number of loaded chunks.
     */
    int loadedChunkCount();

    /**
     * All entities currently in this world.
     */
    Collection<? extends InternalEntity> entities();

    /**
     * All entities of the given type.
     */
    <T extends InternalEntity> Collection<T> entities(Class<T> type);

    /**
     * All players currently in this world.
     */
    Collection<? extends InternalPlayer> players();

    /**
     * Finds the nearest player within the given radius.
     */
    Optional<InternalPlayer> nearestPlayer(double x, double y, double z, double radius);

    /**
     * Stream of all players in this world.
     */
    Stream<? extends InternalPlayer> playerStream();
}



