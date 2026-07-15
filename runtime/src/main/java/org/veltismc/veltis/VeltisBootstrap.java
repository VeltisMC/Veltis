package org.veltismc.veltis;

import org.veltismc.veltis.command.VeltisDiagnoseCommands;
import org.veltismc.veltis.command.brigadier.VeltisCommandsRegistrar;
import org.veltismc.veltis.plugin.lifecycle.VeltisLifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.manager.PaperPluginManagerImpl;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.SimpleServicesManager;
import org.veltismc.veltis.plugin.compat.VeltisCommandMap;
import org.veltismc.veltis.registry.VeltisRegistryAccess;
import org.veltismc.veltis.server.scheduler.VeltisScheduler;
import org.veltismc.veltis.server.tick.DefaultTickEngine;
import org.veltismc.veltis.server.tick.TickEngine;
import io.papermc.paper.SparksFly;
import io.papermc.paper.SparksFlyHolder;

public final class VeltisBootstrap {

    private static final System.Logger LOG = System.getLogger(VeltisBootstrap.class.getName());

    private static boolean booted;
    private static Path HOME_DIR;
    private static Server bukkitServer;
    private static org.bukkit.plugin.PluginManager bukkitPluginManager;
    private static CommandMap bukkitCommandMap;
    private static BukkitScheduler bukkitScheduler;
    private static ServicesManager bukkitServicesManager;
    private static org.veltismc.veltis.server.scheduler.TaskScheduler taskScheduler;
    public static volatile Object MINECRAFT_SERVER;
    private static volatile TickEngine TICK_ENGINE;
    public static SparksFly VELTIS_FLY;

    static {
        // Initialize the holder class so SparksFlyHolder.instance is available
        try {
            Class.forName("io.papermc.paper.SparksFlyHolder", true, VeltisBootstrap.class.getClassLoader());
        } catch (final ClassNotFoundException e) {
            // Should not happen
        }
    }

    public static void setTickEngine(TickEngine engine) {
        TICK_ENGINE = engine;
        if (bukkitServer instanceof VeltisServer vs) {
            vs.setTickEngine(engine);
        }
        // NOTE: Tick callbacks (tickStart/tickEnd/executeMainThreadTasks) are called
        // directly from MinecraftServerSparkMixin via VeltisBootstrap.VELTIS_FLY.
    }

    public static TickEngine tickEngine() {
        return TICK_ENGINE;
    }

    private static final ConcurrentHashMap<String, Object> BUKKIT_COMMANDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> WORLD_SCHEDULERS = new ConcurrentHashMap<>();

    public static void registerBukkitCommand(String name, Object command) {
        if (name != null && !name.isBlank()) {
            var key = name.toLowerCase(java.util.Locale.ROOT);
            BUKKIT_COMMANDS.put(key, command);
            // Also register in the command map's knownCommands so Bukkit tab-complete fallback works
            if (command instanceof org.bukkit.command.Command cmd && veltisCommandMap != null) {
                veltisCommandMap.registerKnownCommand(key, cmd);
            }
        }
    }

    public static Object getBukkitCommand(String name) {
        return name != null ? BUKKIT_COMMANDS.get(name.toLowerCase(java.util.Locale.ROOT)) : null;
    }

    public static java.util.Map<String, Object> getKnownCommands() {
        return java.util.Collections.unmodifiableMap(BUKKIT_COMMANDS);
    }

    public static void removeBukkitCommand(String name) {
        if (name != null) {
            var key = name.toLowerCase(java.util.Locale.ROOT);
            BUKKIT_COMMANDS.remove(key);
            if (veltisCommandMap != null) {
                veltisCommandMap.removeKnownCommand(key);
            }
        }
    }

    private VeltisBootstrap() {}

    public static void boot(String[] args) {
        if (booted) return;
        booted = true;

        var homeDir = detectHomeDirectory(args);
        HOME_DIR = homeDir;
        ensureDirectories(homeDir);
        org.veltismc.veltis.config.VeltisPaperConfig.load(homeDir);

        taskScheduler = new org.veltismc.veltis.server.scheduler.DefaultTaskScheduler();

        createBukkitBridge();

        // Load plugins early (discovery + instantiation) — before NMS starts,
        // matching Paper's plugin-loads-before-world behavior.
        // Enabling happens later in onMinecraftServerCreated().
        if (bukkitPluginManager instanceof PaperPluginManagerImpl ppm) {
            var pluginsDir = HOME_DIR.resolve("plugins").toFile();
            if (pluginsDir.isDirectory()) {
                ppm.loadPlugins(pluginsDir);
                LOG.log(System.Logger.Level.INFO, "[VeltisMC] Pre-loaded {0} plugins", ppm.getPlugins().length);
            }
        }

        LOG.log(System.Logger.Level.INFO, "[VeltisMC] Bootstrap infrastructure ready");
    }

