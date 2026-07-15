package org.veltismc.veltis.server.internal;

import org.veltismc.veltis.api.definition.ChunkDefinition;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface InternalChunk {

    ChunkDefinition definition();

    int x();

    int z();

    InternalWorld world();

    ChunkState state();

    boolean loaded();

    CompletableFuture<Void> load();

    CompletableFuture<Void> unload(boolean save);

    CompletableFuture<Void> save();

    long inhabitedTime();

    void inhabitedTime(long time);

    Collection<? extends InternalEntity> entities();

    int entityCount();

    int tileEntityCount();

    enum ChunkState {
        UNLOADED,
        LOADING,
        LOADED,
        GENERATING,
        GENERATED,
        SAVING,
        UNLOADING
    }
}


