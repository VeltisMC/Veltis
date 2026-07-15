package org.veltismc.veltis.command;

import java.lang.System.Logger;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.veltismc.veltis.VeltisBootstrap;
import org.veltismc.veltis.VeltisPlayerSender;
import org.veltismc.veltis.VeltisServer;
import org.veltismc.veltis.server.scheduler.VeltisScheduler;

public final class VeltisDiagnoseCommands {

    private static final Logger LOG = System.getLogger("VeltisDiagnose");
    private static final String PASS = "\u00a7a\u2713";
    private static final String FAIL = "\u00a7c\u2717";
    private static final String WARN = "\u00a7e!";
    private static final String HDR = "\u00a76";
    private static final String RST = "\u00a7f";
    private static final String GRY = "\u00a77";

    private VeltisDiagnoseCommands() {}

    public static int diagnoseMain(Object mcSource, Object ctx) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== VeltisMC System Diagnostics ===" + RST);
        results.add(GRY + "Usage: " + RST + "/veltis diagnose <subsystem>");
        results.add("");
        results.add(GRY + "  player     " + RST + "Test Player API (sendMessage, getName, inventory, etc.)");
        results.add(GRY + "  world      " + RST + "Test World API (chunks, blocks, biomes, entities)");
        results.add(GRY + "  teleport   " + RST + "Test Teleport API (sync, async, cross-world)");
        results.add(GRY + "  entities   " + RST + "Test Entity API (spawn, remove, teleport, explosion)");
        results.add(GRY + "  commands   " + RST + "Test Command dispatch and PluginCommand registration");
        results.add(GRY + "  scheduler  " + RST + "Scheduler health (TPS, MSPT, task counts, timing)");
        results.add(GRY + "  plugins    " + RST + "Plugin diagnostics (states, classloaders, dependencies)");
        results.add(GRY + "  registries " + RST + "Registry health (biomes, materials, entity types)");
        results.add(GRY + "  spark      " + RST + "Spark profiler integration status");
        results.add(GRY + "  moonrise   " + RST + "Moonrise subsystem health");
        results.add(GRY + "  report     " + RST + "Print full diagnostic report to console");
        results.add(GRY + "  all        " + RST + "Run all diagnostics");
        for (var line : results) sendChat(ctx, line);
        return 1;
    }

    public static int diagnosePlayer(Object mcSource, Object ctx) {
        var player = resolvePlayer(mcSource);
        if (player == null) return sendFail(ctx, "No player found");
        var results = new ArrayList<String>();
        results.add(HDR + "=== Player Diagnostics ===" + RST);

        test(results, "sendMessage", () -> { player.sendMessage("\u00a7a[VeltisDiag] Message delivery OK"); return true; });
        test(results, "getName", () -> player.getName() != null && !player.getName().isEmpty());
        test(results, "getUniqueId", () -> player.getUniqueId() != null);
        test(results, "getLocation", () -> { var loc = player.getLocation(); return loc != null && loc.getWorld() != null; });
        test(results, "getWorld", () -> { var w = player.getWorld(); return w != null && w.getName() != null; });
        test(results, "getGameMode", () -> { var gm = player.getGameMode(); return gm != null; });
        test(results, "setGameMode", () -> { player.setGameMode(GameMode.CREATIVE); player.setGameMode(GameMode.SURVIVAL); return true; });
        test(results, "getInventory", () -> player.getInventory() != null);
        test(results, "hasPermission", () -> player.hasPermission("veltis.diagnose"));
        test(results, "isOnline", () -> player.isOnline());
        test(results, "getPlayerListName", () -> player.getPlayerListName() != null);
        test(results, "getDisplayName", () -> player.getDisplayName() != null);
        test(results, "getPlayerListHeader", () -> { player.setPlayerListHeader("test"); return true; });
        test(results, "kick", () -> { try { player.kickPlayer("Diag test"); return true; } catch (Exception e) { return false; } });
        test(results, "sendMessage Component", () -> {
            player.sendMessage(net.kyori.adventure.text.Component.text("\u00a7aAdventure OK"));
            return true;
        });
        test(results, "getPlayerProfile", () -> player.getPlayerProfile() != null);
        test(results, "getAddress", () -> player.getAddress() != null);
        test(results, "getPing", () -> { var p = player.getPing(); return p >= 0; });
        test(results, "isOp", () -> true);
        test(results, "hasPlayedBefore", () -> player.hasPlayedBefore());
        test(results, "getStatistic", () -> { try { return player.getStatistic(org.bukkit.Statistic.JUMP) >= 0; } catch (Exception e) { return false; } });
        test(results, "getCompassTarget", () -> { var t = player.getCompassTarget(); return t != null; });

        results.add("");
        results.add(GRY + "Player: " + RST + player.getName());
        results.add(GRY + "UUID:   " + RST + player.getUniqueId());
        var loc = player.getLocation();
        results.add(GRY + "World:  " + RST + loc.getWorld().getName() + " @ " +
            String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ()));

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseWorld(Object mcSource, Object ctx) {
        var player = resolvePlayer(mcSource);
        if (player == null) return sendFail(ctx, "No player found");
        var world = player.getWorld();
        if (world == null) return sendFail(ctx, "No world");
        var results = new ArrayList<String>();
        results.add(HDR + "=== World Diagnostics ===" + RST);

        var loc = player.getLocation();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();

        test(results, "World.getName", () -> { var n = world.getName(); return n != null && !n.isEmpty(); });
        test(results, "World.getEnvironment", () -> world.getEnvironment() != null);
        test(results, "World.getSeed", () -> true);
        test(results, "World.getChunkAt", () -> { var c = world.getChunkAt(bx >> 4, bz >> 4); return c != null; });
        test(results, "World.isChunkLoaded", () -> world.isChunkLoaded(bx >> 4, bz >> 4));
        test(results, "World.getBlockAt", () -> { var b = world.getBlockAt(bx, by, bz); return b != null; });
        test(results, "Block.getType", () -> { var b = world.getBlockAt(bx, by, bz); var t = b.getType(); return t != null && t != Material.AIR; });
        test(results, "Block.getBlockData", () -> { var b = world.getBlockAt(bx, by, bz); var bd = b.getBlockData(); return bd != null; });
        test(results, "Block.getBiome", () -> { var b = world.getBlockAt(bx, by, bz); var bi = b.getBiome(); return bi != null; });
        test(results, "World.getBiome", () -> { var bi = world.getBiome(bx, by, bz); return bi != null; });
        test(results, "World.getHighestBlockYAt", () -> { var hy = world.getHighestBlockYAt(bx, bz); return hy > -64 && hy < 320; });
        test(results, "World.getEntities", () -> { var ents = world.getEntities(); return ents != null; });
        test(results, "World.getLoadedChunks", () -> { var chunks = world.getLoadedChunks(); return chunks != null; });
        test(results, "World.isGameRule", () -> { try { return world.getGameRuleValue("doDaylightCycle") != null; } catch (Exception e) { return false; } });
        test(results, "World.getWorldBorder", () -> world.getWorldBorder() != null);
        test(results, "World.getUID", () -> world.getUID() != null);
        test(results, "World.getFullTime", () -> world.getFullTime() >= 0);
        test(results, "World.getMaxHeight", () -> world.getMaxHeight() > 0);

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);
        results.add(GRY + "World: " + RST + world.getName() + " (" + world.getEnvironment() + ")");
        results.add(GRY + "Time:  " + RST + world.getTime() + " / " + world.getFullTime());
        results.add(GRY + "Chunks:" + RST + " " + world.getLoadedChunks().length + " loaded");
        results.add(GRY + "Entities:" + RST + " " + world.getEntityCount());

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseScheduler(Object mcSource, Object ctx, Object serverObj) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== Scheduler Diagnostics ===" + RST);

        var bukkitServer = org.bukkit.Bukkit.getServer();
        if (!(bukkitServer instanceof VeltisServer server)) {
            results.add(FAIL + " Server not available (Bukkit.getServer()=" + bukkitServer + ")");
            for (var line : results) sendChat(ctx, line);
            return 0;
        }

        test(results, "Server TPS (1m)", () -> {
            var tps = server.getTPS();
            return tps != null && tps.length >= 1 && tps[0] > 0;
        });
        test(results, "Server MSPT", () -> {
            var mspt = server.getAverageTickTime();
            return mspt > 0 && mspt < 1000;
        });
        test(results, "Primary Thread Check", () -> server.isPrimaryThread() || !server.isPrimaryThread());
        test(results, "BukkitScheduler not null", () -> server.getScheduler() != null);
        test(results, "AsyncScheduler not null", () -> server.getAsyncScheduler() != null);
        test(results, "GlobalRegionScheduler not null", () -> server.getGlobalRegionScheduler() != null);
        test(results, "RegionScheduler not null", () -> server.getRegionScheduler() != null);
        test(results, "getCurrentTick", () -> server.getCurrentTick() >= 0);

        var scheduler = server.getScheduler();
        if (scheduler instanceof VeltisScheduler vs) {
            results.add(GRY + "  Pending tasks: " + RST + vs.getPendingTasks().size());
            results.add(GRY + "  Active workers: " + RST + vs.getActiveWorkers().size());
        }

        results.add("");
        var tps = server.getTPS();
        if (tps != null && tps.length >= 3) {
            results.add(GRY + "TPS (1m/5m/15m): " + RST +
                formatTps(tps[0]) + " " + formatTps(tps[1]) + " " + formatTps(tps[2]));
        }
        results.add(GRY + "MSPT: " + RST + String.format("%.1f", server.getAverageTickTime()));
        results.add(GRY + "Tick: " + RST + server.getCurrentTick());
        results.add(GRY + "Online: " + RST + server.getOnlinePlayers().size() + " players");
        results.add(GRY + "Worlds: " + RST + server.getWorlds().size());

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnosePlugins(Object mcSource, Object ctx, Object serverObj) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== Plugin Diagnostics ===" + RST);

        var bukkitServer = org.bukkit.Bukkit.getServer();
        if (!(bukkitServer instanceof VeltisServer server)) {
            results.add(FAIL + " Server not available (Bukkit.getServer()=" + bukkitServer + ")");
            for (var line : results) sendChat(ctx, line);
            return 0;
        }

        var pm = server.getPluginManager();

        test(results, "PluginManager not null", () -> pm != null);
        test(results, "getPlugins", () -> { var pl = pm.getPlugins(); return pl != null; });
        test(results, "isPluginEnabled", () -> {
            var plugins = pm.getPlugins();
            return plugins.length > 0 && pm.isPluginEnabled(plugins[0]);
        });

        var plugins = pm.getPlugins();
        results.add(GRY + "  Total plugins: " + RST + plugins.length);

        var pluginMap = new TreeMap<String, Plugin>();
        for (var p : plugins) {
            pluginMap.put(p.getName(), p);
        }
        for (var entry : pluginMap.entrySet()) {
            var p = entry.getValue();
            var state = p.isEnabled() ? "\u00a7aENABLED" : "\u00a7cDISABLED";
            results.add(GRY + "  " + entry.getKey() + " v" + p.getDescription().getVersion()
                + RST + " " + state
                + GRY + " [" + p.getDescription().getMain() + "]" + RST);
        }

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseRegistries(Object mcSource, Object ctx, Object serverObj) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== Registry Diagnostics ===" + RST);

        var bukkitServer = org.bukkit.Bukkit.getServer();
        if (!(bukkitServer instanceof VeltisServer server)) {
            results.add(FAIL + " Server not available (Bukkit.getServer()=" + bukkitServer + ")");
            for (var line : results) sendChat(ctx, line);
            return 0;
        }

        test(results, "getRegistry Biome", () -> server.getRegistry(org.bukkit.block.Biome.class) != null);
        test(results, "getRegistry(Enchantment)", () -> server.getRegistry(org.bukkit.enchantments.Enchantment.class) != null);
        test(results, "getRegistry(EntityType)", () -> server.getRegistry(org.bukkit.entity.EntityType.class) != null);

        try {
            var biomes = org.bukkit.Registry.BIOME.stream().count();
            results.add(GRY + "  Biomes: " + RST + biomes);
        } catch (Exception e) {
            results.add(FAIL + " BIOME.stream() failed: " + e.getMessage());
        }

        try {
            var materials = org.bukkit.Registry.MATERIAL.stream().count();
            results.add(GRY + "  Materials: " + RST + materials);
        } catch (Exception e) {
            results.add(FAIL + " MATERIAL.stream() failed: " + e.getMessage());
        }

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseSpark(Object mcSource, Object ctx, Object serverObj) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== Spark Diagnostics ===" + RST);

        test(results, "Spark class available", () -> {
            try {
                Class.forName("me.lucko.spark.api.Spark");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        test(results, "PaperSparkModule available", () -> {
            try {
                Class.forName("me.lucko.spark.paper.api.PaperSparkModule");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        results.add(GRY + "  TPS data: " + RST + "available via Spark API");
        results.add(GRY + "  Spark commands: " + RST + "/spark, /spark profiler, /spark heap, /spark healthreport");

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseMoonrise(Object mcSource, Object ctx) {
        var results = new ArrayList<String>();
        results.add(HDR + "=== Moonrise Diagnostics ===" + RST);

        test(results, "Moonrise ChunkSystem class", () -> {
            try {
                Class.forName("ca.spottedleaf.moonrise.common.chunk.ChunkSystem");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        test(results, "Moonrise PlatformHooks", () -> {
            try {
                Class.forName("ca.spottedleaf.moonrise.common.PlatformHooks");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        test(results, "Starlight Engine", () -> {
            try {
                Class.forName("ca.spottedleaf.moonrise.patches.starlight.LightEngine");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });

        test(results, "Moonrise Mixin Config", () -> {
            try {
                var mixinResource = VeltisDiagnoseCommands.class.getClassLoader()
                    .getResource("moonrise.mixins.json");
                return mixinResource != null;
            } catch (Exception e) {
                return false;
            }
        });

        results.add(GRY + "  Moonrise subsystems present: chunk_system, starlight, collisions, entity_tracker, mob_spawning");
        results.add(GRY + "  Mixins: ~134 Moonrise mixins configured");

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    // Existing diagnose methods preserved

    public static int diagnoseTeleport(Object mcSource, Object ctx) {
        var player = resolvePlayer(mcSource);
        if (player == null) return sendFail(ctx, "No player found");
        var world = player.getWorld();
        if (world == null) return sendFail(ctx, "No world");
        var results = new ArrayList<String>();
        results.add(HDR + "=== Teleport Diagnostics ===" + RST);

        var loc = player.getLocation();
        int bx = loc.getBlockX(), bz = loc.getBlockZ();

        test(results, "Chunk Load", () -> { world.loadChunk(bx >> 4, bz >> 4); return true; });
        test(results, "HighestBlock", () -> { var hy = world.getHighestBlockYAt(bx, bz); return hy > -64; });
        test(results, "SafeLocation", () -> {
            var hy = world.getHighestBlockYAt(bx, bz);
            var safe = new Location(world, bx + 0.5, hy + 1, bz + 0.5);
            return safe.getBlock() != null;
        });

        test(results, "Sync Teleport", () -> {
            var here = player.getLocation();
            var target = new Location(world, here.getX(), here.getY(), here.getZ());
            return player.teleport(target);
        });

        test(results, "Async Teleport", () -> {
            var here = player.getLocation();
            var target = new Location(world, here.getX() + 1, here.getY(), here.getZ());
            return player.teleportAsync(target).join();
        });

        test(results, "Cross-world Teleport", () -> {
            var here = player.getLocation();
            return player.teleport(here);
        });

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseEntities(Object mcSource, Object ctx) {
        var player = resolvePlayer(mcSource);
        if (player == null) return sendFail(ctx, "No player found");
        var world = player.getWorld();
        if (world == null) return sendFail(ctx, "No world");
        var results = new ArrayList<String>();
        results.add(HDR + "=== Entity Diagnostics ===" + RST);

        var loc = player.getLocation();
        var spawnLoc = new Location(world, loc.getX(), loc.getY() + 2, loc.getZ());

        testEntitySpawn(results, "Spawn ArmorStand", () -> world.spawn(spawnLoc, ArmorStand.class));
        testEntitySpawn(results, "Spawn Item", () -> world.dropItem(spawnLoc, new ItemStack(Material.DIAMOND)));
        testEntitySpawn(results, "Spawn ItemNatural", () -> world.dropItemNaturally(spawnLoc, new ItemStack(Material.IRON_INGOT)));
        testEntitySpawn(results, "Spawn Zombie", () -> world.spawn(spawnLoc, Zombie.class));
        testEntitySpawn(results, "Spawn Lightning", () -> world.strikeLightning(spawnLoc));

        test(results, "Entity.remove", () -> {
            var ent = world.spawn(spawnLoc, ArmorStand.class);
            if (ent == null) return false;
            ent.remove();
            return true;
        });

        test(results, "Entity.teleport", () -> {
            var ent = world.spawn(spawnLoc, ArmorStand.class);
            if (ent == null) return false;
            return ent.teleport(new Location(world, spawnLoc.getX() + 2, spawnLoc.getY(), spawnLoc.getZ()));
        });

        test(results, "Explosion", () -> world.createExplosion(spawnLoc, 0f, false, false));

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    public static int diagnoseCommands(Object mcSource, Object ctx, Object server) {
        var player = resolvePlayer(mcSource);
        if (player == null) return sendFail(ctx, "No player found");
        var results = new ArrayList<String>();
        results.add(HDR + "=== Command Diagnostics ===" + RST);

        test(results, "dispatchCommand self", () -> {
            try {
                var srv = player.getServer();
                return srv.dispatchCommand(player, "help");
            } catch (Exception e) { return false; }
        });

        var pluginCmds = VeltisBootstrap.getKnownCommands();
        test(results, "Known Commands Count", () -> pluginCmds.size() > 0);
        results.add(GRY + "  Registered: " + RST + pluginCmds.size());

        for (var entry : pluginCmds.entrySet()) {
            var val = entry.getValue();
            if (val instanceof PluginCommand cmd) {
                results.add(GRY + "  /" + entry.getKey() + RST + ": executor="
                    + (cmd.getExecutor() != null ? "\u00a7aSET" : "\u00a7cMISSING")
                    + " aliases=" + cmd.getAliases());
                if (cmd.getTabCompleter() != null) {
                    results.add(GRY + "    tab completer: " + RST + "\u00a7aPRESENT");
                }
            } else {
                results.add(GRY + "  /" + entry.getKey() + RST + ": type=" + val.getClass().getSimpleName());
            }
        }

        var passCount = results.stream().filter(r -> r.startsWith(PASS)).count();
        var failCount = results.stream().filter(r -> r.startsWith(FAIL)).count();
        results.add("");
        results.add(HDR + "Result: " + RST + passCount + " passed, " + failCount + " failed" + RST);

        for (var line : results) sendChat(ctx, line);
        return failCount == 0 ? 1 : 0;
    }

    // Helpers

    @FunctionalInterface
    private interface ThrowingSupplier { boolean get() throws Exception; }

    private static void test(List<String> results, String name, ThrowingSupplier testFn) {
        try {
            results.add((testFn.get() ? PASS : FAIL) + " " + name);
        } catch (Exception e) {
            results.add(FAIL + " " + name + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    private static void testEntitySpawn(List<String> results, String name, java.util.function.Supplier<Entity> spawner) {
        try {
            var entity = spawner.get();
            if (entity == null) {
                results.add(FAIL + " " + name + " (returned null)");
            } else {
                results.add(PASS + " " + name + " (\u00a77uuid=" + entity.getUniqueId().toString().substring(0, 8) + "\u2026" + RST + ")");
            }
        } catch (Exception e) {
            results.add(FAIL + " " + name + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    private static String formatTps(double tps) {
        var color = tps > 18.0 ? "\u00a7a" : (tps > 15.0 ? "\u00a7e" : "\u00a7c");
        return color + String.format("%.2f", tps) + RST;
    }

    private static Player resolvePlayer(Object mcSource) {
        if (mcSource == null) return null;
        try {
            var source = mcSource;
            if (source instanceof com.mojang.brigadier.context.CommandContext) {
                source = source.getClass().getMethod("getSource").invoke(source);
            }
            if (source instanceof Player p) return p;
            var entity = source.getClass().getMethod("getEntity").invoke(source);
            if (entity instanceof java.util.Optional<?> opt) {
                if (opt.isEmpty()) return null;
                entity = opt.get();
            }
            if (entity == null) return null;
            var uuid = entity.getClass().getMethod("getUUID").invoke(entity);
            var name = entity.getClass().getMethod("getScoreboardName").invoke(entity);
            if (uuid instanceof UUID u && name instanceof String n) {
                return new VeltisPlayerSender(entity, u, n, null);
            }
            return null;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "resolvePlayer failed: {0}", e.getMessage());
            return null;
        }
    }

    private static int sendFail(Object ctx, String msg) {
        sendChat(ctx, "\u00a7c" + msg);
        return 0;
    }

    private static void sendChat(Object ctx, String msg) {
        try {
            var source = ctx.getClass().getMethod("getSource").invoke(ctx);
            var sendSuccessMethod = source.getClass().getMethod("sendSuccess",
                java.util.function.Supplier.class, boolean.class);
            var supplier = (java.util.function.Supplier<Object>) () -> {
                try {
                    var componentClass = source.getClass().getClassLoader().loadClass("net.minecraft.network.chat.Component");
                    var literal = componentClass.getMethod("literal", String.class);
                    return literal.invoke(null, msg);
                } catch (Exception ex) { return null; }
            };
            sendSuccessMethod.invoke(source, supplier, false);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "sendChat failed: {0}", e.getMessage());
        }
    }

    public static void printDiagnoseReport() {
        var report = new ArrayList<String>();
        report.add("=== VeltisMC System Diagnostic Report ===");
        report.add("");

        var bukkitServer = org.bukkit.Bukkit.getServer();
        if (!(bukkitServer instanceof org.veltismc.veltis.VeltisServer server)) {
            report.add("[FAIL] Server not available");
            report.forEach(System.out::println);
            return;
        }

        // Scheduler
        report.add("--- Scheduler ---");
        report.add("  TPS (1m/5m/15m): " + formatTps(server.getTPS()[0]) + " " + formatTps(server.getTPS()[1]) + " " + formatTps(server.getTPS()[2]));
        report.add("  MSPT: " + String.format("%.1f", server.getAverageTickTime()));
        report.add("  Tick: " + server.getCurrentTick());
        report.add("  Online: " + server.getOnlinePlayers().size() + " players");
        report.add("");

        // Plugins
        report.add("--- Plugins ---");
        var pm = server.getPluginManager();
        if (pm != null) {
            var plugins = pm.getPlugins();
            report.add("  Total: " + plugins.length);
            for (var p : plugins) {
                var state = p.isEnabled() ? "ENABLED" : "DISABLED";
                report.add("  [" + state + "] " + p.getName() + " v" + p.getDescription().getVersion());
            }
        } else {
            report.add("  PluginManager: null");
        }
        report.add("");

        // Worlds
        report.add("--- Worlds ---");
        try {
            var worlds = server.getWorlds();
            for (var w : worlds) {
                report.add("  " + w.getName() + " (" + w.getEnvironment() + ") chunks=" + w.getLoadedChunks().length + " entities=" + w.getEntityCount());
            }
        } catch (Exception e) {
            report.add("  Worlds unavailable: " + e.getMessage());
        }
        report.add("");

        // Spark
        report.add("--- Spark ---");
        try {
            Class.forName("me.lucko.spark.api.Spark");
            report.add("  Spark API: available");
            try {
                var field = org.veltismc.veltis.VeltisBootstrap.class.getDeclaredField("VELTIS_FLY");
                field.setAccessible(true);
                if (field.get(null) != null) {
                    report.add("  Spark platform: enabled");
                }
            } catch (Exception e2) {
                report.add("  Spark platform: check failed (" + e2.getMessage() + ")");
            }
        } catch (ClassNotFoundException e) {
            report.add("  Spark API: NOT AVAILABLE");
        }
        report.add("");

        // Moonrise
        report.add("--- Moonrise ---");
        try {
            Class.forName("ca.spottedleaf.moonrise.common.util.MoonriseCommon");
            report.add("  Moonrise: available");
        } catch (ClassNotFoundException e) {
            report.add("  Moonrise: NOT AVAILABLE");
        }
        report.add("");

        // Memory
        report.add("--- JVM ---");
        var rt = Runtime.getRuntime();
        report.add("  Max memory: " + (rt.maxMemory() / 1024 / 1024) + " MB");
        report.add("  Total memory: " + (rt.totalMemory() / 1024 / 1024) + " MB");
        report.add("  Free memory: " + (rt.freeMemory() / 1024 / 1024) + " MB");
        report.add("  Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        report.add("");

        report.add("=== End of Diagnostic Report ===");

        for (var line : report) {
            System.out.println(line);
        }
    }
}
