# Contributing to VeltisMC

Thanks for helping with VeltisMC. This project keeps Minecraft changes as patch files under `patches/` and builds a VeltisMC server jar by overlaying compiled patched classes onto the downloaded vanilla server jar.

## Requirements

- JDK 26 available on your `PATH` or through `JAVA_HOME`
- Git
- Internet access on the first build, so Gradle can download dependencies and the Minecraft server/libraries
- Use the Gradle wrapper included in this repository

On Windows, use `gradlew.bat`. On macOS/Linux, use `./gradlew`.

## Project Layout

- `patches/server/` contains patches applied to decompiled Minecraft server sources.
- `patches/api/` is reserved for API patch files.
- `ver/<minecraftVersion>/minecraft-source/` contains the decompiled vanilla sources.
- `ver/<minecraftVersion>/patched-source/` contains the working source tree after patches are applied. Attached as IDE source root for `server` and `runtime` modules (autocomplete/navigation).
- `ver/<minecraftVersion>/classes/` contains compiled patched Minecraft classes.
- `ver/<minecraftVersion>/server.jar` — Mojang-mapped server jar extracted from bundler; used as compile-time dependency.
- `ver/<minecraftVersion>/libraries/` — downloaded Minecraft library jars; required on classpath for compilation.
- `build/veltismc-server.jar` is the patched Minecraft runtime jar.
- `build/distributions/veltismc.jar` is the standalone VeltisMC launcher jar contributors should run.

The default Minecraft version is configured in `gradle.properties` or falls back to `26.2` in `build.gradle.kts`.

## First-Time Setup

Run the pipeline setup tasks:

```powershell
.\gradlew.bat extractServerJar downloadMinecraft downloadLibraries decompileMinecraft generateSourceWorkspace applyPatches
```

On macOS/Linux:

```bash
./gradlew extractServerJar downloadMinecraft downloadLibraries decompileMinecraft generateSourceWorkspace applyPatches
```

This creates:
- `ver/<minecraftVersion>/server.jar` — Mojang-mapped server jar (from `extractServerJar`)
- `ver/<minecraftVersion>/libraries/` — library jars (from `downloadLibraries`)
- `ver/<minecraftVersion>/minecraft-source/` — decompiled sources
- `ver/<minecraftVersion>/patched-source/` — patched source workspace

### Recommended IDE

**IntelliJ IDEA** is the recommended IDE. It has first-class Gradle Kotlin DSL support and the `idea` Gradle plugin for source attachment.

The `server` and `runtime` modules use the Gradle `idea` plugin to attach the patched source for IDE navigation. After running the pipeline tasks, regenerate the IntelliJ project:

```bash
./gradlew idea
```

Then reopen the project in IntelliJ. You will have full autocomplete, navigation, and source-level documentation for decompiled Minecraft classes.

**Other IDEs** (VS Code, Eclipse, NetBeans) can build and run the project but will **not** resolve decompiled Minecraft source for navigation. The class files from `server.jar` are on the compile classpath, so type names resolve, but you will see decompiled bytecode rather than the source. There are no plans to add IDE-specific source root support beyond IntelliJ's `idea` plugin.

### Module Dependencies

The `server` and `runtime` modules depend on the Mojang-mapped server jar and all Minecraft library jars at compile time. These are wired via `implementation(files(...))` and `implementation(fileTree(...))` with lazy `provider` wrappers — they only resolve if the files exist. The `extractServerJar` and `downloadLibraries` tasks must run first (included in the setup commands above).

## Writing Minecraft Patches

1. Start from a clean branch:

```bash
git checkout -b feature/short-description
```

2. Generate or refresh the source workspace:

```bash
./gradlew generateSourceWorkspace applyPatches
```

3. Edit files in `ver/<minecraftVersion>/patched-source`.

4. Rebuild patch files:

```bash
./gradlew rebuildPatches
```

5. Review the generated patch files in `patches/server/` or `patches/api/`.

Patch files should:

- Use names like `0001-Short-description.patch`.
- Contain only the intended Minecraft source changes.
- Avoid generated compile output, logs, IDE files, and local cache files.
- Keep changes focused. Prefer several small patches over one large mixed patch.

Before committing, inspect the patch:

```bash
git diff -- patches
```

If `rebuildPatches` captures unrelated edits, revert those generated patch changes and regenerate from a clean `patched-source` workspace.

## Testing Patches

Run patch verification:

```bash
./gradlew verifyPatches
```

Compile the patched Minecraft classes:

```bash
./gradlew compileMinecraft
```

Package the server jar:

```bash
./gradlew packageMinecraft
```

Compile project modules that depend on Minecraft classes (`server`, `runtime`):

```bash
./gradlew :server:compileJava :runtime:compileJava
```

For a broader project check, run:

```bash
./gradlew test
```

If your change touches non-Minecraft modules, also run the relevant module build or the full build:

```bash
./gradlew build
```

## Building the VeltisMC Server Jar

Run:

```bash
./gradlew buildVeltisMC
```

The output jars are:

```text
build/distributions/veltismc.jar
build/veltismc-server.jar
```

`build/distributions/veltismc.jar` is the standalone VeltisMC jar with the launcher, Veltis runtime, and patched Minecraft runtime classes. It is a bare NMS server: no Bukkit, Spigot, or Paper API is included or supported. `build/veltismc-server.jar` is the patched Minecraft runtime jar used by the launcher/runtime pipeline.

To only rebuild the patched Minecraft runtime jar, run:

```bash
./gradlew packageMinecraft
```

`buildVeltisMC` downloads Minecraft, decompiles it if needed, generates the source workspace, applies patches, compiles patched classes, packages `build/veltismc-server.jar`, and copies the standalone launcher jar to `build/distributions/veltismc.jar`.

## Runtime Behavior

VeltisMC is a bare NMS server: it boots the patched vanilla `DedicatedServer` directly with no plugin API (no Bukkit/Spigot/Paper). Gameplay is vanilla plus the patch set under `server/patches/`.

Useful runtime commands (vanilla):

```text
help
list
stop
```

Do not copy, fork, or vendor Bukkit/Paper source into this project.

## Pull Request Workflow

1. Create a branch from the latest main branch.
2. Make focused changes.
3. Rebuild patch files if you changed Minecraft sources.
4. Run:

```bash
./gradlew verifyPatches compileMinecraft packageMinecraft build
```

5. Commit only source, patch, and build-script changes that belong to the PR.
6. Push your branch:

```bash
git push -u origin feature/short-description
```

7. Open a pull request.

In the PR description, include:

- What changed and why.
- Which patch files were added or updated.
- The Minecraft version used.
- The commands you ran to test the change.
- Any known limitations or follow-up work.

## Commit Hygiene

Do not commit:

- `ver/`
- `build/`
- `.gradle/`
- IDE metadata
- compile logs or temporary output files

Do commit:

- Patch files in `patches/`
- Gradle/build-tool changes needed by the patch workflow
- Source changes in project modules such as `api`, `server`, `launcher`, `compat-bukkit`, and `compat-paper`
- Tests for non-trivial project-module changes