    private static void createBukkitBridge() {
        try {
            bukkitCommandMap = createBukkitCommandMap();
            bukkitScheduler = createBukkitScheduler();
            bukkitServicesManager = createBukkitServicesManager();

            var veltisPluginManager = new PaperPluginManagerImpl(null, (SimpleCommandMap) bukkitCommandMap, null);
            bukkitPluginManager = veltisPluginManager;

            var veltisServer = new VeltisServer(
                (SimpleCommandMap) bukkitCommandMap,
                bukkitPluginManager,
                bukkitScheduler,
                bukkitServicesManager,
                null);
            bukkitServer = veltisServer;
            veltisPluginManager.setServer(veltisServer);

            var bukkitClass = Class.forName("org.bukkit.Bukkit");
            var serverField = bukkitClass.getDeclaredField("server");
            serverField.setAccessible(true);
            var existingServer = serverField.get(null);
            if (existingServer != null) {
                LOG.log(System.Logger.Level.INFO, "[VeltisMC] Bukkit.server already set to: {0}@{1}",
                existingServer.getClass().getName(), System.identityHashCode(existingServer));
            }
            serverField.set(null, bukkitServer);
            org.bukkit.inventory.ItemStack.setDelegateFactory(
                (type, amount) -> new org.veltismc.veltis.inventory.VeltisItemStack(type, amount));
            LOG.log(System.Logger.Level.INFO, "[VeltisMC] Bukkit bridge created and set");
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Bukkit bridge creation failed", e);
        }
    }

    public static void onMinecraftServerCreated(Object server) {
        if (bukkitServer == null) return;
        if (MINECRAFT_SERVER != null) return;
        MINECRAFT_SERVER = server;
        VeltisRegistryAccess.init(server);
        VeltisRegistryAccess.bootstrap();

        try {
            var startupLog = System.getLogger("VeltisMC.Startup");

            // Initialize Brigadier bridge for command registration
            var commandsObj = server.getClass().getMethod("getCommands").invoke(server);
            var nmsDispatcher = (com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>)
                commandsObj.getClass().getMethod("getDispatcher").invoke(commandsObj);
            var brigeMap = io.papermc.paper.command.brigadier.bukkit.BukkitBrigForwardingMap.INSTANCE;
            brigeMap.initBridge(nmsDispatcher);

            // Enable plugins (instances already loaded during boot()) now that the world is ready
            if (bukkitPluginManager instanceof PaperPluginManagerImpl ppm) {
                for (var plugin : ppm.getPlugins()) {
                    ppm.enablePlugin(plugin);
                }
                startupLog.log(System.Logger.Level.INFO, "[VeltisMC] Enabled {0} plugins", ppm.getPlugins().length);
            }

            // Fire lifecycle events for Commands — plugins registered handlers in onEnable()
            var commandsRegistrar = new VeltisCommandsRegistrar(nmsDispatcher);
            LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(
                LifecycleEvents.COMMANDS,
                commandsRegistrar,
                LifecycleEventOwner.class,
                ReloadableRegistrarEvent.Cause.INITIAL
            );
            startupLog.log(System.Logger.Level.INFO, "[VeltisMC] Fired lifecycle commands event");

            // Re-sync any commands already in the map
            brigeMap.reSyncAll();

            // Initialize Spark built-in profiler (Paper-style via SparksFly)
            try {
                var sparksFly = new SparksFly(bukkitServer);
                sparksFly.setMinecraftServer((net.minecraft.server.MinecraftServer) server);
                sparksFly.enableBeforePlugins();
                sparksFly.registerCommandBeforePlugins(bukkitServer);
                VELTIS_FLY = sparksFly;
                SparksFlyHolder.instance = sparksFly;

                var tickEng = tickEngine();
                if (tickEng == null) {
                    tickEng = new DefaultTickEngine();
                    setTickEngine(tickEng);
                    tickEng.start();
                }

                java.lang.Runtime.getRuntime().addShutdownHook(new Thread(sparksFly::disable));

                startupLog.log(System.Logger.Level.INFO, "[VeltisMC] Spark profiler initialized");
            } catch (Exception e) {
                startupLog.log(System.Logger.Level.WARNING, "[VeltisMC] Spark init failed: {0}", e.getMessage());
            }

            registerVeltisCommands(server);
            createWorldSchedulers(server);
            startupLog.log(System.Logger.Level.INFO, "[VeltisMC] Bukkit bridge initialized, {0} plugins enabled",
                bukkitPluginManager.getPlugins().length);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Bukkit bridge failed", e);
        }
    }

