package org.veltismc.veltis.server.internal;

import org.veltismc.veltis.api.definition.WorldDefinition;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface InternalWorld {

    WorldDefinition definition();

    UUID uniqueId();

    String name();

    Envelope envelope();

    InternalChunkContainer chunkContainer();

    InternalEntityContainer entityContainer();

    Optional<InternalChunk> chunk(int x, int z);

    InternalChunk chunkOrLoad(int x, int z);

    void unloadChunk(int x, int z, boolean save);

    long time();

    void time(long ticks);

    long gameTime();

    boolean storm();

    void storm(boolean storm);

    Collection<? extends InternalEntity> entities();

    <T extends InternalEntity> Collection<T> entities(Class<T> type);

    Optional<InternalPlayer> nearestPlayer(double x, double y, double z, double radius);

    record Envelope(
        int minY,
        int maxY,
        int logicalHeight
    ) {

        public int height() {
            return maxY - minY;
        }

        public boolean containsY(int y) {
            return y >= minY && y < maxY;
        }

        public static final Envelope OVERWORLD = new Envelope(-64, 320, 384);
        public static final Envelope NETHER = new Envelope(0, 256, 256);
        public static final Envelope THE_END = new Envelope(0, 256, 256);
    }

    interface InternalChunkContainer {
        Collection<? extends InternalChunk> loaded();

        Optional<InternalChunk> at(int x, int z);

        InternalChunk load(int x, int z);

        boolean unload(int x, int z);

        boolean isLoaded(int x, int z);

        int count();
    }

    interface InternalEntityContainer {
        Collection<? extends InternalEntity> all();

        <T extends InternalEntity> Collection<T> all(Class<T> type);

        Optional<InternalEntity> byId(int id);

        Optional<InternalEntity> byUuid(UUID uuid);

        int count();
    }
}


