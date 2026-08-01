# VeltisMC Paper Migration Report

Full architectural migration of the VeltisMC codebase off Paper-specific implementation code,
classes, utilities, branding, and duplicate vendored sources, while preserving full Bukkit and
Paper plugin compatibility.

## Summary

- **Verification**: `./gradlew build buildVeltisMC` passes (all modules compile, tests run,
  `veltismc.jar` packages). MC 26.2 pipeline (download/extract/widen/libraries) ran cleanly.
- **Net change**: 42 files touched — 30 deleted (moved/renamed), 22 new, 12 modified,
  +46/-3212 lines. The only remaining `io.papermc.paper` sources are the vendored
  Paper **API** (compat surface) and one documented binary-compat stub.

## Classes renamed / relocated (old -> new)

All moved out of `io.papermc.paper.*` into `org.veltismc.veltis.*`:

| Old (module) | New |
|---|---|
| `io.papermc.paper.plugin.manager.PaperPluginManagerImpl` (runtime) | `org.veltismc.veltis.plugin.manager.VeltisPluginManager` |
| `...PaperPluginInstanceManager` (runtime) | `...VeltisPluginInstanceManager` |
| `...PaperEventManager` (runtime) | `...VeltisEventManager` |
| `...PaperPermissionManager` (runtime) | `...VeltisPermissionManager` |
| `...NormalPaperPermissionManager` (runtime) | `...NormalPermissionManager` |
| `...StupidSPMPermissionManagerWrapper` (runtime) | `...SimplePluginManagerPermissionWrapper` |
| `...plugin.entrypoint.classloader.group.PaperPluginClassLoaderStorage` (runtime) | `org.veltismc.veltis.plugin.classloader.VeltisPluginClassLoaderStorage` |
| `...entrypoint.classloader.group.{Global,Locking,SimpleList,Spigot,Static,Singleton,DependencyBased}PluginClassLoaderGroup` (runtime) | `org.veltismc.veltis.plugin.classloader.*` (same names) |
| `...command.brigadier.bukkit.BukkitBrigForwardingMap` (runtime) | `org.veltismc.veltis.command.brigadier.bukkit.VeltisBrigForwardingMap` |
| `...plugin.loader.PaperClasspathBuilder` (runtime) | `org.veltismc.veltis.plugin.compat.VeltisClasspathBuilder` |
| `...plugin.loader.library.PaperLibraryStore` (runtime) | `org.veltismc.veltis.plugin.compat.VeltisLibraryStore` |
| `io.papermc.paper.SparksFly` (server) | `org.veltismc.veltis.spark.SparksFly` |
| `io.papermc.paper.SparksFlyHolder` (server) | `org.veltismc.veltis.spark.SparksFlyHolder` |
| `io.papermc.paper.util.MCUtil` (server) | `org.veltismc.veltis.util.MCUtil` |
| `org.veltismc.veltis.config.VeltisPaperConfig` (runtime) | `org.veltismc.veltis.config.VeltisConfig` |
| `org.veltismc.veltis.VeltisPaperScheduledTask` (runtime) | `org.veltismc.veltis.VeltisScheduledTaskImpl` |

## Files deleted

- 8 duplicate vendored API copies in the runtime module (stripped versions of classes that the
  `server` module already ships with full javadoc; verified API-supersets before deletion):
  `PluginLoader`, `PluginClasspathBuilder`, `ClassPathLibrary`, `LibraryStore`,
  `LibraryLoadingException`, `JarLibrary`, `MavenLibraryResolver`, `ProviderUtil`.
- 1 dead reflection path removed: `VeltisServer.getDatapackManager()` no longer tries
  `io.papermc.paper.datapack.PaperDatapackManager` (that class never existed in this tree);
  the proxy fallback is now the sole implementation.
- 30 files total deleted as part of the moves above.

## Service registrations updated

- `runtime/src/main/resources/META-INF/services/io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage`
  now points at `org.veltismc.veltis.plugin.classloader.VeltisPluginClassLoaderStorage`.

## Branding removed

- Thread name `"Paper Async Task Handler Thread - %1$d"` -> `"Veltis Async Task Handler Thread - %1$d"` (MCUtil).
- spark info URL `docs.papermc.io/paper/profiling` -> `spark.lucko.me` (SparksFly).
- System property `Paper.DisableClassPrioritization` -> `veltismc.DisableClassPrioritization` (SimpleListPluginClassLoaderGroup).
- Leftover `paper*` variable/method names in Veltis code renamed (`createScheduledTaskHandle`, `veltisStack`, `registryKey`, `"Bundled spark module available"`).
- `veltis.preferSparkPlugin` and `veltismc:veltis` brand were already Veltis-owned — unchanged.