    private static Object handleBukkitMethod(Method method, Object[] args) throws Exception {
        var server = MINECRAFT_SERVER;
        switch (method.getName()) {
            case "getName": return "VeltisMC";
            case "getVersion": return "1.0.0-SNAPSHOT";
            case "getBukkitVersion": return "26.2-R0.1-SNAPSHOT";
            case "getLogger": return Logger.getLogger("Minecraft");
            case "isPrimaryThread":
                if (server != null) return invokeServerBool(server, "isSameThread", true);
                var name = Thread.currentThread().getName();
                return name.contains("Server thread") || name.contains("Server Thread");
            case "getPluginManager": return bukkitPluginManager;
            case "getScheduler": return bukkitScheduler;
            case "getServicesManager": return bukkitServicesManager;
            case "getConsoleSender": return createConsoleSender();
            case "getOnlinePlayers": {
                if (server == null) return List.of();
                try {
                    return getAllOnlinePlayers(server);
                } catch (Exception e) {
                    return List.of();
                }
            }
            case "getWorlds": return List.of();
            case "shutdown": return null;
            case "getMaxPlayers": return server != null ? invokeServerInt(server, "getMaxPlayers", 20) : 20;
            case "getPort": return server != null ? invokeServerInt(server, "getPort", 25565) : 25565;
            case "getMotd": return server != null ? invokeServerString(server, "getMotd", "VeltisMC Server") : "VeltisMC Server";
            case "getIp": return server != null ? invokeServerString(server, "getLocalIp", "0.0.0.0") : "0.0.0.0";
            case "getViewDistance": return server != null ? invokeServerInt(server, "getViewDistance", 10) : 10;
            case "getSimulationDistance": return server != null ? invokeServerInt(server, "getSimulationDistance", 10) : 10;
            case "getOnlineMode": return server != null ? invokeServerBool(server, "usesAuthentication", true) : true;
            case "getAllowNether": return true;
            case "getAllowEnd": return true;
            case "hasWhitelist": return server != null ? invokeServerBool(server, "hasWhitelist", false) : false;
            case "getDefaultGameMode": return GameMode.SURVIVAL;
            case "dispatchCommand": {
                // Route through SimpleCommandMap
                if (args != null && args.length >= 2 && args[1] instanceof String cmdLine) {
                    var sender = args[0] instanceof CommandSender cs ? cs : createConsoleSender();
                    try {
                        var map = (CommandMap) bukkitCommandMap;
                        return map.dispatch(sender, cmdLine);
                    } catch (Exception ignored) {}
                }
                return false;
            }
            case "getPlayer": {
                if (args != null && args.length > 0 && server != null) {
                    if (args[0] instanceof UUID uuid) {
                        return createBukkitPlayerFromMinecraft(server, uuid);
                    }
                    if (args[0] instanceof String pn) {
                        return getPlayerByName(server, pn);
                    }
                }
                return null;
            }
            case "getPlayerExact": {
                if (args != null && args.length > 0 && args[0] instanceof String pn && server != null) {
                    return getPlayerByName(server, pn);
                }
                return null;
            }
            case "matchPlayer": {
                if (args != null && args.length > 0 && args[0] instanceof String partial && server != null) {
                    return matchPlayersByName(server, partial);
                }
                return List.of();
            }
            case "getOfflinePlayer": return null;
            case "getOfflinePlayers": return new Object[0];
            case "broadcastMessage": LOG.log(System.Logger.Level.INFO, String.valueOf(args[0])); return 0;
            case "broadcast": LOG.log(System.Logger.Level.INFO, String.valueOf(args[0])); return 0;
            case "getWorldContainer": return new java.io.File(".");
            case "isEnforcingSecureProfiles": return true;
            case "getServerTickManager": return null;
            case "getDataPackManager": return null;
            case "getPluginCommand": return getBukkitCommand(String.valueOf(args[0]));
            case "getCommandMap": return bukkitCommandMap;
            case "getHelpMap": return null;
            case "getMessenger": return null;
            case "spigot": return null;
            case "getUnsafe": return createBukkitUnsafe();
            case "getAsyncScheduler": return createAsyncScheduler();
            case "getGlobalRegionScheduler": return createGlobalRegionScheduler();
            case "getRegionScheduler": return createRegionScheduler();
        }
        return null;
    }

    private static VeltisCommandMap veltisCommandMap;

    private static CommandMap createBukkitCommandMap() throws Exception {
        veltisCommandMap = new VeltisCommandMap(bukkitServer);
        return veltisCommandMap;
    }

    private static SimpleServicesManager bukkitServicesManagerImpl;

    private static ServicesManager createBukkitServicesManager() throws Exception {
        bukkitServicesManagerImpl = new SimpleServicesManager();
        return bukkitServicesManagerImpl;
    }

