package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.server.model.chunk.ChunkPosition;
import org.veltismc.veltis.server.model.chunk.InternalChunk;
import org.veltismc.veltis.server.model.entity.InternalEntity;
import org.veltismc.veltis.server.model.player.InternalPlayer;
import org.veltismc.veltis.server.model.world.InternalWorld;
import org.veltismc.veltis.server.model.world.WorldIdentifier;
import org.veltismc.veltis.server.model.world.WorldProperties;
import org.veltismc.veltis.server.model.world.WorldState;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public final class WorldRuntimeAdapter {

    private static final Logger LOG = System.getLogger(WorldRuntimeAdapter.class.getName());

    private static final String SERVER_LEVEL_CLASS = "net.minecraft.server.level.ServerLevel";

    private final ClassLoader classLoader;
    private final Object minecraftServer;
    private final CopyOnWriteArrayList<AdaptedWorld> worlds;

    private Method getAllLevelsMethod;

    public WorldRuntimeAdapter(Object minecraftServer) {
        this.minecraftServer = Objects.requireNonNull(minecraftServer, "minecraftServer");
        this.classLoader = getClass().getClassLoader();
        this.worlds = new CopyOnWriteArrayList<>();
    }

    public InternalWorld adapt(Object mojangLevel) {
        Objects.requireNonNull(mojangLevel, "mojangLevel");

        for (var adapted : worlds) {
            if (adapted.mojangLevel == mojangLevel) {
                return adapted;
            }
        }

        var identifier = extractIdentifier(mojangLevel);
        var properties = extractProperties(mojangLevel, identifier);

        var world = new AdaptedWorld(identifier, properties, mojangLevel);
        worlds.add(world);
        return world;
    }

    public List<Object> getAllLevels() {
        var result = new ArrayList<Object>();
        try {
            if (getAllLevelsMethod == null) {
                getAllLevelsMethod = findAccessibleMethod(
                    minecraftServer.getClass(), "getAllLevels");
            }
            if (getAllLevelsMethod != null) {
                var levels = getAllLevelsMethod.invoke(minecraftServer);
                if (levels instanceof Iterable<?> iterable) {
                    for (var level : iterable) {
                        if (level != null) result.add(level);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Failed to retrieve levels: {0}", e.getMessage());
        }
        return result;
    }

    public void clear() {
        worlds.clear();
    }

    public List<? extends InternalWorld> allAdapted() {
        return List.copyOf(worlds);
    }

    private WorldIdentifier extractIdentifier(Object mojangLevel) {
        try {
            var dimensionTypeMethod = findAccessibleMethod(mojangLevel.getClass(), "dimensionTypeRegistration");
            if (dimensionTypeMethod != null) {
                var dimensionReg = dimensionTypeMethod.invoke(mojangLevel);
                var keyMethod = dimensionReg.getClass().getMethod("key");
                var resourceKey = keyMethod.invoke(dimensionReg);
                var locationMethod = resourceKey.getClass().getMethod("location");
                var resourceLocation = locationMethod.invoke(resourceKey);
                var pathMethod = resourceLocation.getClass().getMethod("getPath");
                var name = (String) pathMethod.invoke(resourceLocation);
                return WorldIdentifier.named(name);
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract dimension name, using UUID");
        }
        return WorldIdentifier.named("world_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private WorldProperties extractProperties(Object mojangLevel, WorldIdentifier identifier) {
        var environment = detectEnvironment(mojangLevel);
        return switch (environment) {
            case "nether" -> WorldProperties.nether(identifier, 0);
            case "the_end" -> WorldProperties.theEnd(identifier, 0);
            default -> WorldProperties.overworld(identifier, 0);
        };
    }

    private String detectEnvironment(Object mojangLevel) {
        try {
            var dimensionTypeMethod = findAccessibleMethod(mojangLevel.getClass(), "dimensionType");
            if (dimensionTypeMethod != null) {
                var dimType = dimensionTypeMethod.invoke(mojangLevel);

                var effectsMethod = dimType.getClass().getMethod("effects");
                var effectsLocation = effectsMethod.invoke(dimType);
                var pathMethod = effectsLocation.getClass().getMethod("getPath");
                var effectsPath = (String) pathMethod.invoke(effectsLocation);

                return switch (effectsPath) {
                    case "the_nether" -> "nether";
                    case "the_end" -> "the_end";
                    default -> "overworld";
                };
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not detect environment");
        }
        return "overworld";
    }

    private static Method findAccessibleMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            var method = clazz.getMethod(name, paramTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            for (var iface : clazz.getInterfaces()) {
                try {
                    var method = iface.getMethod(name, paramTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }
    }

    private static final class AdaptedWorld implements InternalWorld {

        private final WorldIdentifier identifier;
        private final WorldProperties properties;
        private final Object mojangLevel;
        private volatile WorldState state;
        private volatile long time;
        private volatile boolean storm;
        private volatile boolean thundering;

        AdaptedWorld(WorldIdentifier identifier, WorldProperties properties, Object mojangLevel) {
            this.identifier = identifier;
            this.properties = properties;
            this.mojangLevel = mojangLevel;
            this.state = WorldState.LOADED;
        }

        @Override
        public UUID uniqueId() { return identifier.uniqueId(); }

        @Override
        public String name() { return identifier.name(); }

        @Override
        public WorldIdentifier identifier() { return identifier; }

        @Override
        public WorldProperties properties() { return properties; }

        @Override
        public WorldState state() { return state; }

        @Override
        public void state(WorldState newState) { this.state = newState; }

        @Override
        public long time() { return time; }

        @Override
        public void time(long ticks) { this.time = ticks; }

        @Override
        public long gameTime() { return time; }

        @Override
        public boolean storm() { return storm; }

        @Override
        public void storm(boolean storm) { this.storm = storm; }

        @Override
        public boolean thundering() { return thundering; }

        @Override
        public void thundering(boolean thundering) { this.thundering = thundering; }

        @Override
        public Optional<InternalChunk> chunk(
            ChunkPosition position) {
            return Optional.empty();
        }

        @Override
        public Optional<InternalChunk> chunk(int x, int z) {
            return Optional.empty();
        }

        @Override
        public Collection<InternalChunk> loadedChunks() {
            return List.of();
        }

        @Override
        public int loadedChunkCount() { return 0; }

        @Override
        public Collection<? extends InternalEntity> entities() { return List.of(); }

        @Override
        public <T extends InternalEntity> Collection<T> entities(Class<T> type) { return List.of(); }

        @Override
        public Collection<? extends InternalPlayer> players() { return List.of(); }

        @Override
        public Optional<InternalPlayer> nearestPlayer(double x, double y, double z, double radius) {
            return Optional.empty();
        }

        @Override
        public Stream<? extends InternalPlayer> playerStream() { return Stream.empty(); }
    }
}


