# VeltisMC

A bare-NMS Minecraft server for **Minecraft 26.3**, with a region-based world
simulation engine and a build-time patch pipeline.

## What is VeltisMC?

VeltisMC boots the patched vanilla `DedicatedServer` directly. There is **no plugin
API** — no Bukkit, Spigot or Paper. Gameplay is vanilla plus the patch set in
`patches/`.

Every Minecraft change is a git-style unified-diff patch file. VeltisMC ships one
jar — `server.jar`, with no Minecraft code inside it — plus the patch set
packaged in it.

`java -jar server.jar --nogui` does everything from that one file: it resolves the
Minecraft version against Mojang, downloads and SHA-1 verifies `Vanilla/<version>/vanilla-server.jar`
and its libraries, applies the bytecode patch set packaged inside the jar, writes
`Veltis/<version>/veltis-server.jar`, and only then starts Minecraft. A second
start skips all of it: it validates the jar it already has and launches. Nothing
touches the network on that path.

That path never widens, decompiles or compiles, and it cannot: the distributable
ships neither a decompiler nor `javac`. Producing the patch set is a build-time
job — `./gradlew buildVeltisMC` widens access, decompiles, applies the source
patches in `patches/` and compiles the result before cutting the bytecode patch
set the jar carries. An operator's directory holds no source and needs no
toolchain.

Two things follow from that, and both are deliberate. The distribution still
carries no Mojang bytes, so it stays EULA-compliant — Minecraft is fetched from
Mojang at run time, verified against Mojang's own SHA-1, and never redistributed.
And the server directory stays a deployment: no hidden state directory, no
source tree, no class output. Just the two artifacts, the world and the usual
`server.properties`.

## Project Structure

```text
VeltisMC/
├── .github/            CI workflow (build + test + package)
├── launcher/           the entry point; assembles veltismc.jar
├── patch-engine/       download / widen / decompile / diff apply / diff generate / patch,
│                       the workspace layout and the runtime that builds and loads it
├── server/             everything inside Minecraft's classloader: the NMS entrypoint,
│                       the Veltis framework and the world engine
├── patches/            code/ data/ modules/ — the single source of truth
├── gradle/             Gradle wrapper
├── build.gradle.kts    the pipeline and the `minecraft` source set
├── settings.gradle.kts the three modules
└── README.md           this file
```

| Module | Packages | Contains |
|---|---|---|
| `:launcher` | `org.veltismc.launcher` | `VeltisLauncher`, argument handling, the startup clock, uber-jar assembly |
| `:patch-engine` | `org.veltismc.patchengine` | `MojangMetadata`, `MinecraftDownloader`, `MinecraftDecompiler`, `UnifiedDiffPatcher`, `DiffGenerator`, `PatchDiscovery`, `VeltisPatcher`, `PatchRebuilder`, `AccessWidener`, `VeltisRuntime`, `VeltisWorkspace`, `PipelineRunner` |
| `:server` | `org.veltismc.server`, `org.veltismc.runtime.*`, `org.veltismc.world.*` | `Main` (logging, config + EULA, reflective runtime boot); lifecycle, events, scheduler, tick engine, services, containers, `VeltisConfig`, `VeltisBootstrap`; the simulation engine and its `org.veltismc.world.nms` boundary |

Three modules, and each name answers to a real boundary: an operator runs
`:launcher`, the pipeline is `:patch-engine`, and everything that compiles against
Minecraft's classes lives in `:server`. Two modules were removed for the same
reason — `:build-tools` held one class whose callers already depended on
`:patch-engine`, and `:runtime`/`:world` were never depended on from outside the
repository and were always packaged into one jar in a fixed order by a list in
`launcher/build.gradle.kts`.

### The workspace

A contributor's checkout and a server installation use the same engine with two
different layouts, and the difference is where the intermediate work tree lives.

A checkout keeps everything, because the IDE navigates it and
`rebuildVeltisPatches` diffs it:

```text
build/minecraft/<version>/
├── metadata/    cached Mojang version.json + library coordinate list
├── vanilla/     server.jar (bundler), server-classes.jar, server-widened.jar
├── libraries/   Mojang's declared library jars, SHA-1 verified
├── source/      pristine decompile — the baseline patches apply to
├── patched/     source/ + the patch set (the IDE source root)
├── classes/     javac output for the patched sources
├── resources/   the data and modules patch delta
└── build/       patch-targets.txt, decompile marker inputs
```

A server installation keeps nothing but what a launch needs to repeat itself, and
the work tree lives under the system temporary directory for the length of one
build:

```text
<server>/
├── Vanilla/<version>/vanilla-server.jar    Mojang's artifact, SHA-1 verified
├── libraries/**                            the jars Minecraft links against,
│                                            one root shared by every version
└── Veltis/<version>/veltis-server.jar      the patched runtime
```

That is the whole persistent surface. There is no `.vlt`, no marker file, no
metadata directory and no source tree: what produced `veltis-server.jar` is
recorded *inside* it (see `META-INF/veltis/runtime.properties`), because a file
next to a jar can be left behind by an installation the jar does not match.

In both layouts there are **no temporary or per-run directories** — no `temp/`,
no UUID directories. Each step reads and writes fixed, named locations, so a
rerun after a failure resumes from a known state instead of from leftover scratch
space.

`source/` is the single baseline: `rebuildVeltisPatches` diffs `source/<file>`
against `patched/<file>`. There is no third copy of the decompiled tree.

## Architecture

### Module graph

```text
launcher ──> patch-engine
launcher ──> server ──> patch-engine
```

Minecraft's jar is a compile dependency of `:server` only, contributed by the
root build from the one workspace declaration, so no module defines its own view
of it. The launcher jar carries all three modules plus the patch set, which is
what makes `java -jar server.jar` self-sufficient.

### Acquisition

Minecraft comes from Mojang's official endpoints, resolved at build time from a
single `minecraftVersion` property. There are no hardcoded per-version URLs and no
third-party mirrors anywhere in the codebase.

```text
version_manifest_v2.json → version metadata → server jar + library list
                                                  │
                                    every artifact SHA-1 verified
```

A cached artifact that fails verification is proven bad, discarded and re-fetched,
so one corrupt entry costs a re-download rather than a broken build. A cached
source tree is only reused when its marker proves it is the decompile of the
requested version, server jar and library set.

### The patch engine

`patch-engine` is Veltis's own; no `patchy`, no `git apply`, no external diff
tool. Patches are git-style unified diffs with the usual semantics retained.

- **Categories** are exactly `patches/code`, `patches/data`, `patches/modules`.
  They determine apply order (`code` → `data` → `modules`) and which source set the
  target lands in (`minecraft`, `minecraftResources`, `minecraftModules`). Within a
  category, patches apply in file-name order.
- **Application** is read-once → apply the whole per-file chain in memory →
  verify → one atomic write. A file is never left half-patched: either the complete
  chain succeeds and the result replaces the original atomically, or nothing is
  written.
- **Determinism** comes from `LinkedHashMap` grouping in first-seen order, so
  same-file chains are structurally sequential and independent targets can run in
  parallel without the result depending on thread scheduling or worker count.
- **Parallelism** uses a fixed `veltis-patch-N` platform-thread pool sized from the
  `patchWorkers` property. Never `ForkJoinPool.commonPool()`.
- **Failures** name the patch, category, target, location and reason, and stop the
  build:

  ```text
  [VeltisPatch] Failed to apply patch: 005-Wire-VeltisBootstrap-Shutdown.patch
    Category: code
    Target: net/minecraft/server/MinecraftServer.java
    Location: patch 1 of 1, hunk #2
    Reason: patch 1 of 1, hunk #2 found no matching context: tried exact,
            whitespace-tolerant and blank-skipping
    Minecraft: 26.3
    Patch revision: 894b9815255046c7c474dd303cd3bd17ede8fbcdaaad1cd168dab631b7d92b7c
  ```

  The revision is the SHA-256 of the patch file's bytes, so the message identifies
  the exact patch content rather than just its name.