## Dependencies

- No Maven dependency was removed: the project has **no external paper-api dependency** (the API is
  vendored in the `server` module by design).
- `me.lucko:spark-paper:1.10.152` + `me.lucko:spark-api` retained intentionally: they are the spark
  profiler's official Paper-platform integration powering the bundled profiler, resolve from
  repo.papermc.io only (the POM declares no transitive deps, so no paper-api leaks in), and provide
  the `me.lucko.spark.paper.api.*` classes used by `SparksFly`.

## Remaining Paper references (cannot be removed)

| Reference | Reason it stays |
|---|---|
| Vendored Paper API under `server/src/main/java/io/papermc/paper/**` and `com/destroystokyo/paper/**` (~500 files) | Compatibility surface; plugins compile/run against these classes (schedulers, registries, ServerBuildInfo, PlayerProfile, ban lists, commands, lifecycle events, etc.). Interfaces/API-only classes are intentionally retained per task rules. |
| 27 `*Impl` classes inside `server/io/papermc` (BanListTypeImpl, TypedKeyImpl, ReferenceImpl, LifecycleEventTypeProviderImpl, ...) | These are vendored **paper-api** sources (pure Java, no Minecraft/server code; verified) — the API's own implementation layer, referenced by the API itself (e.g. `LifecycleEventTypeProvider.provider()`) and service file `io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventTypeProvider`. |
| `runtime/.../io/papermc/paper/plugin/entrypoint/classloader/PaperPluginClassLoader.java` (9-line stub) | Binary-compat shim required by the bundled spark-paper jar's `PaperClassSourceLookup`; documented in its header as unused by VeltisMC. |
| `VeltisConfig` reading `paper-global.yml` / `paper-world-defaults.yml` / `paper-world.yml` | Legacy Paper config-format compatibility (the file format is named after Paper; servers keep their existing configs). |
| `parsePaperPluginYml`, `getPaperPluginLoader`, `paperPluginLoader` reflection in VeltisPluginInstanceManager | The plugin descriptor format is literally `paper-plugin.yml`; naming describes the format being parsed. |
| `io.papermc.paper.*` imports throughout Veltis runtime code | All are API interfaces/classes implemented or consumed by Veltis (ScheduledTask, PaperClassLoaderStorage, PluginClasspathBuilder, RegistryAccess, lifecycle events, ...). |
| `com.destroystokyo.paper.*` imports (PlayerProfile, MobGoals, TargetBlockInfo, Title, SkinParts, events...) | Vendored Paper API, part of the plugin-facing surface. |
| `me.lucko.spark.paper.api.*` imports in SparksFly | External spark-paper integration library (see Dependencies). |
| `isBrandCompatible` accepting `papermc:paper` in VeltisServerBuildInfo | Explicit brand-compat bridge so Paper-expecting plugins/update checks recognize VeltisMC. |

## Compatibility impact

- Plugin-facing API: unchanged (paper-plugin.yml + plugin.yml loading, libraries via
  `libraries`/`PluginLoader` — now backed by `VeltisClasspathBuilder` which gained the missing
  `getContext()` (PluginProviderContext) implementation, loadbefore/softdepend/provides,
  commands/brigadier, permissions, schedulers, registries, lifecycle events).
- Service-loader contracts (`PaperClassLoaderStorage`, `LifecycleEventTypeProvider`) unchanged
  FQN-wise; only the implementing class moved to `org.veltismc`.
- No public class exposed to plugins changed FQN; every renamed class is a server-side
  implementation (new `VeltisClasspathBuilder` constructor requires a `PluginProviderContext`,
  supplied by `VeltisPluginInstanceManager`).
- spark profiler still bundled and controllable via `veltis.preferSparkPlugin`.

## Follow-up work

- Rename `SparksFly`/`SparksFlyHolder` (kept for recognizability; not Paper-branded) if desired.
- Consider migrating `paper-*.yml` legacy config files to `veltis-*.yml` with automatic fallback.
- The stub `PaperPluginClassLoader` could be dropped if the spark integration is ever replaced.
- `VeltisServerBuildInfo` brand-compat accept-list could be moved to a config file.
