package org.veltismc.runtime.api;

public interface ChunkDefinition {

    int x();

    int z();

    WorldDefinition world();

    boolean loaded();

    long inhabitedTime();

    long lastAccessTime();

    int entityCount();

    int tileEntityCount();
}