    private static UnsafeValues createBukkitUnsafe() throws Exception {
        return (UnsafeValues) Proxy.newProxyInstance(
            UnsafeValues.class.getClassLoader(), new Class<?>[]{UnsafeValues.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "VeltisUnsafeValues";
                    case "checkSupported": return null;
                    case "createEmptyStack": {
                        return new org.veltismc.veltis.inventory.VeltisItemStack(Material.AIR, 0);
                    }
                }
                return null;
            });
    }

    private static VeltisScheduler veltisScheduler;

    public static VeltisScheduler veltisScheduler() {
        return veltisScheduler;
    }

    private static org.veltismc.veltis.server.scheduler.TaskHandle createPaperScheduledTaskHandle() {
        return new org.veltismc.veltis.server.scheduler.TaskHandle() {
            private volatile org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState state =
                org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.SCHEDULED;

            @Override public long id() { return 0; }
            @Override public Runnable runnable() { return null; }
            @Override public org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState state() { return state; }
            @Override public boolean cancelled() { return state == org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.CANCELLED; }
            @Override public void cancel() { state = org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.CANCELLED; }
            @Override public long executionCount() { return 0; }
            @Override public long delayMs() { return 0; }
            @Override public long periodMs() { return 0; }
            @Override public boolean isRepeating() { return false; }
            @Override public boolean hasExecuted() { return false; }
        };
    }

    private static Object createAsyncScheduler() throws Exception {
        try {
            var asClass = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            return Proxy.newProxyInstance(asClass.getClassLoader(), new Class<?>[]{asClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "VeltisAsyncScheduler";
                        case "runNow":
                            if (args != null && args.length >= 2 && args[1] instanceof java.util.function.Consumer c
                                && args[0] instanceof Plugin plugin) {
                                var handle = createPaperScheduledTaskHandle();
                                var task = new VeltisPaperScheduledTask(plugin, handle, false);
                                var t = new Thread(() -> {
                                    task.onStart();
                                    c.accept(task);
                                    task.onFinish();
                                });
                                t.setDaemon(true);
                                t.start();
                            }
                            return null;
                    }
                    return null;
                });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object createGlobalRegionScheduler() throws Exception {
        try {
            var grsClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            return Proxy.newProxyInstance(grsClass.getClassLoader(), new Class<?>[]{grsClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "VeltisGlobalRegionScheduler";
                        case "run":
                            if (args != null && args.length >= 2 && args[1] instanceof java.util.function.Consumer c
                                && args[0] instanceof Plugin plugin) {
                                var handle = createPaperScheduledTaskHandle();
                                var task = new VeltisPaperScheduledTask(plugin, handle, false);
                                task.onStart();
                                c.accept(task);
                                task.onFinish();
                            }
                            return null;
                        case "execute":
                            if (args != null && args.length >= 1 && args[0] instanceof Runnable r) {
                                r.run();
                            }
                            return null;
                    }
                    return null;
                });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object createRegionScheduler() throws Exception {
        try {
            var rsClass = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return Proxy.newProxyInstance(rsClass.getClassLoader(), new Class<?>[]{rsClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "VeltisRegionScheduler";
                        case "run":
                            if (args != null && args.length >= 3 && args[2] instanceof java.util.function.Consumer c
                                && args[0] instanceof Plugin plugin) {
                                var handle = createPaperScheduledTaskHandle();
                                var task = new VeltisPaperScheduledTask(plugin, handle, false);
                                task.onStart();
                                c.accept(task);
                                task.onFinish();
                            }
                            return null;
                        case "execute":
                            if (args != null && args.length >= 2 && args[1] instanceof Runnable r) {
                                r.run();
                            }
                            return null;
                    }
                    return null;
                });
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static VeltisScheduler createBukkitScheduler() throws Exception {
        veltisScheduler = new VeltisScheduler(taskScheduler);
        return veltisScheduler;
    }

    private static ConsoleCommandSender createConsoleSender() throws Exception {
        return new VeltisConsoleSender(bukkitServer);
    }

    /**
     * Creates a Bukkit Player proxy from a Minecraft ServerPlayer looked up by UUID.
     */
    private static Object createBukkitPlayerFromMinecraft(Object server, UUID uuid) {
        try {
            var playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            var getPlayer = playerList.getClass().getMethod("getPlayer", UUID.class);
            var mcPlayer = getPlayer.invoke(playerList, uuid);
            if (mcPlayer == null) return null;

            var cl = server.getClass().getClassLoader();
            var componentClass = cl.loadClass("net.minecraft.network.chat.Component");
            var componentLiteralMethod = componentClass.getMethod("literal", String.class);

            return resolveBukkitSender(mcPlayer, CommandSender.class, Player.class, componentClass, componentLiteralMethod, cl);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Failed to create Bukkit player: {0}", e.getMessage());
            return null;
        }
    }

    private static Object getPlayerByName(Object server, String name) {
        try {
            var playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            var getPlayers = playerList.getClass().getMethod("getPlayers");
            var players = (List<?>) getPlayers.invoke(playerList);
            var cl = server.getClass().getClassLoader();
            var componentClass = cl.loadClass("net.minecraft.network.chat.Component");
            var componentLiteralMethod = componentClass.getMethod("literal", String.class);
            for (var p : players) {
                try {
                    var pName = p.getClass().getMethod("getName").invoke(p);
                    if (name.equalsIgnoreCase(String.valueOf(pName))) {
                        return createPlayerSenderProxy(p, CommandSender.class, Player.class,
                            componentClass, componentLiteralMethod, cl);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static List<Object> matchPlayersByName(Object server, String partial) {
        var result = new ArrayList<Object>();
        try {
            var playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            var getPlayers = playerList.getClass().getMethod("getPlayers");
            var players = (List<?>) getPlayers.invoke(playerList);
            var cl = server.getClass().getClassLoader();
            var componentClass = cl.loadClass("net.minecraft.network.chat.Component");
            var componentLiteralMethod = componentClass.getMethod("literal", String.class);
            for (var p : players) {
                try {
                    var pName = String.valueOf(p.getClass().getMethod("getName").invoke(p));
                    if (pName.toLowerCase(java.util.Locale.ROOT).contains(partial.toLowerCase(java.util.Locale.ROOT))) {
                        result.add(createPlayerSenderProxy(p, CommandSender.class, Player.class,
                            componentClass, componentLiteralMethod, cl));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static List<Object> getAllOnlinePlayers(Object server) {
        var result = new ArrayList<Object>();
        try {
            var playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            var getPlayers = playerList.getClass().getMethod("getPlayers");
            var players = (List<?>) getPlayers.invoke(playerList);
            var cl = server.getClass().getClassLoader();
            var componentClass = cl.loadClass("net.minecraft.network.chat.Component");
            var componentLiteralMethod = componentClass.getMethod("literal", String.class);
            for (var p : players) {
                result.add(createPlayerSenderProxy(p, CommandSender.class, Player.class,
                    componentClass, componentLiteralMethod, cl));
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * Creates a Bukkit PlayerJoinEvent proxy via reflection.
     */
    private static Object createBukkitPlayerJoinEvent(Object bukkitPlayer, String joinMessage) {
        try {
            var constructor = PlayerJoinEvent.class.getConstructor(Player.class, String.class);
            return constructor.newInstance(bukkitPlayer, joinMessage);
        } catch (Exception e) {
            try {
                var cl = bukkitPlayer.getClass().getClassLoader();
                return Proxy.newProxyInstance(cl, new Class<?>[]{PlayerJoinEvent.class, Event.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPlayer")) return bukkitPlayer;
                        if (method.getName().equals("getJoinMessage")) return joinMessage;
                        if (method.getName().equals("getEventName")) return "PlayerJoinEvent";
                        return null;
                    });
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Failed to create PlayerJoinEvent: {0}", ex.getMessage());
                return null;
            }
        }
    }

    private static Object createBukkitPlayerQuitEvent(Object bukkitPlayer, String quitMessage) {
        try {
            var constructor = PlayerQuitEvent.class.getConstructor(Player.class, String.class);
            return constructor.newInstance(bukkitPlayer, quitMessage);
        } catch (Exception e) {
            try {
                var cl = bukkitPlayer.getClass().getClassLoader();
                return Proxy.newProxyInstance(cl, new Class<?>[]{PlayerQuitEvent.class, Event.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPlayer")) return bukkitPlayer;
                        if (method.getName().equals("getQuitMessage")) return quitMessage;
                        if (method.getName().equals("getEventName")) return "PlayerQuitEvent";
                        return null;
                    });
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Failed to create PlayerQuitEvent: {0}", ex.getMessage());
                return null;
            }
        }
    }

    /**
     * Creates a {@code DefaultWorldScheduler} for every loaded Minecraft world
     * so per-world thread scheduling is available.
     */
    private static void createWorldSchedulers(Object server) {
        try {
            var getAllLevels = server.getClass().getMethod("getAllLevels");
            var levels = (Iterable<?>) getAllLevels.invoke(server);
            int count = 0;
            for (var level : levels) {
                if (level == null) continue;
                try {
                    var dimensionMethod = level.getClass().getMethod("dimension");
                    var dimension = dimensionMethod.invoke(level);
                    var locationMethod = dimension.getClass().getMethod("location");
                    var location = locationMethod.invoke(dimension);
                    var getPath = location.getClass().getMethod("getPath");
                    var worldName = (String) getPath.invoke(location);

                    var scheduler = new org.veltismc.veltis.server.scheduler.DefaultWorldScheduler(worldName);
                    WORLD_SCHEDULERS.put(worldName, scheduler);
                    count++;
                } catch (Exception e) {
                    // Skip worlds that can't be adapted
                }
            }
            if (count > 0) {
                LOG.log(System.Logger.Level.INFO, "[VeltisMC] Created {0} world schedulers", count);
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Failed to create world schedulers: {0}", e.getMessage());
        }
    }

    public static org.bukkit.plugin.Plugin[] getBukkitPlugins() {
        if (bukkitPluginManager == null) return new org.bukkit.plugin.Plugin[0];
        return bukkitPluginManager.getPlugins();
    }

    public static void registerVeltisCommands(Object server) {
        try {
            var commandsObj = server.getClass().getMethod("getCommands").invoke(server);
            var dispatcher = commandsObj.getClass().getMethod("getDispatcher").invoke(commandsObj);
            var cl = server.getClass().getClassLoader();

            var literalClass = cl.loadClass("com.mojang.brigadier.builder.LiteralArgumentBuilder");
            var literalMethod = literalClass.getMethod("literal", String.class);
            var abClass = cl.loadClass("com.mojang.brigadier.builder.ArgumentBuilder");
            var thenMethod = abClass.getMethod("then", abClass);
            var cmdClass = cl.loadClass("com.mojang.brigadier.Command");
            var executesMethod = abClass.getMethod("executes", cmdClass);
            var registerMethod = dispatcher.getClass().getMethod("register", literalClass);

            var helpMsg = "VeltisMC commands: /veltis version, /veltis plugins, /veltis help";
            var pluginsMsg = buildPluginsList();

            var commandProxy = (cmdClass.isInterface()
                ? Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                    if (m.getName().equals("run")) { sendSuccess(a[0], helpMsg); return 1; }
                    if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (m.getName().equals("equals")) return p == a[0];
                    if (m.getName().equals("toString")) return "veltismc-command";
                    return null;
                })
                : null);

            var veltisRoot = literalMethod.invoke(null, "veltis");
            var versionLeaf = literalMethod.invoke(null, "version");
            var pluginsLeaf = literalMethod.invoke(null, "plugins");

            var versionCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { sendSuccess(a[0], "VeltisMC 1.0.0 (MC 26.2)"); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "version-command";
                return null;
            });
            var pluginsCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { sendSuccess(a[0], pluginsMsg); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "plugins-command";
                return null;
            });

            // Build diagnose subcommands
            var diagnoseLeaf = literalMethod.invoke(null, "diagnose");
            var diagMainCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseMain(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose";
                return null;
            });
            var playerDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnosePlayer(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-player";
                return null;
            });
            var worldDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseWorld(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-world";
                return null;
            });
            var teleportDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseTeleport(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-teleport";
                return null;
            });
            var entitiesDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseEntities(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-entities";
                return null;
            });
            var commandsDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseCommands(a[0], a[0], server); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-commands";
                return null;
            });
            var schedulerDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseScheduler(a[0], a[0], server); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-scheduler";
                return null;
            });
            var pluginsDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnosePlugins(a[0], a[0], server); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-plugins";
                return null;
            });
            var registriesDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseRegistries(a[0], a[0], server); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-registries";
                return null;
            });
            var sparkDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseSpark(a[0], a[0], server); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-spark";
                return null;
            });
            var moonriseDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) { VeltisDiagnoseCommands.diagnoseMoonrise(a[0], a[0]); return 1; }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-moonrise";
                return null;
            });

            // diagnose -> player/world/teleport/entities/commands/scheduler/plugins/registries/spark/moonrise/all
            Object diagnoseNode = executesMethod.invoke(diagnoseLeaf, diagMainCmd);
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "player"), playerDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "world"), worldDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "teleport"), teleportDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "entities"), entitiesDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "commands"), commandsDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "scheduler"), schedulerDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "plugins"), pluginsDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "registries"), registriesDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "spark"), sparkDiagCmd));
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "moonrise"), moonriseDiagCmd));
            // all subcommand runs the complete battery
            var allDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) {
                    VeltisDiagnoseCommands.diagnoseMain(a[0], a[0]);
                    VeltisDiagnoseCommands.diagnoseScheduler(a[0], a[0], server);
                    VeltisDiagnoseCommands.diagnosePlayer(a[0], a[0]);
                    VeltisDiagnoseCommands.diagnoseWorld(a[0], a[0]);
                    VeltisDiagnoseCommands.diagnoseTeleport(a[0], a[0]);
                    VeltisDiagnoseCommands.diagnoseEntities(a[0], a[0]);
                    VeltisDiagnoseCommands.diagnoseCommands(a[0], a[0], server);
                    VeltisDiagnoseCommands.diagnosePlugins(a[0], a[0], server);
                    VeltisDiagnoseCommands.diagnoseRegistries(a[0], a[0], server);
                    VeltisDiagnoseCommands.diagnoseSpark(a[0], a[0], server);
                    VeltisDiagnoseCommands.diagnoseMoonrise(a[0], a[0]);
                    return 1;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-all";
                return null;
            });
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "all"), allDiagCmd));

            // diagnosereport subcommand (console-only, prints to stdout)
            var reportDiagCmd = Proxy.newProxyInstance(cl, new Class<?>[]{cmdClass}, (p, m, a) -> {
                if (m.getName().equals("run")) {
                    VeltisDiagnoseCommands.printDiagnoseReport();
                    return 1;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "diagnose-report";
                return null;
            });
            diagnoseNode = thenMethod.invoke(diagnoseNode, executesMethod.invoke(literalMethod.invoke(null, "report"), reportDiagCmd));

            // veltis -> diagnose + version + plugins + help
            Object veltisFinal = thenMethod.invoke(veltisRoot, diagnoseNode);
            veltisFinal = thenMethod.invoke(veltisFinal, executesMethod.invoke(versionLeaf, versionCmd));
            veltisFinal = thenMethod.invoke(veltisFinal, executesMethod.invoke(pluginsLeaf, pluginsCmd));
            veltisFinal = thenMethod.invoke(veltisFinal, executesMethod.invoke(literalMethod.invoke(null, "help"), commandProxy));
            registerMethod.invoke(dispatcher, veltisFinal);

            var pluginsRoot = literalMethod.invoke(null, "plugins");
            registerMethod.invoke(dispatcher,
                executesMethod.invoke(pluginsRoot, pluginsCmd));

            LOG.log(System.Logger.Level.INFO, "[VeltisMC] Commands registered: /veltis, /plugins, /veltis diagnose");
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Command registration failed: {0}", e.getMessage());
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Exception", e);
        }
    }

    private static String buildPluginsList() {
        if (bukkitPluginManager == null) return "Plugins: none";
        var all = bukkitPluginManager.getPlugins();
        if (all.length == 0) return "Plugins: none";

        var sb = new StringBuilder();
        sb.append("§ePlugins (§f").append(all.length).append("§e):");
        for (var plugin : all) {
            sb.append("\n");
            if (plugin.isEnabled()) {
                sb.append("§a");
            } else {
                sb.append("§7");
            }
            sb.append(plugin.getName());
            var meta = plugin.getPluginMeta();
            if (meta != null) {
                sb.append(" §7v").append(meta.getVersion());
            }
        }
        return sb.toString();
    }



    /**
     * Resolves a Brigadier CommandSourceStack into a Bukkit CommandSender.
     * For player sources, returns a Player proxy that sends messages as
     * real Minecraft chat packets (not System.out).
     */
    private static Object resolveBukkitSender(Object mcSource, Class<?> senderClass, Class<?> playerClass,
                                               Class<?> componentClass, java.lang.reflect.Method componentLiteral, ClassLoader cl) {
        if (mcSource == null) {
            LOG.log(System.Logger.Level.DEBUG, "resolveBukkitSender: mcSource is null");
            return null;
        }
        // Try player FIRST — ensures sendMessage() goes through our proxy
        // which sends real chat packets via sendSystemMessage
        var mcPlayer = tryGetSourcePlayer(mcSource);
        if (mcPlayer != null) {
            var proxy = createPlayerSenderProxy(mcPlayer, senderClass, playerClass, componentClass, componentLiteral, cl);
            if (proxy != null) return proxy;
            LOG.log(System.Logger.Level.DEBUG, "resolveBukkitSender: createPlayerSenderProxy returned null for player, falling back");
        } else {
            LOG.log(System.Logger.Level.DEBUG, "resolveBukkitSender: tryGetSourcePlayer returned null");
        }

        // Fallback to getBukkitSender (Paper API) if no player
        try {
            var getBukkitSender = mcSource.getClass().getMethod("getBukkitSender");
            var result = getBukkitSender.invoke(mcSource);
            if (result != null && senderClass.isInstance(result)) return result;
        } catch (NoSuchMethodException e) {
            LOG.log(System.Logger.Level.DEBUG, "resolveBukkitSender: no getBukkitSender on CommandSourceStack");
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "resolveBukkitSender: getBukkitSender failed: {0}", e.getMessage());
        }

        // Last resort: look for a getBukkitSender on the entity
        try {
            for (var methodName : new String[]{"getPlayer", "getEntity", "player"}) {
                try {
                    var entityMethod = mcSource.getClass().getMethod(methodName);
                    var entity = entityMethod.invoke(mcSource);
                    if (entity == null) continue;
                    // Check for Optional
                    if (entity instanceof java.util.Optional<?> opt) {
                        if (opt.isEmpty()) continue;
                        entity = opt.get();
                    }
                    try {
                        var getBukkitSender = entity.getClass().getMethod("getBukkitSender");
                        var result = getBukkitSender.invoke(entity);
                        if (result != null && senderClass.isInstance(result)) return result;
                    } catch (NoSuchMethodException ignored) {}
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "resolveBukkitSender: entity fallback failed: {0}", e.getMessage());
        }

        LOG.log(System.Logger.Level.WARNING, "resolveBukkitSender: no sender found, returning console fallback");
        return null;
    }

    /**
     * Creates a VeltisPlayerSender backed by a Minecraft ServerPlayer.
     */
    private static Object createPlayerSenderProxy(Object mcPlayer, Class<?> senderClass, Class<?> playerClass,
                                                   Class<?> componentClass, java.lang.reflect.Method componentLiteral, ClassLoader cl) {
        try {
            var uuid = extractUuid(mcPlayer);
            var name = extractName(mcPlayer);
            return new VeltisPlayerSender(mcPlayer, uuid, name, (Server) bukkitServer);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "createPlayerSenderProxy failed: {0}", e.getMessage());
            return null;
        }
    }

    private static UUID extractUuid(Object mcPlayer) {
        try {
            var getUuid = mcPlayer.getClass().getMethod("getUUID");
            return (UUID) getUuid.invoke(mcPlayer);
        } catch (Exception e) {
            try {
                var getGameProfile = mcPlayer.getClass().getMethod("getGameProfile");
                var profile = getGameProfile.invoke(mcPlayer);
                return (UUID) profile.getClass().getMethod("getId").invoke(profile);
            } catch (Exception e2) {
                return java.util.UUID.randomUUID();
            }
        }
    }

    private static String extractName(Object mcPlayer) {
        try {
            var getName = mcPlayer.getClass().getMethod("getName");
            var name = getName.invoke(mcPlayer);
            return name != null ? String.valueOf(name) : "Player";
        } catch (Exception e) {
            return "Player";
        }
    }

    private static boolean isOpForSource(Object mcSource) {
        try {
            var getPermissionLevel = mcSource.getClass().getMethod("getPermissionLevel");
            return (int) getPermissionLevel.invoke(mcSource) >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object consoleSenderForBrig() {
        try {
            return createConsoleSender();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendSuccess(Object context, String message) {
        try {
            var source = context.getClass().getMethod("getSource").invoke(context);
            var componentClass = source.getClass().getClassLoader()
                .loadClass("net.minecraft.network.chat.Component");
            var literalMethod = componentClass.getMethod("literal", String.class);
            var sendSuccessMethod = source.getClass().getMethod("sendSuccess",
                java.util.function.Supplier.class, boolean.class);
            var supplier = (java.util.function.Supplier<Object>) () -> {
                try { return literalMethod.invoke(null, message); }
                catch (Exception e) { return null; }
            };
            sendSuccessMethod.invoke(source, supplier, false);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] sendSuccess failed: {0}", e.getMessage());
        }
    }

    private static int invokeServerInt(Object server, String method, int def) {
        try { return (int) server.getClass().getMethod(method).invoke(server); }
        catch (Exception e) { return def; }
    }

    private static String invokeServerString(Object server, String method, String def) {
        try { return (String) server.getClass().getMethod(method).invoke(server); }
        catch (Exception e) { return def; }
    }

    private static boolean invokeServerBool(Object server, String method, boolean def) {
        try { return (boolean) server.getClass().getMethod(method).invoke(server); }
        catch (Exception e) { return def; }
    }

    public static org.veltismc.veltis.server.scheduler.TaskScheduler scheduler() { return taskScheduler; }
    public static Server bukkitServer() { return bukkitServer; }

    /**
     * Returns the world scheduler for the given world name, or null.
     */
    public static Object getWorldScheduler(String worldName) {
        return worldName != null ? WORLD_SCHEDULERS.get(worldName) : null;
    }

    /**
     * Tries to extract a Minecraft ServerPlayer from a CommandSourceStack,
     * probing multiple accessor names across different mapping conventions.
     * Handles both direct return types and Optional&lt;ServerPlayer&gt;.
     * Falls back to reading the {@code source} or {@code entity} field
     * directly via reflection when method probes fail.
     * Does NOT check for CraftBukkit's getBukkitEntity() — that method only
     * exists on Paper/CraftBukkit, not on Vanilla Minecraft.
     */
    private static Object tryGetSourcePlayer(Object mcSource) {
        if (mcSource == null) return null;

        LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: mcSource class = {0}", mcSource.getClass().getName());

        // ── Phase 1: Method probes ──
        for (var methodName : new String[]{"getPlayer", "getBukkitSender", "getEntity", "player"}) {
            try {
                var method = mcSource.getClass().getMethod(methodName);
                var result = method.invoke(mcSource);
                if (result == null) continue;
                // Unwrap Optional — CommandSourceStack.getPlayer() may return Optional in some versions
                if (result instanceof java.util.Optional<?> opt) {
                    if (opt.isEmpty()) continue;
                    result = opt.get();
                    if (result == null) continue;
                }
                // If we got a Bukkit CommandSender directly, look up the player from server
                if (result.getClass().getName().startsWith("org.bukkit.")) {
                    LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: found Bukkit sender via {0}()", methodName);
                    return tryGetPlayerFromBukkitSender(result);
                }
                // Verify it's a player-like entity by checking for UUID method
                try {
                    result.getClass().getMethod("getUUID");
                    LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: found player via {0}()", methodName);
                    return result;
                } catch (NoSuchMethodException e) {
                    try {
                        result.getClass().getMethod("getScoreboardName");
                        LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: found player via {0}() (scoreboard name)", methodName);
                        return result;
                    } catch (NoSuchMethodException e2) {
                        // Not a player entity, skip
                    }
                }
            } catch (NoSuchMethodException e) {
                LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: no {0}() on {1}", methodName, mcSource.getClass().getSimpleName());
            } catch (java.lang.reflect.InvocationTargetException e) {
                LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: {0}() threw: {1}", methodName, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (IllegalAccessException e) {
                LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: {0}() inaccessible", methodName);
            }
        }

        // ── Phase 2: Field access (source/entity) ──
        LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: trying field access on {0}", mcSource.getClass().getSimpleName());
        for (var fieldName : new String[]{"source", "entity"}) {
            try {
                var field = mcSource.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                var result = field.get(mcSource);
                if (result == null) continue;
                try {
                    result.getClass().getMethod("getUUID");
                    LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: found player via field {0}", fieldName);
                    return result;
                } catch (NoSuchMethodException e) {
                    try {
                        result.getClass().getMethod("getScoreboardName");
                        LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: found player via field {0} (scoreboard name)", fieldName);
                        return result;
                    } catch (NoSuchMethodException e2) {
                        LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: field {0} value is not a player entity ({1})",
                            fieldName, result.getClass().getName());
                    }
                }
            } catch (NoSuchFieldException e) {
                LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: no field {0} on {1}", fieldName, mcSource.getClass().getSimpleName());
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "tryGetSourcePlayer: field {0} failed: {1}", fieldName, e.getMessage());
            }
        }

        LOG.log(System.Logger.Level.WARNING, "tryGetSourcePlayer: no player found in {0}", mcSource.getClass().getName());
        return null;
    }

    /**
     * Tries to extract a Minecraft ServerPlayer from a Bukkit CommandSender
     * by looking up the server's player list. This handles the case where
     * getBukkitSender() returns a Bukkit wrapper directly.
     */
    private static Object tryGetPlayerFromBukkitSender(Object bukkitSender) {
        try {
            var getUniqueId = bukkitSender.getClass().getMethod("getUniqueId");
            var uuid = (java.util.UUID) getUniqueId.invoke(bukkitSender);
            if (uuid != null && MINECRAFT_SERVER != null) {
                var playerList = MINECRAFT_SERVER.getClass().getMethod("getPlayerList").invoke(MINECRAFT_SERVER);
                var getPlayer = playerList.getClass().getMethod("getPlayer", java.util.UUID.class);
                return getPlayer.invoke(playerList, uuid);
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "tryGetPlayerFromBukkitSender: failed: {0}", e.getMessage());
        }
        return null;
    }

    private static Path detectHomeDirectory(String[] args) {
        var customHome = System.getProperty("veltismc.home");
        if (customHome != null && !customHome.isBlank()) return Path.of(customHome);
        for (int i = 0; i < args.length - 1; i++) {
            if ("--home".equals(args[i])) return Path.of(args[i + 1]);
        }
        return Path.of(System.getProperty("user.dir"));
    }

    private static void ensureDirectories(Path root) {
        try {
            java.nio.file.Files.createDirectories(root.resolve("plugins"));
            java.nio.file.Files.createDirectories(root.resolve("logs"));
            java.nio.file.Files.createDirectories(root.resolve("config"));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "[VeltisMC] Failed to create directories: {0}", e.getMessage());
        }
    }
}