### Runtime boot

```text
VeltisLauncher ──(locates the prebuilt jar)──> org.veltismc.server.Main
                                                     │ Class.forName
                                                     ▼
VeltisBootstrap.boot()                          [runtime]
 ├─ VeltisConfig.load(home)
 └─ DefaultServerRuntime
      ├─ PlayerContainer / WorldContainer, SimpleEventBus
      ├─ DefaultLifecycleManager, DefaultTaskScheduler, DefaultTickEngine
      ├─ ServerMetrics
      └─ DefaultServiceRegistry ──> WorldEngineService ──> WorldEngines.create()
```

The launcher asks `VeltisRuntime` to prepare whatever it is missing — a warm
start validates `Veltis/<version>/veltis-server.jar`, hashes the vanilla artifact
and launches without touching anything else; a cold start builds the whole runtime
through that same call the Gradle pipeline uses.

Patch `004` wires the same entry point into the patched `DedicatedServer`
(`VeltisBootstrap.onMinecraftServerCreated`), so the vanilla boot path stays
identical to the one `Main` uses. Patch `005` wires the matching shutdown hook
(`VeltisBootstrap.onServerStopping`) into the vanilla stop path, just before
`Saving worlds`.

### Startup order

Everything on the lifecycle path runs synchronously on one thread — no
`CompletableFuture.runAsync` / common ForkJoinPool — so the console order is
deterministic:

```text
1. Launcher       VeltisStartup.begin(): the instant the Done line is measured from
2. Prepare         resolve version -> validate or build the runtime (cold: download,
                   widen, decompile, patch, compile, package; warm: verify only)
3. Server Main    Log4j2 config, server.properties, eula.txt
4. Vanilla init   version, properties, keypair, port bind, level prepare + spawn
5. Patch 004      VeltisBootstrap.onMinecraftServerCreated: config -> event bus
                  -> lifecycle -> scheduler -> tick engine -> metrics -> services
                  -> (world engine) -> runtime started
6. Guard          verifyPatchedClasses: every patched class really came from
                   Veltis/<version>/veltis-server.jar, with the compiled bytes
7. Done (X.XXXs)! the whole of 1-6, printed once
```

Shutdown mirrors it:

```text
stop -> Stopping server -> Saving players -> [patch 005] Stopping VeltisMC ->
VeltisMC stopped -> Saving worlds -> process exit
```

Every phase logs a start line and a completion line carrying its real elapsed
time. There are no fake completions, no banners/tables, and no duplicated vanilla
messages.

### Logging

VeltisMC has exactly **one** logging system: Log4j2, the same one Minecraft
uses.

- Console and `logs/latest.log` share the pattern `[HH:mm:ss LEVEL]: msg`;
  Veltis messages carry a `[VeltisMC]` or `[Veltis]` prefix.
- `VeltisConsole.configureLog4j` sets the `log4j2.configurationFile` system
  property to VeltisMC's own config before the first log statement. The property
  (not `Configurator.initialize`) is what makes this reliable: the launcher runs
  the server in a child class loader, and a JVM-wide property is the only thing
  both Log4j2 instances consult. Mojang's classpath carries a competing
  `log4j2.xml` in `com.mojang:logging`, and when that one wins it emits the
  `Queue`/`Listener`/`ServerGuiConsole`/`Tracy` appender errors. Verified gone:
  a `--nogui` start produces no `ERROR` lines on stderr.
- `java.util.logging` (and therefore `System.getLogger`) is bridged into
  Log4j2, so nothing writes to the console out-of-band.
- Diagnostics (jar/classpath details, decompiler chatter) run at DEBUG and appear
  only with `--verbose`.

JDK 24+ prints two warnings that come from **Mojang's own libraries**, not from
VeltisMC code:

