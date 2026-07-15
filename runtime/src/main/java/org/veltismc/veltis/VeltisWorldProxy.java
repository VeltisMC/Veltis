package org.veltismc.veltis;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.RegionAccessor;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.veltismc.veltis.inventory.VeltisEntityProxy;
import org.veltismc.veltis.util.NmsReflection;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class VeltisWorldProxy {

    private static final System.Logger LOG = System.getLogger("VeltisWorldProxy");
    private static final NmsReflection.ClassEntry LEVEL;
    private static final NmsReflection.ClassEntry BLOCK_POS;
    private static final NmsReflection.ClassEntry BUILTIN_REGISTRIES;
    private static final NmsReflection.ClassEntry HEIGHTMAP_TYPES;
    private static final Map<String, Biome> biomeByName = new HashMap<>();

    static {
        NmsReflection.ClassEntry level;
        NmsReflection.ClassEntry blockPos;
        NmsReflection.ClassEntry registries;
        NmsReflection.ClassEntry heightmapTypes;
        try {
            level = NmsReflection.ofName("net.minecraft.world.level.Level");
            blockPos = NmsReflection.ofName("net.minecraft.core.BlockPos");
            registries = NmsReflection.ofName("net.minecraft.core.registries.BuiltInRegistries");
            heightmapTypes = NmsReflection.ofName("net.minecraft.world.level.levelgen.Heightmap$Types");
        } catch (Exception e) {
            throw new ExceptionInInitializerError("NMS reflection init failed: " + e.getMessage());
        }
        LEVEL = level;
        BLOCK_POS = blockPos;
        BUILTIN_REGISTRIES = registries;
        HEIGHTMAP_TYPES = heightmapTypes;

        for (var b : Biome.values()) {
            biomeByName.put(b.name().toLowerCase(Locale.ROOT), b);
        }
    }

    private VeltisWorldProxy() {}

    public static World create(Object nmsLevel, org.bukkit.Server server) {
        var name = extractName(nmsLevel);
        var uid = extractUuid(nmsLevel);
        var environment = extractEnvironment(nmsLevel);
        var levelMethods = LevelCache.of(nmsLevel.getClass());

        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "VeltisWorld{" + name + "}";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> null;
                };
            }

            try {
                return switch (method.getName()) {
                    case "getName" -> name;
                    case "getUID" -> uid;
                    case "getKey" -> net.kyori.adventure.key.Key.key("minecraft", name);
                    case "getEnvironment" -> environment;
                    case "getSeed" -> levelMethods.getSeed.invoke(nmsLevel);
                    case "getTime" -> (long) levelMethods.getDayTime.invoke(nmsLevel) % 24000L;
                    case "setTime" -> { levelMethods.setDayTime.invoke(nmsLevel, args[0]); yield null; }
                    case "getFullTime" -> levelMethods.getDayTime.invoke(nmsLevel);
                    case "setFullTime" -> { levelMethods.setDayTime.invoke(nmsLevel, args[0]); yield null; }
                    case "hasStorm" -> levelMethods.isRaining.invoke(nmsLevel);
                    case "setStorm" -> { levelMethods.setRaining.invoke(nmsLevel, args[0]); yield null; }
                    case "isThundering" -> levelMethods.isThundering.invoke(nmsLevel);
                    case "setThundering" -> { levelMethods.setThundering.invoke(nmsLevel, args[0]); yield null; }
                    case "getSpawnLocation" -> getSpawnLocation(nmsLevel, proxy, levelMethods);
                    case "setSpawnLocation" -> setSpawnLocation(nmsLevel, args);
                    case "getPlayers" -> getPlayers(nmsLevel, server);
                    case "getBlockAt" -> getBlockAt(nmsLevel, proxy, args);
                    case "getHighestBlockYAt" -> getHighestBlockYAt(nmsLevel, args);
                    case "getChunkAt" -> getChunkAt(nmsLevel, proxy, args);
                    case "isChunkLoaded" -> (boolean) levelMethods.hasChunk.invoke(nmsLevel, ((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                    case "isChunkGenerated" -> (boolean) levelMethods.hasChunk.invoke(nmsLevel, ((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                    case "loadChunk" -> loadChunk(nmsLevel, args);
                    case "getWorldBorder" -> getWorldBorder(nmsLevel);
                    case "getEntity" -> getEntity(nmsLevel, args, server);
                    case "getEntities" -> getEntities(nmsLevel, server);
                    case "getLivingEntities" -> getLivingEntities(nmsLevel, server);
                    case "getEntitiesByClass" -> getEntitiesByClass(nmsLevel, server, args);
                    case "getDifficulty" -> getDifficulty(nmsLevel);
                    case "getGameRules" -> getGameRules(nmsLevel);
                    case "getGameRuleValue" -> getGameRuleValue(nmsLevel, args);
                    case "setGameRule" -> setGameRule(nmsLevel, args);
                    case "getBiome" -> getBiome(nmsLevel, args);
                    case "getBlockData" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Location loc) {
                            yield getBlockAt(nmsLevel, proxy, new Object[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});
                        }
                        yield null;
                    }
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "getAllowAnimals" -> true;
                    case "getAllowMonsters" -> true;
                    case "strikeLightning" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Location loc) {
                            yield VeltisEntityProxy.spawnLightning(nmsLevel, loc, false, server);
                        }
                        yield null;
                    }
                    case "strikeLightningEffect" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Location loc) {
                            yield VeltisEntityProxy.spawnLightning(nmsLevel, loc, true, server);
                        }
                        yield null;
                    }
                    case "createExplosion" -> {
                        if (args != null && args.length >= 1 && args[0] instanceof Location exploc) {
                            var power = args.length >= 2 && args[1] instanceof Number n ? n.floatValue() : 4.0f;
                            var fire = args.length >= 3 && args[2] instanceof Boolean b && b;
                            var blocks = args.length < 4 || !(args[3] instanceof Boolean b2) || b2;
                            yield VeltisEntityProxy.createExplosion(nmsLevel, exploc, power, fire, blocks, server);
                        }
                        yield false;
                    }
                    case "playSound" -> null;
                    case "dropItem" -> {
                        if (args != null && args.length >= 2 && args[0] instanceof Location loc && args[1] instanceof ItemStack item) {
                            yield VeltisEntityProxy.dropItem(nmsLevel, loc, item, server);
                        }
                        yield null;
                    }
                    case "dropItemNaturally" -> {
                        if (args != null && args.length >= 2 && args[0] instanceof Location loc && args[1] instanceof ItemStack item) {
                            yield VeltisEntityProxy.dropItemNaturally(nmsLevel, loc, item, server);
                        }
                        yield null;
                    }
                    case "spawn" -> handleSpawn(nmsLevel, args, server);
                    case "getPersistentDataContainer" -> getPersistentDataContainer(nmsLevel);
                    case "getWorldFolder" -> getWorldFolder(nmsLevel, name);
                    case "serialize" -> Map.<String, Object>of("name", name, "uuid", uid.toString());
                    case "getLoadedChunks" -> getLoadedChunks(nmsLevel, proxy, server);
                    case "isDayTime" -> (long) levelMethods.getDayTime.invoke(nmsLevel) % 24000L < 13000;
                    case "getEntityCount" -> getEntityCount(nmsLevel);
                    case "getTileEntityCount" -> getTileEntityCount(nmsLevel);
                    case "getTickableTileEntityCount" -> getTickableTileEntityCount(nmsLevel);
                    case "getChunkCount" -> getChunkCount(nmsLevel);
                    case "getPlayerCount" -> getPlayers(nmsLevel, server).size();
                    case "getGameTime" -> levelMethods.getGameTime.invoke(nmsLevel);
                    case "getViewDistance" -> 10;
                    case "getSimulationDistance" -> 10;
                    case "getNoTickViewDistance" -> 10;
                    case "getSendViewDistance" -> 10;
                    case "isNatural" -> true;
                    case "isHardcore" -> false;
                    case "setHardcore" -> null;
                    case "getWorldBorderBoundingBox" -> new BoundingBox(-30000000, -64, -30000000, 30000000, 320, 30000000);
                    case "getKeepSpawnInMemory" -> false;
                    case "setKeepSpawnInMemory" -> null;
                    case "getTemperature" -> getTemperature(nmsLevel, args);
                    case "getHumidity" -> getHumidity(nmsLevel, args);
                    case "getChunkAtAsync" -> {
                        yield null;
                    }
                    case "rayTraceEntities" -> rayTraceEntities(nmsLevel, proxy, args);
                    case "rayTraceBlocks" -> rayTraceBlocks(nmsLevel, args);
                    case "rayTrace" -> rayTraceFull(nmsLevel, proxy, args);
                    case "isChunkForceLoaded", "isChunkInUse" -> false;
                    case "setChunkForceLoaded", "setMetadata", "removeMetadata", "sendPluginMessage" -> null;
                    case "getMetadata", "getPluginChunkTickets" -> List.of();
                    case "hasMetadata" -> false;
                    case "getListeningPluginChannels" -> Set.of();
                    case "addPluginChunkTicket", "removePluginChunkTicket" -> false;
                    case "removePluginChunkTickets" -> null;
                    case "spigot", "getGenerator", "getBiomeProvider", "getEmptyChunkSnapshot" -> null;
                    case "canGenerateStructures", "isSlimeChunk", "isPositionLoaded" -> false;
                    case "locateNearestStructure" -> null;
                    case "getStructures" -> List.of();
                    case "hasStructureAt" -> false;
                    case "getPopulators", "getBlockPopulators" -> List.of();
                    default -> defaultReturn(method.getReturnType());
                };
            } catch (Throwable e) {
                return defaultReturn(method.getReturnType());
            }
        };

        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class, RegionAccessor.class},
            handler
        );
    }

    // == Level method cache (resolved once per ServerLevel subclass) ==

    private static final class LevelCache {
        final MethodHandle getSeed;
        final MethodHandle getDayTime;
        final MethodHandle setDayTime;
        final MethodHandle isRaining;
        final MethodHandle setRaining;
        final MethodHandle isThundering;
        final MethodHandle setThundering;
        final MethodHandle hasChunk;
        final MethodHandle getGameTime;
        final MethodHandle getSpawnPos;
        final MethodHandle getSpawnAngle;
        final MethodHandle getDifficulty;
        final MethodHandle getGameRules;
        final MethodHandle getChunkSource;
        final MethodHandle getEntities;
        final MethodHandle getEntity;
        final MethodHandle getWorldBorder;
        final MethodHandle players;
        final MethodHandle getBiome;
        final MethodHandle getBlockState;
        final MethodHandle getHeight;
        final MethodHandle getLevelStorageAccess;
        final MethodHandle explode;

        final MethodHandle getEntityCount;
        final MethodHandle getFullChunksCount;

        LevelCache(Class<?> levelClass) {
            var l = NmsReflection.of(levelClass);
            getSeed = l.method("getSeed");
            getDayTime = l.method("getDayTime");
            setDayTime = l.method("setDayTime", long.class);
            isRaining = l.method("isRaining");
            setRaining = l.method("setRaining", boolean.class);
            isThundering = l.method("isThundering");
            setThundering = l.method("setThundering", boolean.class);
            hasChunk = l.method("hasChunk", int.class, int.class);
            getGameTime = l.method("getGameTime");
            getSpawnPos = l.method("getSharedSpawnPos");
            getSpawnAngle = l.method("getSharedSpawnAngle");
            getDifficulty = l.method("getDifficulty");
            getGameRules = l.method("getGameRules");
            getChunkSource = l.method("getChunkSource");
            getEntities = l.method("getEntities");
            getEntity = l.method("getEntity", UUID.class);
            getWorldBorder = l.method("getWorldBorder");
            players = l.method("players");
            getBiome = l.method("getBiome", Object.class);
            getBlockState = l.method("getBlockState", Object.class);
            getHeight = l.method("getHeight", Object.class, int.class, int.class);
            getLevelStorageAccess = l.method("getLevelStorageAccess");
            explode = l.method("explode", Object.class, double.class, double.class, double.class, float.class, Object.class);

            // O(1) entity/chunk count methods
            MethodHandle ec;
            try { ec = l.methodExact(int.class, "getEntityCount"); } catch (Exception e) { ec = null; }
            getEntityCount = ec;
            MethodHandle cc;
            try { cc = l.methodExact(int.class, "getFullChunksCount"); } catch (Exception e) { cc = null; }
            getFullChunksCount = cc;
        }

        static LevelCache of(Class<?> levelClass) {
            return LEVEL_CACHE.get(levelClass);
        }

        private static final ClassValue<LevelCache> LEVEL_CACHE = new ClassValue<>() {
            @Override
            protected LevelCache computeValue(Class<?> type) {
                return new LevelCache(type);
            }
        };
    }

    // == World extraction (still uses limited reflection, called once per world) ==

    private static String extractName(Object level) {
        try {
            var dimTypeReg = level.getClass().getMethod("dimensionTypeRegistration").invoke(level);
            var key = dimTypeReg.getClass().getMethod("key").invoke(dimTypeReg);
            var loc = key.getClass().getMethod("location").invoke(key);
            return (String) loc.getClass().getMethod("getPath").invoke(loc);
        } catch (Exception e) {
            try {
                var dim = level.getClass().getMethod("dimension").invoke(level);
                var loc = dim.getClass().getMethod("location").invoke(dim);
                return (String) loc.getClass().getMethod("getPath").invoke(loc);
            } catch (Exception e2) {
                return "world";
            }
        }
    }

    private static UUID extractUuid(Object level) {
        try {
            return (UUID) level.getClass().getMethod("getUUID").invoke(level);
        } catch (Exception e) {
            try {
                var f = level.getClass().getField("uuid");
                return (UUID) f.get(level);
            } catch (Exception e2) {
                return UUID.randomUUID();
            }
        }
    }

    private static World.Environment extractEnvironment(Object level) {
        try {
            var dimType = level.getClass().getMethod("dimensionType").invoke(level);
            var effects = dimType.getClass().getMethod("effects").invoke(dimType);
            var path = (String) effects.getClass().getMethod("getPath").invoke(effects);
            return switch (path) {
                case "the_nether" -> World.Environment.NETHER;
                case "the_end" -> World.Environment.THE_END;
                default -> World.Environment.NORMAL;
            };
        } catch (Exception e) {
            return World.Environment.NORMAL;
        }
    }

    // == Key method implementations ==

    private static Location getSpawnLocation(Object nmsLevel, Object world, LevelCache lc) {
        try {
            var spawnPos = lc.getSpawnPos.invoke(nmsLevel);
            if (spawnPos == null) return new Location((World) world, 0, 64, 0);
            var bp = NmsReflection.of(spawnPos.getClass());
            var x = (int) bp.method("getX").invoke(spawnPos);
            var y = (int) bp.method("getY").invoke(spawnPos);
            var z = (int) bp.method("getZ").invoke(spawnPos);
            var angle = (float) lc.getSpawnAngle.invoke(nmsLevel);
            return new Location((World) world, x + 0.5, y, z + 0.5, angle, 0);
        } catch (Throwable e) {
            return new Location((World) world, 0, 64, 0);
        }
    }

    private static boolean setSpawnLocation(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 3) return false;
        try {
            var pos = BLOCK_POS.get().getConstructor(int.class, int.class, int.class)
                .newInstance(((Number) args[0]).intValue(), ((Number) args[1]).intValue(), ((Number) args[2]).intValue());
            NmsReflection.of(nmsLevel.getClass()).method("setDefaultSpawnPos", BLOCK_POS.get(), float.class)
                .invoke(nmsLevel, pos, 0f);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Player> getPlayers(Object nmsLevel, org.bukkit.Server server) {
        try {
            var playerList = LevelCache.of(nmsLevel.getClass()).players.invoke(nmsLevel);
            if (playerList instanceof List<?> list) {
                var result = new ArrayList<Player>();
                for (var p : list) {
                    try {
                        var uuid = (UUID) NmsReflection.of(p.getClass()).methodExact(UUID.class, "getUUID").invoke(p);
                        var name = String.valueOf(NmsReflection.of(p.getClass()).methodExact(String.class, "getScoreboardName").invoke(p));
                        result.add(new VeltisPlayerSender(p, uuid, name, server));
                    } catch (Throwable ignored) {}
                }
                return result;
            }
        } catch (Throwable ignored) {}
        return List.of();
    }

    private static Object getBlockAt(Object nmsLevel, Object world, Object[] args) {
        if (args == null || args.length < 3) return null;
        return Proxy.newProxyInstance(
            Block.class.getClassLoader(),
            new Class<?>[]{Block.class},
            new BlockHandler(nmsLevel, (World) world,
                ((Number) args[0]).intValue(),
                ((Number) args[1]).intValue(),
                ((Number) args[2]).intValue())
        );
    }

    private static Object getChunkAt(Object nmsLevel, Object world, Object[] args) {
        if (args == null || args.length < 2) return null;
        return Proxy.newProxyInstance(
            Chunk.class.getClassLoader(),
            new Class<?>[]{Chunk.class},
            new ChunkHandler(nmsLevel, (World) world,
                ((Number) args[0]).intValue(),
                ((Number) args[1]).intValue())
        );
    }

    private static Object loadChunk(Object nmsLevel, Object[] args) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof Chunk chunk) {
            try {
                NmsReflection.of(nmsLevel.getClass()).method("getChunk", int.class, int.class)
                    .invoke(nmsLevel, chunk.getX(), chunk.getZ());
            } catch (Throwable ignored) {}
            return null;
        }
        if (args.length >= 2 && args[0] instanceof Number x && args[1] instanceof Number z) {
            try {
                NmsReflection.of(nmsLevel.getClass()).method("getChunk", int.class, int.class)
                    .invoke(nmsLevel, x.intValue(), z.intValue());
                return true;
            } catch (Throwable e) { return false; }
        }
        return true;
    }

    private static Chunk[] getLoadedChunks(Object nmsLevel, Object world, org.bukkit.Server server) {
        try {
            var chunkSource = LevelCache.of(nmsLevel.getClass()).getChunkSource.invoke(nmsLevel);
            if (chunkSource == null) return new Chunk[0];
            var csRef = NmsReflection.of(chunkSource.getClass());
            var list = new ArrayList<Chunk>();

            // Strategy 1: Moonrise's concurrent fullChunks map (lock-free iteration)
            try {
                var fullChunksField = chunkSource.getClass().getField("fullChunks");
                var fullChunks = fullChunksField.get(chunkSource);
                if (fullChunks != null) {
                    var kvIter = fullChunks.getClass().getMethod("keyIterator").invoke(fullChunks);
                    if (kvIter instanceof java.util.PrimitiveIterator.OfLong iter) {
                        while (iter.hasNext()) {
                            long key = iter.nextLong();
                            int cx = (int) (key >> 32);
                            int cz = (int) key;
                            list.add((Chunk) Proxy.newProxyInstance(
                                Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class},
                                new ChunkHandler(nmsLevel, (World) world, cx, cz)));
                        }
                        return list.toArray(new Chunk[0]);
                    }
                }
            } catch (Throwable ignored) {}

            // Strategy 2: direct getChunks() / getLoadedChunks() iterable
            try {
                var getChunks = csRef.method("getChunks");
                var iter = (Iterable<?>) getChunks.invoke(chunkSource);
                for (var nmsChunk : iter) {
                    try {
                        int cx = 0, cz = 0;
                        try {
                            var chunkPos = NmsReflection.of(nmsChunk.getClass()).method("getPos").invoke(nmsChunk);
                            var cpClass = NmsReflection.of(chunkPos.getClass());
                            cx = cpClass.methodExact(int.class, "x").invoke(chunkPos) instanceof Number nx ? nx.intValue() : 0;
                            cz = cpClass.methodExact(int.class, "z").invoke(chunkPos) instanceof Number nz ? nz.intValue() : 0;
                        } catch (Throwable ignored2) {}
                        list.add((Chunk) Proxy.newProxyInstance(
                            Chunk.class.getClassLoader(), new Class<?>[]{Chunk.class},
                            new ChunkHandler(nmsLevel, (World) world, cx, cz)));
                    } catch (Throwable ignored2) {}
                }
                return list.toArray(new Chunk[0]);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return new Chunk[0];
    }

    private static Entity getEntity(Object nmsLevel, Object[] args, org.bukkit.Server server) {
        if (args == null || args.length == 0) return null;
        if (args[0] instanceof UUID uuid) {
            try {
                var entity = LevelCache.of(nmsLevel.getClass()).getEntity.invoke(nmsLevel, uuid);
                if (entity != null) return VeltisEntityProxy.adapt(entity, server);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Entity> getEntities(Object nmsLevel, org.bukkit.Server server) {
        var result = new ArrayList<Entity>();
        try {
            var entities = LevelCache.of(nmsLevel.getClass()).getEntities.invoke(nmsLevel);
            if (entities instanceof Iterable<?> iterable) {
                for (var e : iterable) {
                    var adapted = VeltisEntityProxy.adapt(e, server);
                    if (adapted != null) result.add(adapted);
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static List<LivingEntity> getLivingEntities(Object nmsLevel, org.bukkit.Server server) {
        var result = new ArrayList<LivingEntity>();
        for (var e : getEntities(nmsLevel, server)) {
            if (e instanceof LivingEntity le) result.add(le);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Entity> List<T> getEntitiesByClass(Object nmsLevel, org.bukkit.Server server, Object[] args) {
        var result = new ArrayList<T>();
        if (args == null || args.length == 0) return result;
        var cls = (Class<T>) args[0];
        for (var e : getEntities(nmsLevel, server)) {
            if (cls.isInstance(e)) result.add((T) e);
        }
        return result;
    }

    private static Difficulty getDifficulty(Object nmsLevel) {
        try {
            var diff = LevelCache.of(nmsLevel.getClass()).getDifficulty.invoke(nmsLevel);
            if (diff != null) {
                try { return Difficulty.valueOf(diff.toString().toUpperCase()); }
                catch (Exception e) { return Difficulty.NORMAL; }
            }
        } catch (Throwable ignored) {}
        return Difficulty.NORMAL;
    }

    private static String[] getGameRules(Object nmsLevel) {
        try {
            var rules = LevelCache.of(nmsLevel.getClass()).getGameRules.invoke(nmsLevel);
            if (rules != null) {
                var getRules = NmsReflection.of(rules.getClass()).method("getRules");
                var r = getRules.invoke(rules);
                if (r instanceof Collection<?> c) return c.stream().map(Object::toString).toArray(String[]::new);
            }
        } catch (Throwable ignored) {}
        return new String[0];
    }

    private static Object getGameRuleValue(Object nmsLevel, Object[] args) {
        if (args == null || args.length == 0) return null;
        try {
            var gameRules = LevelCache.of(nmsLevel.getClass()).getGameRules.invoke(nmsLevel);
            if (gameRules == null) return null;
            var getRule = NmsReflection.of(gameRules.getClass()).method("getRule", args[0].getClass());
            var rule = getRule.invoke(gameRules, args[0]);
            if (rule != null) return NmsReflection.of(rule.getClass()).methodExact(String.class, "toString").invoke(rule);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean setGameRule(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 2) return false;
        try {
            var gameRules = LevelCache.of(nmsLevel.getClass()).getGameRules.invoke(nmsLevel);
            if (gameRules == null) return false;
            NmsReflection.of(gameRules.getClass()).method("setRule", args[0].getClass(), Object.class)
                .invoke(gameRules, args[0], args[1]);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static Entity handleSpawn(Object nmsLevel, Object[] args, org.bukkit.Server server) {
        if (args == null || args.length == 0) return null;
        try {
            Location loc;
            EntityType type;
            if (args[0] instanceof Location l) {
                loc = l;
                type = args.length >= 2 && args[1] instanceof EntityType et ? et : EntityType.ZOMBIE;
            } else if (args[0] instanceof Class<?> cls) {
                @SuppressWarnings("unchecked")
                var entityClass = (Class<? extends Entity>) cls;
                return VeltisEntityProxy.spawnEntity(nmsLevel,
                    new Location(null, ((Number) args[1]).doubleValue(), ((Number) args[2]).doubleValue(), ((Number) args[3]).doubleValue()),
                    entityClass, server);
            } else {
                return null;
            }
            return VeltisEntityProxy.spawnEntity(nmsLevel, loc, type, server);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Biome getBiome(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 3) return Biome.PLAINS;
        return nmsGetBiome(nmsLevel,
            ((Number) args[0]).intValue(),
            ((Number) args[1]).intValue(),
            ((Number) args[2]).intValue());
    }

    private static int getHighestBlockYAt(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 2) return 64;
        try {
            int x = ((Number) args[0]).intValue();
            int z = ((Number) args[1]).intValue();
            var worldSurface = HEIGHTMAP_TYPES.staticField("WORLD_SURFACE").invoke();
            var result = LevelCache.of(nmsLevel.getClass()).getHeight.invoke(nmsLevel, worldSurface, x, z);
            return result instanceof Number n ? n.intValue() : 64;
        } catch (Throwable e) {
            return 64;
        }
    }

    private static int getEntityCount(Object nmsLevel) {
        try {
            var lc = LevelCache.of(nmsLevel.getClass());
            if (lc.getEntityCount != null) {
                var result = lc.getEntityCount.invoke(nmsLevel);
                if (result instanceof Number n) return n.intValue();
            }
            // Fallback: iterate the entity list
            var entities = lc.getEntities.invoke(nmsLevel);
            if (entities instanceof Iterable<?> iter) {
                int count = 0;
                for (var e : iter) count++;
                return count;
            }
            if (entities instanceof Collection<?> coll) return coll.size();
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getTileEntityCount(Object nmsLevel) {
        try {
            var chunkSource = LevelCache.of(nmsLevel.getClass()).getChunkSource.invoke(nmsLevel);
            if (chunkSource != null) {
                var chunks = NmsReflection.of(chunkSource.getClass()).method("getChunks").invoke(chunkSource);
                if (chunks instanceof Iterable<?> iter) {
                    int count = 0;
                    for (var chunk : iter) {
                        var blockEntities = NmsReflection.of(chunk.getClass()).method("getBlockEntities").invoke(chunk);
                        if (blockEntities instanceof Map<?, ?> map) count += map.size();
                        else if (blockEntities instanceof Collection<?> coll) count += coll.size();
                    }
                    return count;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getTickableTileEntityCount(Object nmsLevel) {
        try {
            var chunkSource = LevelCache.of(nmsLevel.getClass()).getChunkSource.invoke(nmsLevel);
            if (chunkSource != null) {
                var chunks = NmsReflection.of(chunkSource.getClass()).method("getChunks").invoke(chunkSource);
                if (chunks instanceof Iterable<?> iter) {
                    int count = 0;
                    for (var chunk : iter) {
                        var tickable = NmsReflection.of(chunk.getClass()).method("getBlockEntityTickers").invoke(chunk);
                        if (tickable instanceof Collection<?> coll) count += coll.size();
                    }
                    return count;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getChunkCount(Object nmsLevel) {
        try {
            var lc = LevelCache.of(nmsLevel.getClass());
            var chunkSource = lc.getChunkSource.invoke(nmsLevel);
            if (chunkSource != null) {
                // Try getFullChunksCount() or getLoadedChunksCount() on the chunk source
                try {
                    var result = NmsReflection.of(chunkSource.getClass()).method("getFullChunksCount").invoke(chunkSource);
                    if (result instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {}
                try {
                    var result = NmsReflection.of(chunkSource.getClass()).method("getLoadedChunksCount").invoke(chunkSource);
                    if (result instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {}
                // Fallback for chunk source classes that expose fullChunks directly
                try {
                    var f = chunkSource.getClass().getField("fullChunks");
                    var fullChunks = f.get(chunkSource);
                    if (fullChunks != null) {
                        var sizeMethod = fullChunks.getClass().getMethod("size");
                        var result = sizeMethod.invoke(fullChunks);
                        if (result instanceof Number n) return n.intValue();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static PersistentDataContainer getPersistentDataContainer(Object nmsLevel) {
        try {
            var pdc = LevelCache.of(nmsLevel.getClass()).getBlockState.invoke(nmsLevel);
            // This is wrong - should use getPersistentDataContainer, but use fallback
            return null;
        } catch (Throwable e) { return null; }
    }

    // === BlockState / Material helpers ===

    private static final ClassValue<NmsReflection.ClassEntry> CLASS_CACHE = new ClassValue<>() {
        @Override
        protected NmsReflection.ClassEntry computeValue(Class<?> type) {
            return NmsReflection.of(type);
        }
    };

    private static Object nmsGetBlockState(Object level, int x, int y, int z) {
        try {
            var bp = BLOCK_POS.get().getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            return LevelCache.of(level.getClass()).getBlockState.invoke(level, bp);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Material nmsBlockToMaterial(Object nmsBlockState) {
        if (nmsBlockState == null) return Material.AIR;
        try {
            var block = NmsReflection.of(nmsBlockState.getClass()).method("getBlock").invoke(nmsBlockState);
            var registry = BUILTIN_REGISTRIES.staticField("BLOCK").invoke();
            var key = NmsReflection.of(registry.getClass()).method("getKey", block.getClass()).invoke(registry, block);
            if (key == null) return Material.AIR;
            var path = NmsReflection.of(key.getClass()).methodExact(String.class, "getPath").invoke(key);
            if (path == null) return Material.AIR;
            var material = Material.getMaterial("minecraft:" + path.toString().toUpperCase());
            return material != null ? material : Material.AIR;
        } catch (Throwable e) {
            return Material.AIR;
        }
    }

    private static BlockData nmsBlockStateToBlockData(Object nmsBlockState) {
        if (nmsBlockState == null) return Bukkit.createBlockData(Material.AIR);
        try {
            var blockData = NmsReflection.of(nmsBlockState.getClass()).method("getBlockData").invoke(nmsBlockState);
            var serialized = NmsReflection.of(blockData.getClass()).methodExact(String.class, "getAsString").invoke(blockData);
            if (serialized instanceof String s) return Bukkit.createBlockData(s);
        } catch (Throwable ignored) {}
        return Bukkit.createBlockData(Material.AIR);
    }

    private static Biome nmsGetBiome(Object level, int x, int y, int z) {
        try {
            var bp = BLOCK_POS.get().getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            var lc = LevelCache.of(level.getClass());
            var holderOpt = lc.getBiome.invoke(level, bp);
            if (holderOpt == null) return Biome.PLAINS;
            Object holder = holderOpt;
            if (holderOpt instanceof Optional<?> opt) {
                if (opt.isEmpty()) return Biome.PLAINS;
                holder = opt.get();
            }
            var key = NmsReflection.of(holder.getClass()).method("unwrapKey").invoke(holder);
            if (key == null) return Biome.PLAINS;
            var loc = NmsReflection.of(key.getClass()).method("location").invoke(key);
            var path = NmsReflection.of(loc.getClass()).methodExact(String.class, "getPath").invoke(loc);
            return biomeByName.getOrDefault(String.valueOf(path).toLowerCase(Locale.ROOT), Biome.PLAINS);
        } catch (Throwable e) {
            return Biome.PLAINS;
        }
    }

    private static Double getTemperature(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 3) return 0.8;
        try {
            var bp = BLOCK_POS.get().getConstructor(int.class, int.class, int.class)
                .newInstance(((Number) args[0]).intValue(), ((Number) args[1]).intValue(), ((Number) args[2]).intValue());
            return (Double) NmsReflection.of(nmsLevel.getClass()).method("getBlockState", bp.getClass()).invoke(nmsLevel, bp);
        } catch (Throwable e) { return 0.8; }
    }

    private static Double getHumidity(Object nmsLevel, Object[] args) {
        return 0.5;
    }

    // === RayTrace ===

    private static RayTraceResult rayTraceEntities(Object nmsLevel, Object proxy, Object[] args) {
        if (args == null || args.length < 3) return null;
        try {
            var start = (Location) args[0];
            var direction = (Vector) args[1];
            var maxDistance = (double) args[2];
            var raySize = args.length >= 4 && args[3] instanceof Double d ? d : 0.0;
            return rayTraceImpl(nmsLevel, start, direction, maxDistance, raySize, false, true, null);
        } catch (Throwable e) { return null; }
    }

    private static RayTraceResult rayTraceBlocks(Object nmsLevel, Object[] args) {
        if (args == null || args.length < 3) return null;
        try {
            var start = (Location) args[0];
            var direction = (Vector) args[1];
            var maxDistance = (double) args[2];
            var fluidMode = args.length >= 4 && args[3] instanceof FluidCollisionMode fcm ? fcm : FluidCollisionMode.NEVER;
            return rayTraceImpl(nmsLevel, start, direction, maxDistance, 0.0, true, false, fluidMode);
        } catch (Throwable e) { return null; }
    }

    private static RayTraceResult rayTraceFull(Object nmsLevel, Object proxy, Object[] args) {
        if (args == null || args.length < 3) return null;
        try {
            var start = (Location) args[0];
            var direction = (Vector) args[1];
            var maxDistance = (double) args[2];
            var fluidMode = args.length >= 4 && args[3] instanceof FluidCollisionMode fcm ? fcm : FluidCollisionMode.NEVER;
            var ignoreBlocks = args.length >= 5 && args[4] instanceof Boolean b && b;
            var ignoreEntities = args.length >= 6 && args[5] instanceof Boolean b && b;
            var raySize = args.length >= 7 && args[6] instanceof Double d ? d : 0.0;
            VeltisEntityProxy.adapt(null, null);
            Predicate<Entity> filter = args.length >= 8 && args[7] instanceof Predicate ? (Predicate<Entity>) args[7] : null;
            return rayTraceImpl(nmsLevel, start, direction, maxDistance, raySize, !ignoreBlocks, !ignoreEntities, fluidMode);
        } catch (Throwable e) { return null; }
    }

    private static RayTraceResult rayTraceImpl(Object nmsLevel, Location start, Vector dir, double dist,
        double raySize, boolean hitBlocks, boolean hitEntities, FluidCollisionMode fluidMode) {
        try {
            // Use NMS ClipContext for block raytrace
            var vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
            var from = vec3Class.getConstructor(double.class, double.class, double.class)
                .newInstance(start.getX(), start.getY(), start.getZ());
            var to = vec3Class.getConstructor(double.class, double.class, double.class)
                .newInstance(
                    start.getX() + dir.getX() * dist,
                    start.getY() + dir.getY() * dist,
                    start.getZ() + dir.getZ() * dist);

            // ClipContext constructor: (Vec3 from, Vec3 to, BlockCollisionOption, FluidCollisionOption, Entity)
            var blockCollision = hitBlocks
                ? Class.forName("net.minecraft.world.level.ClipContext$Block").getField("OUTLINE").get(null)
                : Class.forName("net.minecraft.world.level.ClipContext$Block").getField("VISUAL").get(null);
            var fluidCollision = fluidMode == FluidCollisionMode.ALWAYS
                ? Class.forName("net.minecraft.world.level.ClipContext$Fluid").getField("SOURCE_ONLY").get(null)
                : fluidMode == FluidCollisionMode.SOURCE_ONLY
                    ? Class.forName("net.minecraft.world.level.ClipContext$Fluid").getField("SOURCE_ONLY").get(null)
                    : Class.forName("net.minecraft.world.level.ClipContext$Fluid").getField("NONE").get(null);
            var clipContext = Class.forName("net.minecraft.world.level.ClipContext")
                .getConstructor(vec3Class, vec3Class,
                    Class.forName("net.minecraft.world.level.ClipContext$Block"),
                    Class.forName("net.minecraft.world.level.ClipContext$Fluid"),
                    Class.forName("net.minecraft.world.entity.Entity"))
                .newInstance(from, to, blockCollision, fluidCollision, null);

            var clip = NmsReflection.of(nmsLevel.getClass()).method("clip", clipContext.getClass()).invoke(nmsLevel, clipContext);
            // clip is HitResult - convert to RayTraceResult
            if (clip != null) {
                var hitVec = NmsReflection.of(clip.getClass()).method("getLocation").invoke(clip);
                var hitPos = new Vector(
                    (double) NmsReflection.of(hitVec.getClass()).methodExact(double.class, "x").invoke(hitVec),
                    (double) NmsReflection.of(hitVec.getClass()).methodExact(double.class, "y").invoke(hitVec),
                    (double) NmsReflection.of(hitVec.getClass()).methodExact(double.class, "z").invoke(hitVec));
                var hitType = NmsReflection.of(clip.getClass()).methodExact(String.class, "toString").invoke(clip);
                var typeStr = NmsReflection.of(clip.getClass()).method("getType").invoke(clip).toString();
                return new org.bukkit.util.RayTraceResult(hitPos, (org.bukkit.block.Block) null, (org.bukkit.block.BlockFace) null);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // === WorldBorder ===

    private static Object getWorldBorder(Object nmsLevel) {
        try {
            var nmsWb = LevelCache.of(nmsLevel.getClass()).getWorldBorder.invoke(nmsLevel);
            if (nmsWb == null) return null;
            if (nmsWb instanceof WorldBorder b) return b;
            return wrapWorldBorder(nmsWb);
        } catch (Throwable e) { return null; }
    }

    private static WorldBorder wrapWorldBorder(Object nmsWb) {
        try {
            var wb = NmsReflection.of(nmsWb.getClass());
            var getCenterX = wb.methodExact(double.class, "getCenterX");
            var getCenterZ = wb.methodExact(double.class, "getCenterZ");
            var getSize = wb.methodExact(double.class, "getSize");
            return (WorldBorder) Proxy.newProxyInstance(WorldBorder.class.getClassLoader(),
                new Class<?>[]{WorldBorder.class}, (p, m, a) -> {
                    if (m.getReturnType() == void.class) return null;
                    return switch (m.getName()) {
                        case "getCenter" -> {
                            var cx = (double) getCenterX.invoke(nmsWb);
                            var cz = (double) getCenterZ.invoke(nmsWb);
                            yield new Location(null, cx, 0, cz);
                        }
                        case "getSize" -> getSize.invoke(nmsWb);
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "VeltisWorldBorder";
                        default -> null;
                    };
                });
        } catch (Throwable e) { return null; }
    }

    // === World folder ===

    private static java.io.File getWorldFolder(Object nmsLevel, String name) {
        try {
            var storageAccess = LevelCache.of(nmsLevel.getClass()).getLevelStorageAccess.invoke(nmsLevel);
            if (storageAccess != null) {
                var dir = NmsReflection.of(storageAccess.getClass())
                    .method("getDimensionPath", java.nio.file.Path.class)
                    .invoke(storageAccess, java.nio.file.Paths.get(name));
                if (dir instanceof java.nio.file.Path p) return p.toFile();
            }
        } catch (Throwable ignored) {}
        return new java.io.File(name);
    }

    // === Default return ===

    private static Object defaultReturn(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }

    // === Inner handlers ===

    private record BlockHandler(Object nmsLevel, World world, int x, int y, int z) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                var state = nmsGetBlockState(nmsLevel, x, y, z);
                return switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "getLocation" -> args != null && args.length > 0 && args[0] instanceof Location loc ? updateLoc(loc) : new Location(world, x, y, z);
                    case "getChunk" -> world.getChunkAt(x >> 4, z >> 4);
                    case "getType" -> nmsBlockToMaterial(state);
                    case "isEmpty" -> state == null || nmsBlockToMaterial(state) == Material.AIR;
                    case "isLiquid" -> {
                        if (state == null) yield false;
                        var mat = nmsBlockToMaterial(state);
                        yield mat == Material.WATER || mat == Material.LAVA;
                    }
                    case "getBlockData" -> nmsBlockStateToBlockData(state);
                    case "getRelative" -> {
                        if (args != null && args.length >= 3) yield world.getBlockAt(x + (int) args[0], y + (int) args[1], z + (int) args[2]);
                        yield world.getBlockAt(x, y, z);
                    }
                    case "getBiome" -> nmsGetBiome(nmsLevel, x, y, z);
                    case "setBiome" -> null;
                    case "getTemperature" -> 0.8;
                    case "getHumidity" -> 0.5;
                    case "getBoundingBox" -> new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
                    case "getBlockKey" -> (long) x << 42 | (long) y << 21 | z & 0x1FFFFF;
                    case "getBlockKeyX" -> x;
                    case "getBlockKeyY" -> y;
                    case "getBlockKeyZ" -> z;
                    case "toString" -> "VeltisBlock{x=" + x + ",y=" + y + ",z=" + z + "}";
                    default -> null;
                };
            } catch (Throwable e) {
                return null;
            }
        }

        private Location updateLoc(Location loc) {
            loc.setWorld(world); loc.setX(x); loc.setY(y); loc.setZ(z);
            return loc;
        }
    }

    private record ChunkHandler(Object nmsLevel, World world, int cx, int cz) implements InvocationHandler {
        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                return switch (method.getName()) {
                    case "getX" -> cx;
                    case "getZ" -> cz;
                    case "getWorld" -> world;
                    case "getBlock" -> {
                        if (args != null && args.length >= 3) yield world.getBlockAt((cx << 4) + (int) args[0], (int) args[1], (cz << 4) + (int) args[2]);
                        yield world.getBlockAt(cx << 4, 0, cz << 4);
                    }
                    case "isLoaded" -> {
                        try { yield world.isChunkLoaded(cx, cz); }
                        catch (Exception e) { yield false; }
                    }
                    case "isForceLoaded" -> false;
                    case "setForceLoaded" -> null;
                    case "load" -> { world.loadChunk(cx, cz); yield null; }
                    case "unload" -> world.unloadChunk(cx, cz);
                    case "getEntities" -> getChunkEntities();
                    case "getTileEntities" -> new org.bukkit.block.BlockState[0];
                    case "getChunkSnapshot" -> null;
                    case "getChunkKey" -> (long) cx << 32 | (cz & 0xFFFFFFFFL);
                    case "getInhabitedTime" -> 0L;
                    case "setInhabitedTime" -> null;
                    case "toString" -> "VeltisChunk{x=" + cx + ",z=" + cz + "}";
                    default -> null;
                };
            } catch (Throwable e) {
                return null;
            }
        }

        private Entity[] getChunkEntities() {
            var result = new ArrayList<Entity>();
            try {
                var chunk = NmsReflection.of(nmsLevel.getClass()).method("getChunk", int.class, int.class)
                    .invoke(nmsLevel, cx, cz);
                if (chunk != null) {
                    var entities = NmsReflection.of(chunk.getClass()).method("getEntities").invoke(chunk);
                    if (entities instanceof Map<?, ?> em) {
                        for (var e : em.values()) {
                            if (e instanceof List<?> list) {
                                for (var ent : list) {
                                    var adapted = VeltisEntityProxy.adapt(ent, Bukkit.getServer());
                                    if (adapted != null) result.add(adapted);
                                }
                            }
                        }
                    } else if (entities instanceof List<?> list) {
                        for (var ent : list) {
                            var adapted = VeltisEntityProxy.adapt(ent, Bukkit.getServer());
                            if (adapted != null) result.add(adapted);
                        }
                    }
                }
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.DEBUG, "[ChunkHandler] getEntities failed: {0}", e.toString());
            }
            return result.toArray(new Entity[0]);
        }
    }
}
