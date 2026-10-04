package org.veltismc.world.api;

import java.util.concurrent.CompletableFuture;

/**
 * Persistence SPI for chunk data. Hosts provide their own storage (region files,
 * databases, S3, ...); the engine provides an in-memory implementation by default.
 */
public interface ChunkStore {

    /** Loads raw chunk data, or {@code null} if the chunk does not exist yet. */
    CompletableFuture<byte[]> load(ChunkPos pos);

    /** Persists raw chunk data. */
    CompletableFuture<Void> save(ChunkPos pos, byte[] data);

    /** Deletes stored data for a chunk. */
    CompletableFuture<Void> delete(ChunkPos pos);
}
