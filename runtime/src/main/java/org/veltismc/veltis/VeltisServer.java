package org.veltismc.veltis;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.Collections;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.util.TriState;

import org.bukkit.*;
import org.bukkit.Warning.WarningState;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityFactory;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.help.HelpMap;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.map.MapView;
import org.bukkit.packs.ResourcePack;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageRecipient;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.CachedServerIcon;

import io.papermc.paper.configuration.ServerConfiguration;
import io.papermc.paper.datacomponent.DataComponentBuilder;
import io.papermc.paper.datapack.DatapackManager;
import io.papermc.paper.math.Position;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.lang.reflect.Proxy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.plugin.Plugin;
import java.time.Duration;
import java.time.Instant;
import org.bukkit.BanEntry;
import org.veltismc.veltis.server.tick.TickEngine;

public class VeltisServer implements Server {

    private static final System.Logger LOG = System.getLogger("VeltisMC.Server");
    private static final int TICK_HISTORY_SIZE = 18000; // 15 min @ 20 TPS
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MICRO = 1_000L;

    private volatile TickEngine tickEngine;
    private final long[] tickStartNanos = new long[TICK_HISTORY_SIZE];
    private int tickHistoryIndex = 0;
    private int tickHistoryCount = 0;

    private Object minecraftServer;
    private final SimpleCommandMap commandMap;
    private final PluginManager pluginManager;
    private final BukkitScheduler scheduler;
    private final ServicesManager servicesManager;
    private final Messenger messenger;
    private final ConsoleCommandSender consoleSender;
    private final AsyncScheduler asyncScheduler = new VeltisAsyncScheduler(VeltisBootstrap.scheduler());
    private final GlobalRegionScheduler globalRegionScheduler = new VeltisGlobalRegionScheduler(VeltisBootstrap.scheduler());
    private final RegionScheduler regionScheduler = new VeltisRegionScheduler(VeltisBootstrap.scheduler());
    private volatile DatapackManager datapackManager;

    public VeltisServer(SimpleCommandMap commandMap, PluginManager pluginManager,
                        BukkitScheduler scheduler, ServicesManager servicesManager,
                        Messenger messenger) {
        this.commandMap = commandMap;
        this.pluginManager = pluginManager;
        this.scheduler = scheduler;
        this.servicesManager = servicesManager;
        this.messenger = messenger != null ? messenger : new VeltisMessenger();
        this.consoleSender = new VeltisConsoleSender(this);
    }

    public void setMinecraftServer(Object server) {
        this.minecraftServer = server;
    }

    public void setTickEngine(TickEngine engine) {
        this.tickEngine = engine;
        engine.onTick(ctx -> {
            long now = System.nanoTime();
            tickStartNanos[tickHistoryIndex] = now;
            tickHistoryIndex = (tickHistoryIndex + 1) % TICK_HISTORY_SIZE;
            if (tickHistoryCount < TICK_HISTORY_SIZE) tickHistoryCount++;
        });
    }

    private Object mcServer() {
        return minecraftServer != null ? minecraftServer : VeltisBootstrap.MINECRAFT_SERVER;
    }

    private double computeTps(long windowNs) {
        if (tickHistoryCount < 2) return 20.0;
        int samples = Math.min(tickHistoryCount, TICK_HISTORY_SIZE);
        long now = System.nanoTime();
        int count = 0;
        long earliest = now;
        for (int i = 0; i < samples; i++) {
            int idx = (tickHistoryIndex - 1 - i + TICK_HISTORY_SIZE) % TICK_HISTORY_SIZE;
            long ts = tickStartNanos[idx];
            if (ts == 0) break;
            long age = now - ts;
            if (age > windowNs) break;
            if (ts < earliest) earliest = ts;
            count++;
        }
        if (count < 2) return 20.0;
        double elapsedSeconds = (now - earliest) / (double) NANOS_PER_SECOND;
        double tps = count / elapsedSeconds;
        return Math.min(20.0, tps);
    }

