package org.veltismc.runtime.container;

import org.veltismc.runtime.api.ChunkDefinition;
import org.veltismc.runtime.api.EntityDefinition;
import org.veltismc.runtime.api.WorldDefinition;
import org.veltismc.runtime.internal.InternalChunk;
import org.veltismc.runtime.model.world.InternalWorld;
import org.veltismc.runtime.internal.InternalEntity;
import org.veltismc.runtime.internal.InternalPlayer;
import org.veltismc.runtime.model.world.WorldProperties;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public final class DefaultWorldContainer implements WorldContainer {

    private final ConcurrentMap<UUID, org.veltismc.runtime.internal.InternalWorld> byUuid;
    private final ConcurrentMap<String, org.veltismc.runtime.internal.InternalWorld> byName;
    private final List<org.veltismc.runtime.internal.InternalWorld> ordered;

    public DefaultWorldContainer() {
        this.byUuid = new ConcurrentHashMap<>();
        this.byName = new ConcurrentHashMap<>();
        this.ordered = new CopyOnWriteArrayList<>();
    }

    @Override
    public org.veltismc.runtime.internal.InternalWorld add(org.veltismc.runtime.internal.InternalWorld world) {
        byUuid.put(world.uniqueId(), world);
        byName.put(world.name().toLowerCase(), world);
        ordered.add(world);
        return world;
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalWorld> remove(UUID uuid) {
        return Optional.ofNullable(byUuid.remove(uuid))
            .map(world -> {
                byName.remove(world.name().toLowerCase());
                ordered.remove(world);
                return world;
            });
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalWorld> remove(String name) {
        return Optional.ofNullable(byName.remove(name.toLowerCase()))
            .map(world -> {
                byUuid.remove(world.uniqueId());
                ordered.remove(world);
                return world;
            });
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalWorld> byUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalWorld> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase()));
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalWorld> byIndex(int index) {
        if (index >= 0 && index < ordered.size()) {
            return Optional.of(ordered.get(index));
        }
        return Optional.empty();
    }

    @Override
    public Collection<org.veltismc.runtime.internal.InternalWorld> all() {
        return Collections.unmodifiableCollection(ordered);
    }

    @Override
    public Stream<org.veltismc.runtime.internal.InternalWorld> stream() {
        return ordered.stream();
    }

    @Override
    public int count() {
        return ordered.size();
    }

    @Override
    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    @Override
    public boolean contains(UUID uuid) {
        return byUuid.containsKey(uuid);
    }

    @Override
    public boolean contains(String name) {
        return byName.containsKey(name.toLowerCase());
    }

    public static final class AdaptedInternal implements org.veltismc.runtime.internal.InternalWorld {

        private final InternalWorld delegate;

        public AdaptedInternal(InternalWorld delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public WorldDefinition definition() {
            var props = delegate.properties();
            var env = switch (props.environment()) {
                case WorldProperties.ENV_NETHER -> WorldDefinition.Environment.NETHER;
                case WorldProperties.ENV_THE_END -> WorldDefinition.Environment.THE_END;
                case WorldProperties.ENV_CUSTOM -> WorldDefinition.Environment.CUSTOM;
                default -> WorldDefinition.Environment.NORMAL;
            };
            return new WorldDefinition() {
                @Override public UUID uniqueId() { return delegate.uniqueId(); }
                @Override public String name() { return delegate.name(); }
                @Override public Environment environment() { return env; }
                @Override public long seed() { return props.seed(); }
                @Override public int maxHeight() { return props.maxY(); }
                @Override public int minHeight() { return props.minY(); }
                @Override public int logicalHeight() { return props.logicalHeight(); }
                @Override public long time() { return delegate.time(); }
                @Override public long gameTime() { return delegate.gameTime(); }
                @Override public boolean storm() { return delegate.storm(); }
                @Override public boolean thundering() { return delegate.thundering(); }
                @Override public int raintime() { return 0; }
                @Override public int thunderTime() { return 0; }
                @Override public boolean pvpAllowed() { return props.pvpAllowed(); }
                @Override public boolean monstersAllowed() { return props.allowMonsters(); }
                @Override public boolean animalsAllowed() { return props.allowAnimals(); }
                @Override public Collection<? extends ChunkDefinition> loadedChunks() { return List.of(); }
                @Override public Collection<? extends EntityDefinition> entities() { return List.of(); }
            };
        }

        @Override
        public UUID uniqueId() { return delegate.uniqueId(); }

        @Override
        public String name() { return delegate.name(); }

        @Override
        public Envelope envelope() {
            var env = delegate.properties().environment();
            if (WorldProperties.ENV_NETHER.equals(env)) return Envelope.NETHER;
            if (WorldProperties.ENV_THE_END.equals(env)) return Envelope.THE_END;
            return Envelope.OVERWORLD;
        }

        @Override
        public InternalChunkContainer chunkContainer() { return null; }

        @Override
        public InternalEntityContainer entityContainer() { return null; }

        @Override
        public Optional<InternalChunk> chunk(int x, int z) {
            return Optional.empty();
        }

        @Override
        public InternalChunk chunkOrLoad(int x, int z) {
            return null;
        }

        @Override
        public void unloadChunk(int x, int z, boolean save) {
        }

        @Override
        public long time() { return delegate.time(); }

        @Override
        public void time(long ticks) { delegate.time(ticks); }

        @Override
        public long gameTime() { return delegate.gameTime(); }

        @Override
        public boolean storm() { return delegate.storm(); }

        @Override
        public void storm(boolean storm) { delegate.storm(storm); }

        @Override
        public Collection<? extends InternalEntity> entities() { return List.of(); }

        @Override
        public <T extends InternalEntity> Collection<T> entities(Class<T> type) { return List.of(); }

        @Override
        public Optional<InternalPlayer> nearestPlayer(double x, double y, double z, double radius) {
            return Optional.empty();
        }
    }
}


