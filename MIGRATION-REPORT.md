# VeltisMC De-Paper / De-Bukkit Migration Report

Final state: VeltisMC is a **bare NMS server**. Every vendored Bukkit, Spigot, and Paper class has
been removed from the source tree; the runtime no longer implements any plugin API. The server
boots the patched vanilla `DedicatedServer`, with no plugin support whatsoever.

## Summary

- **Verification**: `./gradlew build buildVeltisMC` passes (all modules compile, tests run,
  `veltismc.jar` packages). The packaged jar contains **zero** `io.papermc.*`,
  `com.destroystokyo.*`, `org.bukkit.*`, `org.spigotmc.*`, or `co.aikar.*` classes.
- **Net change**: ~1,900 files deleted across two phases (the entire vendored
  `org.bukkit` tree alone was 1,273 files).
- The MC 26.2 pipeline (download / extract / widen / libraries) ran cleanly from scratch
  (`ver/` was regenerated after being cleaned).

## Phase 1 — Paper implementation migration (completed earlier)

Moved the runtime's Paper implementation classes into `org.veltismc.veltis.*`:

| Old | New |
|---|---|
| `io.papermc.paper.plugin.manager.PaperPluginManagerImpl` | `org.veltismc.veltis.plugin.manager.VeltisPluginManager` |
| `...PaperPluginInstanceManager` | `...VeltisPluginInstanceManager` |
| `...PaperEventManager` | `...VeltisEventManager` |
| `...PaperPermissionManager` | `...VeltisPermissionManager` |
| `...NormalPaperPermissionManager` | `...NormalPermissionManager` |
| `...StupidSPMPermissionManagerWrapper` | `...SimplePluginManagerPermissionWrapper` |
| `...entrypoint.classloader.group.PaperPluginClassLoaderStorage` | `org.veltismc.veltis.plugin.classloader.VeltisPluginClassLoaderStorage` |
| `...entrypoint.classloader.group.{Global,Locking,SimpleList,Spigot,Static,Singleton,DependencyBased}PluginClassLoaderGroup` | `org.veltismc.veltis.plugin.classloader.*` |
| `...command.brigadier.bukkit.BukkitBrigForwardingMap` | `org.veltismc.veltis.command.brigadier.bukkit.VeltisBrigForwardingMap` |
| `...plugin.loader.PaperClasspathBuilder` | `org.veltismc.veltis.plugin.compat.VeltisClasspathBuilder` |
| `...plugin.loader.library.PaperLibraryStore` | `org.veltismc.veltis.plugin.compat.VeltisLibraryStore` |
| `io.papermc.paper.SparksFly` / `SparksFlyHolder` | `org.veltismc.veltis.spark.SparksFly` / `SparksFlyHolder` |
| `io.papermc.paper.util.MCUtil` | `org.veltismc.veltis.util.MCUtil` |

Also renamed `VeltisPaperConfig` -> `VeltisConfig`, `VeltisPaperScheduledTask` ->
`VeltisScheduledTaskImpl`; deleted 8 duplicate vendored API copies from the runtime module;
repointed the `PaperClassLoaderStorage` service file. Phase 1 was verified green before Phase 2
began.

## Phase 2 — Full Paper removal

Per user direction ("remove io.papermc.paper"), the entire vendored Paper surface was deleted:

- **Server module**: `server/src/main/java/io/` (~441 files), `server/src/main/java/com/destroystokyo/`
  (~114 files), `org.veltismc/veltis/spark/`, `org/veltismc/veltis/util/MCUtil.java`, the Moonrise
  `MinecraftServerSparkMixin`, and services `InternalAPIBridge`, `LifecycleEventTypeProvider`,
  `RegistryAccess`.
- **Runtime module**: `runtime/src/main/java/io/` (incl. the `PaperPluginClassLoader` stub),
  `plugin/lifecycle/`, `plugin/classloader/`, `registry/`, `VeltisClassLoaderStorage`,
  `VeltisScheduledTaskImpl`, `VeltisAsyncScheduler`, `VeltisRegionScheduler`,
  `VeltisGlobalRegionScheduler`, `VeltisEntityScheduler`, `VeltisServerBuildInfo`,
  `VeltisInternalAPIBridge`, `VeltisCommandsRegistrar`, `VeltisCommandSourceStack`, and services
  `PaperClassLoaderStorage`, `ServerBuildInfo`.

## Phase 3 — Full Bukkit removal (bare NMS server)

Per user direction ("remove org.bukkit too — no plugin API"), everything remaining of the plugin
ecosystem was deleted:

- `server/src/main/java/org/bukkit/` (**1,273 files** — the entire vendored API),
  `server/src/main/java/org/spigotmc/` (4 files), `server/src/main/java/co/` (aikar timings, 19 files).
- Runtime: `org/bukkit/craftbukkit/command/CraftCommandMap.java` and 30 Bukkit-implementing classes:
  `VeltisServer`, `VeltisBanList`, `VeltisConsoleSender`, `VeltisMessenger`, `VeltisObjective`,
  `VeltisOfflinePlayer`, `VeltisPersistentDataContainer`, `VeltisPlayerSender`, `VeltisScore`,
  `VeltisScoreboard`, `VeltisScoreboardManager`, `VeltisTeam`, `VeltisWorldProxy`,
  `VeltisInventoryProxy`, `VeltisItemStack(Bridge)`, `VeltisContainerAdapter`, `VeltisEntityProxy`,
  `VeltisPlayerInventory`, `VeltisDiagnoseCommands`, `VeltisBrigForwardingMap`,
  `VeltisCommandMap`, `plugin/manager/*` (6 files), `VeltisScheduler`.
- Dead Paper-API implementations `VeltisClasspathBuilder` / `VeltisLibraryStore` (dangling
  `io.papermc.paper.plugin.loader.*` imports) — `plugin/compat/` deleted.

## What remains

- **Server module**: `ca.spottedleaf` (Moonrise, 287 files) and the Moonrise mixin bootstrap
  (`Main`, `MixinAgent`, `MixinServiceVanilla`, `MixinSetup`, `VeltisBlackboard`,
  `VeltisContainerHandle`, `moonrise/VeltisPlatformHooks`) were removed in a follow-up cleanup;
  what remains is the NMS entrypoint (`Main`, `api/VeltisAPI` + definitions).
- **Runtime module**: the Veltis server framework that is self-contained — command framework,
  config, data, resource, runtime provisioning/loader/controller, lifecycle, metrics, server
  model, internal scheduler/tick, events, storage, branding — plus a rewritten 35-line
  `VeltisBootstrap` implementing the NMS patch contract (`boot(String[])` +
  `onMinecraftServerCreated(Object)`).
- **NMS patches** (`server/patches/`, `launcher/.../patches/`): untouched; verified zero
  Bukkit/Paper references. The `Wire-VeltisBootstrap-Integration` patch still calls
  `VeltisBootstrap.onMinecraftServerCreated(Object)` reflectively.
- **Pipeline modules** (launcher, builder, patch-engine, build-tools): untouched.

## NMS <-> runtime contract

- `net.minecraft.server.MinecraftServer.SERVER` (added by the branding patch) + the
  `Veltis-Branding-MinecraftServer.patch` brand strings.
- `VeltisBootstrap.onMinecraftServerCreated(Object)` invoked by
  `Wire-VeltisBootstrap-Integration.patch` after the dedicated server finishes booting.

## Dependencies

- Removed: `me.lucko:spark-paper:1.10.152`, `me.lucko:spark-api:0.1-...` (spark profiler),
  the entire Bukkit/Paper API dep block (guava/gson/snakeyaml/joml/fastutil/log4j/slf4j/brigadier/
  bungeecord-chat/adventure stack/maven-resolver/jspecify/checker-qual) from `server/build.gradle.kts`.
- Removed repos: hub.spigotmc.org, repo.papermc.io, repo.lucko.me.
- Kept: Jansi, JetBrains annotations (compileOnly), Minecraft libs via the
  `ver/26.2/libraries` file tree.

## Remaining "papermc" strings (benign)

None. The Moonrise artifact host was removed together with Moonrise itself.

## Feature loss (consequence of the chosen direction)

- No plugin loading (`plugins/` scanning, `plugin.yml`, `paper-plugin.yml`), no plugin commands
  (`/plugins`, `/veltis`, diagnose suite), no events/permissions/schedulers/registries API.
- No spark profiler, no Bukkit-style timings (`co.aikar`).
- Console command handling is vanilla NMS only; branding persists via the NMS patch.

## Follow-up work

- Update the launcher/README/start scripts if they still mention plugin folders or spark flags.
- Decide whether the remaining self-contained runtime framework (config/data/resource/command
  modules) should be wired into the bare server beyond `VeltisBootstrap` (e.g., load
  `veltis.yml` defaults, register Veltis-only commands through NMS brigadier directly).
- `VeltisConfig` still reads Paper-format config file names (`paper-global.yml` etc.);
  migrate to `veltis-*.yml` when convenient.