    private int invokeInt(String getter, int fallback) {
        try {
            return (int) mcServer().getClass().getMethod(getter).invoke(mcServer());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public @NotNull String getName() {
        return "VeltisMC";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0-SNAPSHOT";
    }

    @Override
    public @NotNull String getBukkitVersion() {
        return "26.2-R0.1-SNAPSHOT";
    }

    @Override
    public @NotNull Logger getLogger() {
        return Logger.getLogger("Minecraft");
    }

    @Override
    public boolean isPrimaryThread() {
        var s = mcServer();
        if (s != null) {
            try {
                var result = s.getClass().getMethod("isSameThread").invoke(s);
                if (result instanceof Boolean b) return b;
            } catch (Exception ignored) {}
            try {
                // Fallback: check against MinecraftServer.thread field
                var runningThread = s.getClass().getField("thread").get(s);
                if (runningThread == Thread.currentThread()) return true;
            } catch (Exception ignored) {}
        }
        // Last-resort thread name check
        var name = Thread.currentThread().getName();
        return name.contains("Server thread") || name.contains("Server Thread");
    }

    @Override
    public @NotNull PluginManager getPluginManager() {
        return pluginManager;
    }

    @Override
    public @NotNull BukkitScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public @NotNull ServicesManager getServicesManager() {
        return servicesManager;
    }

    @Override
    public @NotNull Messenger getMessenger() {
        return messenger;
    }

    @Override
    public @NotNull ConsoleCommandSender getConsoleSender() {
        return consoleSender;
    }

    @Override
    public @NotNull List<Player> getOnlinePlayers() {
        var s = mcServer();
        if (s == null) return List.of();
        try {
            var playerList = s.getClass().getMethod("getPlayerList").invoke(s);
            var rawPlayers = (List<?>) playerList.getClass().getMethod("getPlayers").invoke(playerList);
            // toArray() on ArrayList does not throw CME (no iterator involved —
            // uses System.arraycopy for a best-effort snapshot). This is called
            // from spark's worker pool thread while the Server thread mutates the list.
            var snapshot = rawPlayers.toArray();
            var result = new ArrayList<Player>(snapshot.length);
            for (var p : snapshot) {
                var uuid = (UUID) p.getClass().getMethod("getUUID").invoke(p);
                var bp = veltisPlayerFromMinecraft(p, uuid);
                if (bp != null) result.add(bp);
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Player veltisPlayerFromMinecraft(Object mcPlayer, UUID uuid) {
        try {
            var name = (String) mcPlayer.getClass().getMethod("getScoreboardName").invoke(mcPlayer);
            return new VeltisPlayerSender(mcPlayer, uuid, name, this);
        } catch (Exception e) {
            return null;
        }
    }

    private volatile List<World> cachedWorlds;
    private final Object worldLock = new Object();
    private int worldsCacheSerial; // incremented when world list changes

    private List<World> resolveWorlds() {
        var s = mcServer();
        if (s == null) return List.of();
        try {
            var levels = s.getClass().getMethod("getAllLevels").invoke(s);
            if (levels instanceof Iterable<?> iterable) {
                // Check if cache is still valid (same number of levels)
                int count = 0;
                for (var ignored : (Iterable<?>) iterable) count++;
                var cache = this.cachedWorlds;
                if (cache != null && cache.size() == count) {
                    // Quick identity check: all cached proxies still reference the same levels
                    return cache;
                }

                // Build new cache
                var result = new ArrayList<World>(count);
                for (var level : iterable) {
                    if (level != null) {
                        result.add(VeltisWorldProxy.create(level, this));
                    }
                }
                var unmod = Collections.unmodifiableList(result);
                this.cachedWorlds = unmod;
                return unmod;
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "Failed to get worlds: {0}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public @NotNull List<World> getWorlds() {
        return resolveWorlds();
    }

    public void invalidateWorldsCache() {
        this.cachedWorlds = null;
    }

    @Override
    public @Nullable World getWorld(@NotNull String name) {
        for (var w : getWorlds()) {
            if (w.getName().equalsIgnoreCase(name)) return w;
        }
        return null;
    }

    @Override
    public @Nullable World getWorld(@NotNull UUID uid) {
        for (var w : getWorlds()) {
            if (w.getUID().equals(uid)) return w;
        }
        return null;
    }

    @Override
    public @Nullable World getWorld(@NotNull net.kyori.adventure.key.Key worldKey) {
        return getWorld(worldKey.asString());
    }

    @Override
    public int broadcastMessage(@NotNull String message) {
        LOG.log(System.Logger.Level.INFO, message);
        for (var p : getOnlinePlayers()) p.sendMessage(message);
        return getOnlinePlayers().size();
    }

    @Override
    public int broadcast(@NotNull String message, @NotNull String permission) {
        LOG.log(System.Logger.Level.INFO, message);
        int count = 0;
        for (var p : getOnlinePlayers()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(message);
                count++;
            }
        }
        return count;
    }

    @Override
    public int broadcast(@NotNull Component message, @NotNull String permission) {
        return broadcast(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(message), permission);
    }

    @Override
    public @NotNull String getMotd() {
        var s = mcServer();
        if (s != null) {
            try {
                return (String) s.getClass().getMethod("getMotd").invoke(s);
            } catch (Exception ignored) {}
        }
        return "VeltisMC Server";
    }

    @Override
    public int getMaxPlayers() {
        return invokeInt("getMaxPlayers", 20);
    }

    @Override
    public int getPort() {
        return invokeInt("getPort", 25565);
    }

    @Override
    public @NotNull String getIp() {
        try {
            if (mcServer() != null) {
                return (String) mcServer().getClass().getMethod("getLocalIp").invoke(mcServer());
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    @Override
    public long getConnectionThrottle() {
        return 0;
    }

    @Override
    public int getViewDistance() {
        return invokeInt("getViewDistance", 10);
    }

    @Override
    public int getSimulationDistance() {
        return invokeInt("getSimulationDistance", 10);
    }

    @Override
    public boolean getOnlineMode() {
        try {
            if (mcServer() != null) {
                return (boolean) mcServer().getClass().getMethod("usesAuthentication").invoke(mcServer());
            }
        } catch (Exception ignored) {}
        return true;
    }

    @Override
    public boolean getAllowNether() {
        return true;
    }

    @Override
    public boolean getAllowEnd() {
        return true;
    }

    @Override
    public boolean hasWhitelist() {
        try {
            if (mcServer() != null) {
                return (boolean) mcServer().getClass().getMethod("hasWhitelist").invoke(mcServer());
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public @NotNull GameMode getDefaultGameMode() {
        return GameMode.SURVIVAL;
    }

    @Override
    public boolean dispatchCommand(@NotNull CommandSender sender, @NotNull String commandLine) throws CommandException {
        try {
            if (commandMap != null) {
                return commandMap.dispatch(sender, commandLine);
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public boolean isResourcePackRequired() {
        return false;
    }

    @Override
    public @Nullable String getResourcePackPrompt() {
        return null;
    }

    @Override
    public @Nullable String getResourcePackHash() {
        return null;
    }

    @Override
    public @NotNull String getResourcePack() {
        return "";
    }

    @Override
    public @NotNull List<String> getInitialDisabledPacks() {
        return List.of();
    }

    @Override
    public @NotNull List<String> getInitialEnabledPacks() {
        return List.of();
    }

    @Override
    public boolean isLoggingIPs() {
        return false;
    }

    @Override
    public int getMaxWorldSize() {
        return 0;
    }

    @Override
    public boolean getGenerateStructures() {
        return false;
    }

    @Override
    public @NotNull String getWorldType() {
        return "default";
    }

    @Override
    public void setMaxPlayers(int maxPlayers) {
    }

    @Override
    public @Nullable Player getPlayer(@NotNull String name) {
        var s = mcServer();
        if (s == null) return null;
        try {
            var playerList = s.getClass().getMethod("getPlayerList").invoke(s);
            var players = (List<?>) playerList.getClass().getMethod("getPlayers").invoke(playerList);
            for (var p : players) {
                var pName = (String) p.getClass().getMethod("getScoreboardName").invoke(p);
                if (name.equalsIgnoreCase(pName)) {
                    var uuid = (UUID) p.getClass().getMethod("getUUID").invoke(p);
                    return veltisPlayerFromMinecraft(p, uuid);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public @Nullable Player getPlayer(@NotNull UUID uuid) {
        var s = mcServer();
        if (s == null) return null;
        try {
            var playerList = s.getClass().getMethod("getPlayerList").invoke(s);
            var player = playerList.getClass().getMethod("getPlayer", UUID.class).invoke(playerList, uuid);
            if (player != null) {
                return veltisPlayerFromMinecraft(player, uuid);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public @Nullable Player getPlayerExact(@NotNull String name) {
        return getPlayer(name);
    }

    @Override
    public @NotNull List<Player> matchPlayer(@NotNull String partialName) {
        var result = new ArrayList<Player>();
        for (var p : getOnlinePlayers()) {
            if (p.getName().toLowerCase().contains(partialName.toLowerCase())) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public @Nullable OfflinePlayer getOfflinePlayer(@NotNull String name) {
        var online = getPlayer(name);
        if (online != null) return online;
        return new VeltisOfflinePlayer(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()), name, this);
    }

    @Override
    public @Nullable OfflinePlayer getOfflinePlayerIfCached(@NotNull String name) {
        return getOfflinePlayer(name);
    }

    @Override
    public @NotNull ServerConfiguration getServerConfig() {
        return null;
    }

    @Override
    public @Nullable OfflinePlayer getOfflinePlayer(@NotNull UUID id) {
        var online = getPlayer(id);
        if (online != null) return online;
        return new VeltisOfflinePlayer(id, null, this);
    }

    @Override
    public OfflinePlayer @NotNull [] getOfflinePlayers() {
        return getOnlinePlayers().toArray(new OfflinePlayer[0]);
    }

    @Override
    public @Nullable PlayerProfile createPlayerProfile(@Nullable UUID uuid, @Nullable String name) {
        try {
            var cl = getClass().getClassLoader();
            var profileClass = cl.loadClass("com.destroystokyo.paper.profile.PlayerProfile");
            var profile = profileClass.getDeclaredConstructor(UUID.class, String.class).newInstance(uuid, name);
            return (PlayerProfile) profile;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull PlayerProfile createPlayerProfile(@NotNull String name) {
        return createPlayerProfile(null, name);
    }

    @Override
    public @NotNull PlayerProfile createPlayerProfile(@NotNull UUID uniqueId) {
        return createPlayerProfile(uniqueId, null);
    }

    @Override
    public @NotNull Set<String> getIPBans() {
        return Set.of();
    }

    @Override
    public @Nullable BanList getBanList(@NotNull BanList.Type type) {
        try {
            var playerList = mcServer().getClass().getMethod("getPlayerList").invoke(mcServer());
            return switch (type) {
                case NAME, PROFILE -> new VeltisBanList(playerList.getClass().getMethod("getBans").invoke(playerList), type);
                case IP -> new VeltisBanList(playerList.getClass().getMethod("getIpBans").invoke(playerList), type);
            };
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <B extends BanList<E>, E> B getBanList(@NotNull io.papermc.paper.ban.BanListType<B> type) {
        try {
            var playerList = mcServer().getClass().getMethod("getPlayerList").invoke(mcServer());
            if (type == io.papermc.paper.ban.BanListType.PROFILE) {
                return (B) new VeltisBanList(playerList.getClass().getMethod("getBans").invoke(playerList), BanList.Type.PROFILE);
            }
            if (type == io.papermc.paper.ban.BanListType.IP) {
                return (B) new VeltisBanList(playerList.getClass().getMethod("getIpBans").invoke(playerList), BanList.Type.IP);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull Set<OfflinePlayer> getBannedPlayers() {
        return Set.of();
    }

    @Override
    public @NotNull Set<OfflinePlayer> getOperators() {
        return Set.of();
    }

    @Override
    public @NotNull Set<OfflinePlayer> getWhitelistedPlayers() {
        return Set.of();
    }

    public void shutdown() {
        var s = mcServer();
        if (s == null) return;
        try {
            s.getClass().getMethod("halt", boolean.class).invoke(s, false);
        } catch (Exception e1) {
            try {
                s.getClass().getMethod("close").invoke(s);
            } catch (Exception ignored) {}
        }
    }

    public int getTicks() {
        return (int) getCurrentTick();
    }

    @Override
    public double[] getTPS() {
        // Delegate to MinecraftServer.getTPS() if available (matching Paper)
        var s = mcServer();
        if (s != null) {
            try {
                return (double[]) s.getClass().getMethod("getTPS").invoke(s);
            } catch (Exception ignored) {}
        }
        // Fallback: compute from tick history
        return new double[]{
            computeTps(60 * NANOS_PER_SECOND),    // 1 minute
            computeTps(5 * 60 * NANOS_PER_SECOND), // 5 minutes
            computeTps(15 * 60 * NANOS_PER_SECOND) // 15 minutes
        };
    }

    @Override
    public double getAverageTickTime() {
        // Delegate to MinecraftServer.getMSPTData5s() if available (matching Paper)
        var s = mcServer();
        if (s != null) {
            try {
                var msptData = s.getClass().getMethod("getMSPTData5s").invoke(s);
                if (msptData != null) {
                    var avg = msptData.getClass().getMethod("avg").invoke(msptData);
                    if (avg instanceof Number n) return n.doubleValue();
                }
            } catch (Exception ignored) {}
        }
        // Fallback
        if (tickHistoryCount < 2) return 50.0;
        int samples = Math.min(tickHistoryCount, Math.min(TICK_HISTORY_SIZE, 200));
        long now = System.nanoTime();
        int count = 0;
        long latest = 0;
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            int idx = (tickHistoryIndex - 1 - i + TICK_HISTORY_SIZE) % TICK_HISTORY_SIZE;
            long ts = tickStartNanos[idx];
            if (ts == 0) break;
            if (ts > latest) latest = ts;
            if (ts < earliest) earliest = ts;
            count++;
        }
        if (count < 2) return 50.0;
        long span = latest - earliest;
        if (span <= 0) return 50.0;
        return Math.min(50000.0, span / (double) (count - 1) / 1_000_000.0);
    }

    @Override
    public @NotNull File getUpdateFolderFile() {
        return null;
    }

    @Override
    public @NotNull String getUpdateFolder() {
        return "update";
    }

    @Override
    public @NotNull File getPluginsFolder() {
        return new File("plugins");
    }

    @Override
    public @NotNull File getWorldContainer() {
        return new File(".");
    }

    @Override
    public @NotNull WarningState getWarningState() {
        return WarningState.DEFAULT;
    }

    @Override
    public @NotNull HelpMap getHelpMap() {
        return null;
    }

    @Override
    public @NotNull SimpleCommandMap getCommandMap() {
        return commandMap;
    }

    @Override
    public @Nullable PluginCommand getPluginCommand(@NotNull String name) {
        if (commandMap != null) {
            var cmd = commandMap.getCommand(name);
            if (cmd instanceof PluginCommand pc) return pc;
        }
        return null;
    }

    @Override
    public void sendPluginMessage(@NotNull Plugin source, @NotNull String channel, byte @NotNull [] message) {
    }

    @Override
    public @NotNull Set<String> getListeningPluginChannels() {
        return Set.of();
    }

    @Override
    public @NotNull ItemFactory getItemFactory() {
        try {
            var cl = getClass().getClassLoader();
            var cls = cl.loadClass("org.bukkit.inventory.ItemFactory");
            return (ItemFactory) Proxy.newProxyInstance(cl, new Class<?>[]{cls}, (p, m, a) -> null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull UnsafeValues getUnsafe() {
        try {
            var cl = getClass().getClassLoader();
            var cls = UnsafeValues.class;
            return (UnsafeValues) Proxy.newProxyInstance(cl, new Class<?>[]{cls}, (p, m, a) -> {
                switch (m.getName()) {
                    case "getDataVersion": return 0;
                    case "processClass":
                        if (a != null && a.length >= 3 && a[2] instanceof byte[] bytes) {
                            return org.veltismc.veltis.util.VeltisCommodore.process(bytes, true);
                        }
                        return null;
                    case "checkSupported": return null;
                    case "isSupportedApiVersion": {
                        if (a != null && a.length >= 1 && a[0] instanceof String apiVersion) {
                            try {
                                var parts = apiVersion.split("\\.");
                                var major = Integer.parseInt(parts[0]);
                                return major >= 1 && major <= 26;
                            } catch (Exception e) {
                                return false;
                            }
                        }
                        return false;
                    }
                    case "createPluginLifecycleEventManager": {
                        if (a != null && a.length >= 2
                            && a[0] instanceof org.bukkit.plugin.java.JavaPlugin jp
                            && a[1] instanceof java.util.function.BooleanSupplier bs) {
                            return new org.veltismc.veltis.plugin.lifecycle.VeltisLifecycleEventManager(jp, bs);
                        }
                        return null;
                    }
                    case "toString": return "VeltisUnsafeValues";
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public @NotNull ScoreboardManager getScoreboardManager() {
        return new VeltisScoreboardManager();
    }

    public @Nullable MapView getMap(int id) {
        return null;
    }

    @Override
    public @NotNull ItemStack createExplorerMap(@NotNull World world, @NotNull Location location, @NotNull StructureType structureType, int radius, boolean findUnexplored) {
        return null;
    }

    @Override
    public @Nullable ItemStack createExplorerMap(@NotNull World world, @NotNull Location location, @NotNull org.bukkit.generator.structure.StructureType structureType, @NotNull org.bukkit.map.MapCursor.Type mapIcon, int radius, boolean findUnexplored) {
        return null;
    }

    @Override
    public @NotNull MapView createMap(@NotNull World world) {
        return null;
    }

    public @NotNull List<Recipe> getRecipes() {
        return List.of();
    }

    public @Nullable Recipe getRecipe(@NotNull NamespacedKey key) {
        return null;
    }

    public @Nullable Recipe getCraftingRecipe(@NotNull ItemStack[] craftingMatrix, @NotNull World world) {
        return null;
    }

    public @NotNull List<Recipe> getCraftingRecipes() {
        return List.of();
    }

    public @Nullable Recipe getCraftingRecipe(@NotNull ItemStack[] craftingMatrix) {
        return null;
    }

    @Override
    public boolean addRecipe(@NotNull Recipe recipe) {
        return false;
    }

    @Override
    public boolean addRecipe(@Nullable Recipe recipe, boolean resendRecipes) {
        return false;
    }

    @Override
    public @NotNull Iterator<Recipe> recipeIterator() {
        return List.<Recipe>of().iterator();
    }

    @Override
    public @NotNull List<Recipe> getRecipesFor(@NotNull ItemStack result) {
        return List.of();
    }

    @Override
    public void updateRecipes() {
    }

    @Override
    public boolean removeRecipe(@NotNull NamespacedKey key) {
        return false;
    }

    @Override
    public boolean removeRecipe(@NotNull NamespacedKey key, boolean removeTableEntries) {
        return false;
    }

    @Override
    public void clearRecipes() {
    }

    @Override
    public void resetRecipes() {
    }

    @Override
    public @NotNull Map<String, String[]> getCommandAliases() {
        return Map.of();
    }

    @Override
    public int getSpawnRadius() {
        return 0;
    }

    @Override
    public void setSpawnRadius(int value) {
    }

    @Override
    public void setRespawnWorld(@NotNull World world) {
    }

    @Override
    public @NotNull World getRespawnWorld() {
        return null;
    }

    @Override
    public boolean getHideOnlinePlayers() {
        return false;
    }

    public boolean getAllowFlight() {
        return false;
    }

    @Override
    public boolean isHardcore() {
        return false;
    }

    public void setHardcore(boolean hardcore) {
    }

    public boolean isStopping() {
        return false;
    }

    public void savePlayers() {
    }

    public boolean addWhitelist(@NotNull OfflinePlayer player) {
        return false;
    }

    public boolean removeWhitelist(@NotNull OfflinePlayer player) {
        return false;
    }

    public boolean whitelistMessage(@NotNull String message) {
        return false;
    }

    @Override
    public void reloadWhitelist() {
    }

    @Override
    public void reload() {
    }

    @Override
    public void reloadData() {
    }

    @Override
    public void reloadPermissions() {
    }

    @Override
    public void updateResources() {
    }

    @Override
    public boolean isEnforcingSecureProfiles() {
        return true;
    }

    public @Nullable ServerTickManager getServerTickManager() {
        return null;
    }

    @Override
    public @Nullable DatapackManager getDatapackManager() {
        if (datapackManager == null) {
            // Veltis does not ship a PaperDatapackManager implementation; a lightweight
            // proxy with basic functionality is provided instead.
            datapackManager = (DatapackManager) Proxy.newProxyInstance(
                DatapackManager.class.getClassLoader(),
                new Class<?>[]{DatapackManager.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "refreshPacks": return null;
                        case "getPack": return null;
                        case "getPacks": return List.of();
                        case "getEnabledPacks": return List.of();
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "VeltisDatapackManager";
                    }
                    return null;
                });
        }
        return datapackManager;
    }

    public boolean suggestPlayersInsteadOfWhitelistCreation() {
        return false;
    }

    @Override
    public @NotNull String getMinecraftVersion() {
        return "1.21.5";
    }

    @Override
    public @Nullable World createWorld(@NotNull WorldCreator creator) {
        return null;
    }

    public boolean unloadWorld(@NotNull String name, boolean save) {
        return false;
    }

    public boolean unloadWorld(@NotNull World world, boolean save) {
        return false;
    }

    public @NotNull World createWorld(@NotNull WorldCreator creator, @NotNull World.Environment environment) {
        return null;
    }

    public @NotNull World createWorld(@NotNull WorldCreator creator, @NotNull World.Environment environment, long seed) {
        return null;
    }

    public @NotNull World createWorld(@NotNull WorldCreator creator, @NotNull World.Environment environment, @Nullable ChunkGenerator generator) {
        return null;
    }

    public @NotNull World createWorld(@NotNull WorldCreator creator, @NotNull World.Environment environment, @Nullable ChunkGenerator generator, long seed) {
        return null;
    }

    @Override
    public @NotNull WorldBorder createWorldBorder() {
        return null;
    }

    public @NotNull WorldCreator getWorldCreator(@NotNull String name) {
        return WorldCreator.name(name);
    }

    public boolean generateTree(@NotNull Location location, @NotNull TreeType type) {
        return false;
    }

    public boolean generateTree(@NotNull Location location, @NotNull TreeType type, @NotNull BlockChangeDelegate delegate) {
        return false;
    }

    @Override
    public @NotNull List<Entity> selectEntities(@NotNull CommandSender sender, @NotNull String selector) {
        return List.of();
    }

    public @NotNull StructureManager getStructureManager() {
        return null;
    }

    @Override
    public @Nullable LootTable getLootTable(@NotNull NamespacedKey key) {
        return null;
    }

    public @Nullable List<World> getWorldsByEnvironment(@NotNull World.Environment environment) {
        return List.of();
    }

    @Override
    public @Nullable Advancement getAdvancement(@NotNull NamespacedKey key) {
        return null;
    }

    @Override
    public @NotNull Iterator<Advancement> advancementIterator() {
        return Collections.emptyIterator();
    }

    @Override
    public @NotNull BlockData createBlockData(@NotNull String data) {
        return null;
    }

    @Override
    public @NotNull BlockData createBlockData(@Nullable Material material) {
        return null;
    }

    @Override
    public @NotNull BlockData createBlockData(@Nullable Material material, @Nullable String data) {
        return null;
    }

    @Override
    public @NotNull BlockData createBlockData(@NotNull Material material, @Nullable Consumer<? super BlockData> consumer) {
        return null;
    }

    public <T extends Keyed> @NotNull Tag<T> getTag(@NotNull String registry, @NotNull NamespacedKey tagKey, @NotNull Class<T> clazz) {
        return new Tag<T>() {
            @Override
            public @NotNull NamespacedKey getKey() {
                return tagKey;
            }

            @Override
            public @NotNull java.util.Set<T> getValues() {
                return java.util.Set.of();
            }

            @Override
            public boolean isTagged(@NotNull T item) {
                return false;
            }
        };
    }

    @Override
    public @NotNull <T extends Keyed> Iterable<Tag<T>> getTags(@NotNull String registry, @NotNull Class<T> clazz) {
        return List.of();
    }

    public @NotNull <T extends Keyed> Iterable<T> iterateTags(@NotNull String registry, @NotNull Class<T> clazz) {
        return List.of();
    }

    @Override
    public @Nullable <T extends Keyed> Registry<T> getRegistry(@NotNull Class<T> clazz) {
        try {
            var registryAccess = io.papermc.paper.registry.RegistryAccess.registryAccess();
            if (registryAccess instanceof org.veltismc.veltis.registry.VeltisRegistryAccess vra) {
                return vra.getRegistry(clazz);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public @NotNull List<String> getUnsafeListData() {
        return List.of();
    }

    public @NotNull Spigot spigot() {
        return new Spigot();
    }

    public @NotNull String getServerName() {
        return "VeltisMC";
    }

    public @NotNull String getServerId() {
        return "veltis";
    }

    public boolean isLoggingTicketCaches() {
        return false;
    }

    public boolean isStrictErrorHandling() {
        return false;
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, @NotNull InventoryType type) {
        return VeltisInventoryProxy.createInventory(owner, type.getDefaultSize(), net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(type.defaultTitle()));
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, @NotNull InventoryType type, @NotNull Component title) {
        return VeltisInventoryProxy.createInventory(owner, type.getDefaultSize(), net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title));
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, @NotNull InventoryType type, @NotNull String title) {
        return VeltisInventoryProxy.createInventory(owner, type.getDefaultSize(), title);
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, int size) throws IllegalArgumentException {
        return VeltisInventoryProxy.createInventory(owner, size, "container");
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, int size, @NotNull Component title) throws IllegalArgumentException {
        return VeltisInventoryProxy.createInventory(owner, size, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title));
    }

    @Override
    public @NotNull Inventory createInventory(@Nullable InventoryHolder owner, int size, @NotNull String title) throws IllegalArgumentException {
        return VeltisInventoryProxy.createInventory(owner, size, title);
    }

    public @NotNull Inventory createInventory(@NotNull InventoryHolder owner, @NotNull InventoryType type, @NotNull Component title, boolean isCustomName) {
        return VeltisInventoryProxy.createInventory(owner, type.getDefaultSize(), net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title));
    }

    public @NotNull Inventory createInventory(@NotNull InventoryHolder owner, int size, @NotNull Component title, boolean isCustomName) throws IllegalArgumentException {
        return VeltisInventoryProxy.createInventory(owner, size, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(title));
    }

    @Override
    public @NotNull ItemStack craftItem(@NotNull ItemStack[] craftingMatrix, @NotNull World world, @NotNull Player player) {
        return null;
    }

    @Override
    public @NotNull ItemStack craftItem(@NotNull ItemStack[] craftingMatrix, @NotNull World world) {
        return null;
    }

    public @NotNull ItemCraftResult craftItemResult(@NotNull ItemStack[] craftingMatrix, @NotNull World world, @NotNull Player player) {
        return null;
    }

    @Override
    public @NotNull ItemCraftResult craftItemResult(@NotNull ItemStack[] craftingMatrix, @NotNull World world) {
        return null;
    }

    public @NotNull MenuType getMenuType(@NotNull String key) {
        return null;
    }

    @Override
    public @NotNull EntityFactory getEntityFactory() {
        return null;
    }

    public @NotNull EntitySnapshot createEntitySnapshot(@NotNull Entity entity) {
        return null;
    }

    @Override
    public @NotNull CachedServerIcon getServerIcon() {
        return null;
    }

    public @NotNull CachedServerIcon loadServerIcon(@NotNull File file) throws Exception {
        return null;
    }

    public @NotNull CachedServerIcon loadServerIcon(@NotNull BufferedImage image) throws Exception {
        return null;
    }

    public @NotNull Set<CachedServerIcon> getIcons() {
        return Set.of();
    }

    public @NotNull List<Player> getPlayers() {
        return getOnlinePlayers();
    }

    public @NotNull Collection<Player> getOnlinePlayersAsCollection() {
        return getOnlinePlayers();
    }

    public @NotNull Set<String> getLoggingPluginChannels() {
        return Set.of();
    }

    public void configureDbg(ServerConfiguration serverConfiguration) {
    }

    public @NotNull Component motd() {
        return Component.text(getMotd());
    }

    public @NotNull Component serverIcon() {
        return Component.empty();
    }

    public @NotNull Component serverBrand() {
        return Component.text("VeltisMC");
    }

    public @NotNull Iterable<? extends net.kyori.adventure.audience.Audience> audiences() {
        return getOnlinePlayers();
    }

    public @NotNull BossBar createBossBar(@NotNull String title, @NotNull BarColor color, @NotNull BarStyle style, @NotNull BarFlag... flags) {
        return null;
    }

    public @NotNull BossBar createBossBar(@NotNull Component title, @NotNull BarColor color, @NotNull BarStyle style, @NotNull BarFlag... flags) {
        return null;
    }

    public @NotNull Iterator<KeyedBossBar> iterateBossBars() {
        return Collections.emptyIterator();
    }

    public @NotNull KeyedBossBar createBossBar(@NotNull NamespacedKey key, @NotNull Component title, @NotNull BarColor color, @NotNull BarStyle style, @NotNull BarFlag... flags) {
        return null;
    }

    @Override
    public @NotNull KeyedBossBar createBossBar(@NotNull NamespacedKey key, @Nullable String title, @NotNull BarColor color, @NotNull BarStyle style, @NotNull BarFlag... flags) {
        return null;
    }

    @Override
    public @NotNull KeyedBossBar getBossBar(@NotNull NamespacedKey key) {
        return null;
    }

    public boolean removeBossBar(@NotNull NamespacedKey key) {
        return false;
    }

    public @NotNull Criteria getScoreboardCriteria(@NotNull String string) {
        return null;
    }

    public @NotNull GameRule<?> getGameRuleDefault(@NotNull GameRule<?> gameRule) {
        return null;
    }

    public int getPlayerCount() {
        return getOnlinePlayers().size();
    }

    @Override
    public boolean isWhitelistEnforced() {
        return false;
    }

    public void setWhitelistEnforced(boolean b) {
    }

    @Override
    public void setWhitelist(boolean value) {
    }

    public void allowPausing(@NotNull org.bukkit.plugin.Plugin plugin, boolean value) {
    }

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public boolean isGlobalTickThread() {
        return isPrimaryThread();
    }

    @Override
    public @NotNull GlobalRegionScheduler getGlobalRegionScheduler() {
        return globalRegionScheduler;
    }

    @Override
    public @NotNull AsyncScheduler getAsyncScheduler() {
        return asyncScheduler;
    }

    @Override
    public @NotNull RegionScheduler getRegionScheduler() {
        return regionScheduler;
    }

    @Override
    public @NotNull org.bukkit.potion.PotionBrewer getPotionBrewer() {
        return null;
    }

    @Override
    public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
        return null;
    }

    @Override
    public int getCurrentTick() {
        // Paper: return net.minecraft.server.MinecraftServer.currentTick
        var s = mcServer();
        if (s != null) {
            try {
                var field = s.getClass().getField("currentTick");
                var val = field.getInt(s);
                if (val != 0) return val;
            } catch (Exception ignored) {}
            try {
                var field = s.getClass().getSuperclass().getField("currentTick");
                var val = field.getInt(s);
                if (val != 0) return val;
            } catch (Exception ignored) {}
        }
        // Fallback to tick engine
        var engine = tickEngine;
        if (engine == null) engine = VeltisBootstrap.tickEngine();
        return engine != null ? (int) engine.currentTick() : 0;
    }

    @Override
    public @NotNull net.kyori.adventure.text.Component permissionMessage() {
        return Component.empty();
    }

    @Override
    public @NotNull String getPermissionMessage() {
        return "";
    }

    @Override
    public boolean suggestPlayerNamesWhenNullTabCompletions() {
        return true;
    }

    @Override
    public boolean reloadCommandAliases() {
        return true;
    }

    @Override
    public void restart() {
    }

    @Override
    public long @NotNull [] getTickTimes() {
        // Delegate to MinecraftServer.getMSPTData5s() if available (matching Paper)
        var s = mcServer();
        if (s != null) {
            try {
                var msptData = s.getClass().getMethod("getMSPTData5s").invoke(s);
                if (msptData != null) {
                    var raw = msptData.getClass().getMethod("rawData").invoke(msptData);
                    if (raw instanceof long[]) return ((long[]) raw).clone();
                    return new long[0];
                }
            } catch (Exception ignored) {}
        }
        // Fallback
        int samples = Math.min(tickHistoryCount, TICK_HISTORY_SIZE);
        if (samples < 2) return new long[0];
        long[] durations = new long[samples - 1];
        long prev = 0;
        int outIdx = 0;
        for (int i = 0; i < samples; i++) {
            int idx = (tickHistoryIndex - 1 - i + TICK_HISTORY_SIZE) % TICK_HISTORY_SIZE;
            long ts = tickStartNanos[idx];
            if (ts == 0) break;
            if (prev != 0) {
                long diffNs = prev - ts;
                durations[outIdx++] = Math.max(0, diffNs / NANOS_PER_MICRO);
            }
            prev = ts;
        }
        if (outIdx < durations.length) {
            long[] trimmed = new long[outIdx];
            System.arraycopy(durations, 0, trimmed, 0, outIdx);
            return trimmed;
        }
        return durations;
    }

    @Override
    public @Nullable Entity getEntity(@NotNull UUID uuid) {
        return null;
    }

    @Override
    public @NotNull Iterator<KeyedBossBar> getBossBars() {
        return null;
    }

    @Override
    public @NotNull ChunkGenerator.ChunkData createChunkData(@NotNull World world) {
        return null;
    }

    @Override
    public void setPauseWhenEmptyTime(int seconds) {
    }

    @Override
    public int getPauseWhenEmptyTime() {
        return 0;
    }

    @Override
    public int getIdleTimeout() {
        return 0;
    }

    @Override
    public void setIdleTimeout(int threshold) {
    }

    @Override
    public @NotNull ServerLinks getServerLinks() {
        return null;
    }

    @Override
    public void setMotd(@NotNull String motd) {
    }

    @Override
    public boolean isAcceptingTransfers() {
        return false;
    }

    @Override
    public @Nullable Component shutdownMessage() {
        return null;
    }

    @Override
    public void motd(@NotNull Component motd) {
    }

    @Override
    public int getSpawnLimit(@NotNull SpawnCategory spawnCategory) {
        return 0;
    }

    @Override
    public @NotNull Merchant createMerchant() {
        return null;
    }

    @Override
    public @NotNull Merchant createMerchant(@Nullable String title) {
        return null;
    }

    @Override
    public @NotNull Merchant createMerchant(@Nullable Component title) {
        return null;
    }

    @Override
    public @NotNull Path getLevelDirectory() {
        return null;
    }

    @Override
    public @NotNull CommandSender createCommandSender(@NotNull Consumer<? super Component> feedback) {
        return null;
    }

    @Override
    public boolean forcesDefaultGameMode() {
        return true;
    }

    @Override
    public void setDefaultGameMode(@NotNull GameMode mode) {
    }

    @Override
    public @NotNull com.destroystokyo.paper.profile.PlayerProfile createProfile(@NotNull UUID uuid) {
        return null;
    }

    @Override
    public @NotNull com.destroystokyo.paper.profile.PlayerProfile createProfile(@NotNull String name) {
        return null;
    }

    @Override
    public @NotNull com.destroystokyo.paper.profile.PlayerProfile createProfile(@Nullable UUID uuid, @Nullable String name) {
        return null;
    }

    @Override
    public com.destroystokyo.paper.profile.PlayerProfile createProfileExact(@Nullable UUID uuid, @Nullable String name) {
        return null;
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Entity entity) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull World world, int chunkX, int chunkZ, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull World world, int chunkX, int chunkZ) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Location location, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull Location location) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull World world, @NotNull Position position) {
        return isPrimaryThread();
    }

    @Override
    public boolean isOwnedByCurrentRegion(@NotNull World world, @NotNull Position position, int squareRadiusChunks) {
        return isPrimaryThread();
    }

    public boolean isPlayerCommandSendAllowed() {
        return true;
    }

    public @Nullable Player getPlayerUniqueId(@NotNull UUID uuid) {
        return getPlayer(uuid);
    }

    @Override
    public @NotNull UUID getPlayerUniqueId(@NotNull String playerName) {
        return null;
    }

    @Override
    public boolean isTickingWorlds() {
        return false;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayer(@NotNull PlayerProfile playerProfile, @Nullable String reason, @Nullable Date expires, @Nullable String source) {
        return null;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayer(@NotNull PlayerProfile playerProfile, @Nullable String reason, @Nullable Instant expires, @Nullable String source) {
        return null;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayer(@NotNull PlayerProfile playerProfile, @Nullable String reason, @Nullable Duration duration, @Nullable String source) {
        return null;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayerIP(@NotNull InetAddress address, @Nullable String reason, @Nullable Date expires, @Nullable String source) {
        return null;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayerIP(@NotNull InetAddress address, @Nullable String reason, @Nullable Instant expires, @Nullable String source) {
        return null;
    }

    public @Nullable BanEntry<PlayerProfile> banPlayerIP(@NotNull InetAddress address, @Nullable String reason, @Nullable Duration duration, @Nullable String source) {
        return null;
    }

    public @NotNull BanEntry<PlayerProfile> broadcastBanTemplate(@NotNull BanEntry<PlayerProfile> banEntry) {
        return banEntry;
    }

    public void unbanIP(@NotNull String address) {
    }

    public void unbanIP(@NotNull InetAddress address) {
    }

    public void banIP(@NotNull String address) {
    }

    public void banIP(@NotNull InetAddress address) {
    }

    public @Nullable ResourcePack getRequiredResourcePack() {
        return null;
    }

    @Override
    public @Nullable ResourcePack getServerResourcePack() {
        return null;
    }

    public @Nullable Component getMotdComponent() {
        return Component.text(getMotd());
    }

    @Override
    public @Nullable String getShutdownMessage() {
        return "Server closed";
    }

    public @Nullable Path getOptionSet(@NotNull String option) {
        return null;
    }

    public @Nullable Boolean getAllowClientCache() {
        return false;
    }

    public @Nullable SpawnCategory getSpawnCategory(@NotNull String category) {
        return null;
    }

    @Override
    public int getTicksPerSpawns(@NotNull SpawnCategory spawnCategory) {
        return 1;
    }

    @Override
    public int getMonsterSpawnLimit() {
        return 70;
    }

    @Override
    public int getAnimalSpawnLimit() {
        return 10;
    }

    @Override
    public int getAmbientSpawnLimit() {
        return 15;
    }

    @Override
    public int getWaterAnimalSpawnLimit() {
        return 5;
    }

    @Override
    public int getWaterAmbientSpawnLimit() {
        return 20;
    }

    @Override
    public int getWaterUndergroundCreatureSpawnLimit() {
        return 5;
    }

    public int getAxolotlSpawnLimit() {
        return 5;
    }

    public boolean isCommandBlockOutputEnabled() {
        return true;
    }

    public boolean isSendCommandFeedback() {
        return true;
    }

    public int getMonsterSpawnCount() {
        return 70;
    }

    public boolean isMonsterSpawnIgnoringPlayers() {
        return false;
    }

    public boolean stopMonsterSpawning() {
        return false;
    }

    public boolean isPufferfishIcons() {
        return false;
    }

    public boolean isPufferfishProtocolFix() {
        return false;
    }

    public boolean isPufferfishBookFix() {
        return false;
    }

    public boolean isPufferfishBooksFixLog() {
        return false;
    }

    public int getPufferfishEntityMergeSpawnRate() {
        return 0;
    }

    public boolean isPufferfishSpawnLogging() {
        return false;
    }

    public boolean isPufferfishKillLogging() {
        return false;
    }

    public String getPufferfishOptimizationMode() {
        return "disabled";
    }

    public boolean isPufferfishMenuOptimization() {
        return false;
    }

    public boolean isPufferfishSpawnCounts() {
        return false;
    }

    @Override
    public int getMaxChainedNeighborUpdates() {
        return 1000000;
    }

    public boolean isVectorContainersEnabled() {
        return false;
    }

    public boolean getShowDatapackDownloadMenu() {
        return false;
    }

    public boolean isVanillaPermissionSystem() {
        return false;
    }

    public boolean isProxyOnlineMode() {
        return false;
    }

    public boolean isProxyPreventProxyConnections() {
        return false;
    }

    public boolean isProxyPreventProxyDoubleHandshake() {
        return false;
    }

    public boolean isProxyPreventProxyForwarding() {
        return false;
    }

    public boolean isProxyPreventProxyIpForwarding() {
        return false;
    }

    public boolean isProxyPreventProxyMotd() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerCount() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerList() {
        return false;
    }

    public boolean isProxyPreventProxyBrand() {
        return false;
    }

    public boolean isProxyPreventProxyChat() {
        return false;
    }

    public boolean isProxyPreventProxyCommand() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerCommand() {
        return false;
    }

    public boolean isProxyPreventProxyServerCommand() {
        return false;
    }

    public boolean isProxyPreventProxyAdvancement() {
        return false;
    }

    public boolean isProxyPreventProxyDeath() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListHeader() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListFooter() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListName() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListDisplayName() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListScoreboard() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeam() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListRank() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListPrefix() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListSuffix() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListCustomName() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListCustomNameVisible() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListGlowing() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamColor() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListCollision() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListSeeFriendlyInvisibles() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListAllowFriendlyFire() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListNameTagVisibility() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListDeathMessageVisibility() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListPrefixOrSuffix() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamDisplayName() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamPrefix() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamSuffix() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamColor2() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamAllowFriendlyFire() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamSeeFriendlyInvisibles() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamNameTagVisibility() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamDeathMessageVisibility() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamCollisionRule() {
        return false;
    }

    public boolean isProxyPreventProxyPlayerListTeamPrefixOrSuffix() {
        return false;
    }
}
