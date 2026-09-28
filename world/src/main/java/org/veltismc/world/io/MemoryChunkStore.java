package org.veltismc.world.io;

import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkStore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link ChunkStore}. Data does not survive restarts; hosts
 * provide their own store (region files, databases, ...) via the persistence SPI.
 */
public final class MemoryChunkStore implements ChunkStore {

    private final ConcurrentHashMap<Long, byte[]> data = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<byte[]> load(ChunkPos pos) {
        return CompletableFuture.completedFuture(data.get(pos.key()));
    }

    @Override
    public CompletableFuture<Void> save(ChunkPos pos, byte[] bytes) {
        data.put(pos.key(), bytes);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> delete(ChunkPos pos) {
        data.remove(pos.key());
        return CompletableFuture.completedFuture(null);
    }

    public int size() {
        return data.size();
    }
}
