package org.veltismc.veltis.server.model.chunk;

import org.veltismc.veltis.server.model.entity.InternalEntity;
import org.veltismc.veltis.server.model.world.InternalWorld;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * VeltisMC's game model representation of a chunk.
 *
 * <p>Represents a 16x16 horizontal section of a world with
 * lifecycle state tracking, coordinate access, and entity
 * containment.
 */
public interface InternalChunk {

    /**
     * The chunk's position in the world grid.
     */
    ChunkPosition position();

    /**
     * Shortcut for the chunk X coordinate.
     */
    int x();

    /**
     * Shortcut for the chunk Z coordinate.
     */
    int z();

    /**
     * The world this chunk belongs to.
     */
    InternalWorld world();

    /**
     * Current lifecycle state.
     */
    ChunkState state();

    /**
     * Updates the chunk state.
     */
    void state(ChunkState newState);

    /**
     * Whether the chunk is currently in a loaded state.
     */
    boolean loaded();

    /**
     * Asynchronously loads the chunk from storage.
     */
    CompletableFuture<Void> load();

    /**
     * Asynchronously unloads the chunk, optionally saving first.
     */
    CompletableFuture<Void> unload(boolean save);

    /**
     * Asynchronously saves the chunk to storage.
     */
    CompletableFuture<Void> save();

    /**
     * Total time players have spent in this chunk (ticks).
     */
    long inhabitedTime();

    /**
     * Sets the inhabited time.
     */
    void inhabitedTime(long time);

    /**
     * All entities currently in this chunk.
     */
    Collection<? extends InternalEntity> entities();

    /**
     * Number of entities in this chunk.
     */
    int entityCount();

    /**
     * Number of tile/block entities in this chunk.
     */
    int tileEntityCount();
}