- `sun.misc.Unsafe` — `org.joml` 1.10.9 is pinned by Mojang's library manifest.
- `System::load` — vanilla's `NativeModuleLister` uses JNA, and Mojang ships
  `net.java.dev.jna` 5.17.0 in `libraries/`.

Both are upstream, and both are printed by the JVM itself, so nothing in the
server can silence them after the fact — and redirecting stderr to hide them
would hide real failures along with them. `java -jar server.jar` therefore
re-starts itself once with the options that were missing:

```bash
java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
     -jar server.jar --nogui
```

If you already pass either flag (including `--sun-misc-unsafe-memory-access=warn`
rather than `allow`), yours is used and no second start happens. Every other JVM
option — `-Xmx`, agents, `--add-opens` — is carried across unchanged, and this
restart merges with the `--home` working-directory restart into a single one, so
a launch that needs both still starts exactly two JVMs.

## Build-time pipeline

```text
downloadMinecraft ──> widenServerJarAccess ──> downloadLibraries
        │                                        │
        └──────────────> decompileMinecraft <────┘
                              │
                         applyVeltisPatches
                              │
              ┌───────────────┴───────────────┐
   compileMinecraftJava            processMinecraftResources
              └───────────────┬───────────────┘
                              │
        prepareVeltisRuntime ──> build/minecraft/<v>/veltis-server.jar
        packageVeltisMC      ──> build/distributions/veltismc.jar
```

Two outputs, and they are deliberately different:

| Artifact | Contents | Purpose |
|---|---|---|
| `build/distributions/veltismc.jar` | the launcher, the engine and the **bytecode patch set**, no Minecraft code | the distributable; the one file an operator needs |
| `Veltis/<version>/veltis-server.jar` | Mojang's classes jar with only the entries the patch set names replaced or removed, plus the record of what produced it | the runtime the server loads classes from |

`veltis-server.jar` contains Minecraft code, so it is produced on the machine that
runs it, not redistributed — which is also why its identity is recorded *inside*
it rather than next to it. What ships in `veltismc.jar` is the difference between
that jar and vanilla: whole classes where a patch changed one, both SHA-1s on
every entry, and nothing else. No source, no decompiler, no compiler.

### Compile scope

Decompiling Minecraft does not produce sources that compile as a whole, and
compiling all ~15 000 of them to discover that is not useful. The compile task is
scoped to the files the patch set actually addresses, recorded in
`build/patch-targets.txt` by the patcher itself. Unpatched classes are carried
over from Mojang's own jar untouched, so the compiled output is exactly the delta
the patches introduce — and that delta is what the bytecode patch set is cut
from, rather than a jar rebuilt from scratch.

`compileMinecraftJava` compiles only the `code` targets into
`build/minecraft/<v>/classes` against a classpath of the compiled classes, the
widened jar and the library tree. `processMinecraftResources` copies only the
`data` and `modules` targets.

Scoping the compile is also what makes the decompiler's known defects survivable —
see [Known limitations](#known-limitations).

### Intellij

`minecraft` is a real Gradle source set whose root is `build/minecraft/<v>/patched`.
The root is declared unconditionally, so an IDE opened before the first pipeline
run still has the right folder registered; it simply stays empty until the
pipeline fills it. There is no `Mark Sources Root` step and no ad-hoc IDE files.
The root project's IDEA exclusion lists `build/`'s children one at a time and
leaves `build/minecraft` off the list: excluding a directory that contains a
source root makes the IDE lose the source root, and that is exactly how this used
to need a manual mark in every checkout. The same block passes the source set's
root to the `.iml` writer, because Gradle's Idea plugin reads only the main and
test source sets when it writes those files and would otherwise emit a root
module with no source roots in it.

IntelliJ IDEA is the recommended IDE: run `./gradlew prepareMinecraft` once, then
`./gradlew idea` (or just build) and the decompiled sources are navigable with
autocomplete.

### Reflective couplings — keep in sync

These strings are load-bearing and deliberately not compile-time references:

- `server/.../Main.java` → `org.veltismc.runtime.VeltisBootstrap`
  (the `Wire-VeltisBootstrap-Integration` and `Wire-VeltisBootstrap-Shutdown`
  patches wire the same class into vanilla)
- `launcher/.../VeltisLauncher.java` → `org.veltismc.server.Main`
- Gradle `mainClass` strings: `org.veltismc.patchengine.PipelineRunner`,
  `org.veltismc.launcher.VeltisLauncher`
- `DedicatedServer` (the `Report-Total-Startup-Time` patch) →
  `org.veltismc.launcher.VeltisStartup`, so the
  server's only `Done` line reports the whole launch

Renaming any of these classes means updating every string above. Patch *numbers*
are deliberately not quoted here: `rebuildPatches` renumbers the set, so the
descriptions are the stable reference.

### Design rules

- Three modules (`launcher`, `patch-engine`, `server`), each a boundary something
  actually depends on. Adding one needs a strong technical reason.
- Shallow packages: `org.veltismc.<module>`, one level below at most.
- No `I*`/`Impl` interface pairs with a single implementation.
- No Manager/Service/Controller/Provider chains to move logic around.
- No util dumping grounds; code lives with the concept it serves.
- Delete dead code instead of keeping it "just in case" (search first; never delete a
  test to make the build green).

## Known limitations

Three things are not solved, and each is stated with what it costs and what to do
about it.

### 1. Vineflower emits a non-compiling placeholder for synthetic switch maps

When javac compiles a `switch` over an enum, it synthesises a
`$SwitchMap$…` lookup array. Vineflower inlines the lookup as a field that does not
exist in the source language, and marks it `<unrepresentable>`:

```java
byte eventId = switch (<unrepresentable>.$SwitchMap$net$minecraft$server$permissions$PermissionLevel[permissions.level().ordinal()]) {
    case 1 -> 24;
    …
```

`<unrepresentable>` is not a Java expression, so any file containing it fails to
compile. 111 of the 5037 decompiled files contain one; only the four files the patch
set addresses are ever handed to javac, and one of those needed a repair.

This is currently harmless **only** because the compile is scoped to the patch
targets (§ [Compile scope](#compile-scope)) — the other 110 files are never handed
to javac. It becomes real the moment one of them is added as a target.

When that happens, add a small `patches/code` patch that rewrites the switch to the
enum constants themselves. `Fix-Decompiled-PermissionLevel-Switch.patch` is the
worked example: it turns the switch above into

```java
byte eventId = switch (permissions.level()) {
    case ALL -> 24;
    case MODERATORS -> 25;
    case GAMEMASTERS -> 26;
    case ADMINS -> 27;
    case OWNERS -> 28;
};
```

Derive the mapping from the bytecode, not from the decompiled labels —
`javap -c` on `PlayerList$2.<clinit>` in `vanilla/server-classes.jar` gives the
`$SwitchMap` values and `javap -c` on the method gives the case bodies. The
recompiled result was checked against the original Mojang bytecode and is
instruction-identical, constant-pool indices included.

### 2. `patchWorkers` above the target count changes nothing

Parallelism is capped at the number of independent target files
(`min(patchWorkers, groups)`), so `-PpatchWorkers=8` still reports 4 workers on a
4-file patch set. The cap is what makes the result independent of the setting; the
`workers=1/4/8` equivalence is proved on fixtures with enough files for 8 threads to
actually start.

### 3. The current patch set touches four vanilla files

`patches/code` addresses `Commands`, `MinecraftServer`, `DedicatedServer` and
`PlayerList`. That is what the runtime hooks (bootstrap, shutdown, console/command
logging) need, and it keeps the compile scope small enough to avoid the
`<unrepresentable>` problem above — but it means VeltisMC is currently a platform
*around* vanilla rather than one that has replaced parts of it. Growing the patch
set means growing the compile scope, and limitation 1 becomes the thing that
blocks you.

## World Engine

The world engine lives in `:server`, beside the runtime framework it serves:

```text
server/src/main/java/org/veltismc/world/
├── api/        WorldEngine, WorldConfig, WorldEngines (runtime-facing entry point)
├── core/       regions, chunks, entities, simulation loop
├── chunk/      chunk state machine
├── scheduler/  simulation scheduling
├── util/       lock-free queue, object pool
└── nms/        NMS adapters — the only engine code allowed to touch net.minecraft.*
```

**Design rule:** Minecraft (NMS) owns *gameplay* logic; Veltis owns *execution* —
where, when and on which worker Minecraft's existing logic runs, and who owns each
piece of mutable world state. Veltis never re-implements vanilla mechanics (mob AI,
pathfinding, physics, fluids, redstone, generation); it schedules them.

The engine outside `nms/` is pure JDK and must stay compilable with no Minecraft jar
on the classpath. The only classes in the repository that reference `net.minecraft.*`
are `org.veltismc.server.Main` (which hands the process over to vanilla) and
`org.veltismc.world.nms`, and both are compiled against
`build/minecraft/<v>/vanilla/server-widened.jar`.

How it works:

- **Region ownership** — a region (32×32 chunks) has exactly one owner worker at any
  instant; only the owner mutates it. Ownership is dynamic and never shared.
- **Scheduler** — bounded adaptive worker pool, MPSC queues, priority draining,
  parking instead of busy-waiting; jobs are cancellable and isolated (one broken job
  cannot kill the engine).
- **Chunk lifecycle** — a CAS-validated state machine
  (`UNLOADED → LOADING → READY ⇄ SIMULATING ⇄ DIRTY → SAVING`) so each chunk has
  exactly one in-flight job owning it: no duplicate loads/generation/saves.
- **Async load & generation** — load (I/O → decode → apply) and six-stage generation
  run off the owner thread; the NMS adapter delegates terrain stages to vanilla's
  `ChunkStatus` chain.
- **Cross-region messaging** — entity migration, block deltas and explosion fragments
  are immutable messages over MPSC queues (FIFO per destination).
- **Persistence** — dirty chunks save through immutable snapshots with dirty
  tracking; flush completes only when quiescent.

Status: the engine, its scheduler and persistence are implemented and tested;
`NmsWorldEngineBridge`/`NmsWorldAdapter` are compiled and bundled but **not yet
invoked** from a patch or from `:server`. Binding the engine into the ticking
`ServerLevel` (vanilla `Mob.tick()` on region owners, chunk tickets, pathfinding
offload) is the next integration step — until then vanilla gameplay remains the
source of truth.

Guarantees: no global world lock, no thread per region, no busy-waiting; all
mutation is single-writer (region ownership or CAS chunk-state claim); the same seed
and inputs produce the same simulation regardless of worker count.

## Performance

The patch engine is benchmarked and regression-tested inside `:patch-engine`.

```bash
./gradlew :patch-engine:benchmark
```

The benchmark reports discovery, parsing, application, I/O and wall time
separately so decompilation and javac are never conflated with patching, and it
supports `--save`/`--compare` baselines so improvements are shown with
measurements rather than claimed.

**Methodology rules:**

- Same machine, same JVM, same filesystem, same corpus for every comparison.
- Never compare a warm cache against a cold one; cold and warm runs are labelled.
- Phase times are summed across parallel workers and can exceed the wall-clock
  total, so the total is the number to compare.
- Correctness is checked independently of speed: applying the patch set to
  `build/minecraft/<v>/source/` must reproduce `build/minecraft/<v>/patched/`
  byte-for-byte, at every worker count.

**Regression protection** (all run on `./gradlew build`):

| Suite | Property |
|---|---|
| `PatchOutputRegressionTest` | byte-identical output at workers 1/4/8 |
| `UnifiedDiffPatcherTest` | hunk semantics, input validation, failure diagnostics |
| `LineSplitTest` | line splitting stays equivalent to the regex it replaced |
| `PatchPerformanceTest` | structural I/O counters plus a throughput floor |
| `GradlePipelineTest` | real pipeline invariants (cache invalidation, rebuild isolation, library fetch, module jars in the server artifact) |

The test suite passes with `./gradlew clean build`.

The guard is verifiable rather than asserted:

```bash
./gradlew verifyVeltisRuntime -PveltisGuardCanary=true   # must FAIL
./gradlew verifyVeltisRuntime                            # must pass
```

The canary builds a class loader that resolves the vanilla classes jar *first*,
so the loader hands out vanilla's version of every patched class. The guard then
reports the first class whose bytes are not the ones recorded when
`veltis-server.jar` was packaged, and refuses to start. The ordering is not a
stylistic preference: with a classpath that has both, whichever comes first wins.

## Build Instructions

Requirements: JDK 26 on `PATH` (or `JAVA_HOME`), Git, internet access on the first
build, and the Gradle wrapper in this repository (`gradlew.bat` on Windows,
`./gradlew` elsewhere).

```bash
# Build every module and run the tests
./gradlew build

# Download Minecraft, decompile, patch, compile and package the server
./gradlew buildVeltisMC
```

Both work on a fresh clone: the `server` compile task pulls in the pipeline
itself, and the first run downloads Minecraft once.

The distributable is `build/distributions/veltismc.jar`. Copy it anywhere as
`server.jar` and start it; it builds whatever it is missing in that directory:

```bash
cp build/distributions/veltismc.jar /path/to/server/server.jar
cd /path/to/server
java -jar server.jar --nogui
```

The first start downloads, decompiles, patches, compiles and packages into
`Vanilla/` and `Veltis/`; every later one just validates and runs. Minecraft
resolves `world/`, `server.properties` and `eula.txt` against the working
directory, so run it from the directory that should hold the server data.

Useful Gradle tasks:

| Task | Purpose |
|---|---|
| `./gradlew build` | compile all three modules + tests |
| `./gradlew buildVeltisMC` | full pipeline + distributable in `build/distributions/` |
| `./gradlew prepareMinecraft` | download, decompile, patch, compile, copy resources |
| `./gradlew downloadMinecraft` | resolve + fetch the server jar (SHA-1 verified) |
| `./gradlew widenServerJarAccess` | widen class/field/method access in the server jar |
| `./gradlew downloadLibraries` | fetch Mojang's declared library jars |
| `./gradlew decompileMinecraft` | decompile the widened jar into `source/` |
| `./gradlew applyVeltisPatches` | apply `patches/{code,data,modules}` into `patched/` |
| `./gradlew rebuildVeltisPatches` | regenerate patch files from `patched/` edits, renumbered contiguously |
| `./gradlew applyPatches` / `./gradlew rebuildPatches` | the contributor-facing aliases of the two tasks above |
| `./gradlew cleanVeltisPatches` | discard `patched/`, `classes/`, `resources/` |
| `./gradlew :patch-engine:benchmark` | run the patch-engine benchmark |
| `./gradlew idea` | refresh IDE model |

The Minecraft version and the patch worker count are build properties in
`gradle.properties`:

```properties
minecraftVersion=26.3
patchWorkers=4
```

Bumping `minecraftVersion` re-runs the whole chain against the new version; no
URL is ever hardcoded.

## Development

### Writing Minecraft patches

The full contributor workflow — naming, numbering, chains, and deleting or
renaming patches — is documented in [`CONTRIBUTING.md`](CONTRIBUTING.md). The
short version:

```bash
./gradlew applyPatches            # pristine workspace + patches applied
# edit files in build/minecraft/<v>/patched/
./gradlew rebuildPatches          # regenerate patches/
git diff -- patches               # review before committing
```

`applyPatches` and `rebuildPatches` are aliases of the pipeline's own
`applyVeltisPatches` and `rebuildVeltisPatches`; both spellings run the same
task.

Patch rules:

- Files are `NNN-Short-description.patch` in `patches/code`, `patches/data` or
  `patches/modules`; the directory determines apply order and which source set
  the target lands in.
- `NNN` is assigned by the build and renumbered contiguously after every rebuild,
  so a deletion pulls the series up and leaves no hole. Renumbering changes only
  the number — never the description, the targets or a byte of the diff.
- Only the intended Minecraft source changes belong in a patch; no generated
  output, logs, IDE files or local caches.
- Prefer several small patches over one large mixed patch.
- A patch that repairs a decompiler defect (limitation 1) is still an ordinary
  `patches/code` patch; keep it separate from the feature patch that edits the same
  file, so the repair survives a feature rewrite.
- `patches/` is the **single** source of truth. There is no second patch
  directory and nothing is bundled for runtime application.

Rebuild uses `DiffGenerator` (Myers O(ND) with prefix/suffix trimming), so the
regenerated files are deterministic: the same edits always produce the same bytes.
Hunks are located by content, not by line number, so a rebuilt patch keeps applying
when unrelated lines shift above it.

Several patches may address one file — that is how two unrelated changes to
`DedicatedServer` stay separately reviewable. The rebuild replays the chain to
recover each intermediate state: with no edits it regenerates every patch
byte-identically, and with an edit it puts the change in the last patch of the
chain, leaving the earlier ones untouched.

`rebuildVeltisPatches` deliberately does **not** depend on `applyVeltisPatches`.
`apply` re-mirrors `source/` over `patched/`, so depending on it would discard the
edits being captured. For the same reason, deleting a patch means deleting the
file, running `applyPatches`, and then `rebuildPatches`: the rebuild records what
it applied and refuses to run while one of those patches is missing, rather than
silently diffing the change straight back into a new file.

### Runtime behaviour

VeltisMC is a bare NMS server. Gameplay is vanilla plus the patch set; useful vanilla
console commands are `help`, `list`, `stop`. Do not copy, fork or vendor
Bukkit/Paper source into this project.

## Testing

```bash
./gradlew test                                      # all modules
./gradlew test --no-build-cache --rerun-tasks       # force a fresh run
./gradlew :patch-engine:test :server:test
```

JUnit 5, standard `src/test/java` layout:

| Module | Suites |
|---|---|
| `server` | `VeltisConfig` load/round-trip, `DefaultServerRuntime` start/shutdown/double-start, chunk state machine, scheduler, lock-free queue, object pool, engine smoke test (headless, no Minecraft jar) |
| `patch-engine` | Mojang resolution + SHA-1, download + corrupt-cache recovery, decompile-cache invalidation, access widening, discovery, deterministic ordering, same-file chains, parallel patching, conflicts, rollback/atomicity, code/data/module patches, output regression, line-split equivalence, performance floor, runtime artifact + guard, Gradle pipeline declaration |

Never delete a failing test to make the build green — fix the code (or the test, if
it asserts the wrong behaviour).

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first: it has the full patch workflow
(naming, numbering, chains, deleting and renaming), every command in this
repository spelled out for Windows and for Linux/macOS, the troubleshooting
messages you are most likely to hit, and the rules that keep the distributable
clean.

The short version:

1. Branch from the latest main and keep the change focused.
2. If you changed Minecraft sources, rebuild the patch files (see
   [Development](#development)).
3. Before committing run:

```bash
./gradlew build buildVeltisMC
```

4. Commit only source, patch and build-script changes that belong to the PR.
5. Open a PR describing what changed and why, which patch files were touched, the
   Minecraft version used, the commands you ran and any known limitations.

Do **not** commit: `build/`, `.gradle/`, `Vanilla/`, `Veltis/`, `out/`, `run/`, IDE
metadata, compile logs, temporary patch files, or server data (`eula.txt`,
`server.properties`, `logs/`, `config/`) — all gitignored.

Do commit: patch files in `patches/`, module sources and tests, Gradle/build-script
changes needed by the workflow, documentation updates.

## License

See [`LICENSE`](LICENSE).
