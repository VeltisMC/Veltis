# Contributing to VeltisMC

Everything here describes commands that have been run from this repository and
behaviour the build actually has. If a documented command does not do what this
file says it does, that is a bug — report it alongside the change that exposed it.

Commands are shown for both platforms. On Windows use the Gradle wrapper in the
repository root:

```powershell
.\gradlew.bat <task>
```

On Linux and macOS use the same wrapper:

```bash
./gradlew <task>
```

Both are the same build. Every command below works from the repository root, and
no Gradle installation is required — the wrapper downloads the right one.

## 1. Before you start

| Requirement | Why |
|---|---|
| JDK 26 on `PATH` (or `JAVA_HOME`) | every module compiles and runs on it |
| Git 2.x | the patch set is version-controlled data, and Git is what applies it and renders rebuilds |
| Internet on the first build | Minecraft and its libraries are fetched from Mojang and SHA-1 verified |
| The Gradle wrapper in this repository | `gradlew.bat` / `gradlew`; no global Gradle to install or match |

Nothing else. The decompiler and the test framework are all in the build, patch
application and rebuild rendering are the Git you already installed, and the server
runtime has no dependency on any of them.

Your working directory for a rebuild is the repository root, because that is what
decides where `Shulker/` and `build/minecraft/<version>/` are.

## 2. Repository layout

```text
launcher/                    the entry point; assembles the distributable
patch-engine/                download / widen / decompile / diff / patch / workspace
server/                      the runtime and the world engine
Shulker/code/                source patches, compiled             (committed)
Shulker/data/                source patches, resources            (committed)
Shulker/modules/             source patches, Veltis modules        (committed)
build/minecraft/<version>/   generated workspace                  (gitignored)
build/distributions/         the distributable                    (gitignored)
```

The generated workspace is laid out like this:

```text
build/minecraft/26.3/
├── metadata/    cached Mojang version.json + library coordinates
├── vanilla/     server.jar (bundler), server-classes.jar, server-widened.jar
├── libraries/   Mojang's declared library jars, SHA-1 verified
├── source/      pristine decompile — the baseline patches apply to
├── patched/     source/ + the patch set (the IDE source root)
├── classes/     javac output for the patched sources
├── resources/   the data and modules patch delta
└── build/       patch-targets.txt, applied-patches.txt, decompile markers
```

`source/` is the single baseline. There is no third copy of the decompiled tree,
and there is no temporary or per-run directory — each step reads and writes a
fixed, named location, so a rerun after a failure resumes from a known state.

## 3. The two patch layers

VeltisMC has exactly two patch layers, and no third patch engine.

**Layer 1 — the source patch set, in Git.** `Shulker/code`, `Shulker/data` and
`Shulker/modules` hold git-style unified diffs against the decompiled Minecraft
source. This is the layer you edit, review and commit. Everything else in the
build is derived from it.

**Layer 2 — the bytecode patch set, at build time.** The build compiles the
patched sources and cuts a bytecode patch set into
`META-INF/veltis/patches/<version>.zip` inside the distributable: whole classes
where a patch changed one, both SHA-1s recorded per entry, and nothing else. It is
generated from layer 1 on every build.

What follows from that:

- You never hand-edit layer 2, and you never commit it.
- There is no source-patch application at run time. The server downloads vanilla,
  verifies it, applies the bytecode patch set, and launches.
- Do not introduce a second mechanism for changing Minecraft's behaviour. If a
  change belongs in `Shulker/`, put it there.

## 4. Making a change: the edit loop

The whole contribution loop is three commands.

**Windows (PowerShell or cmd):**

```powershell
.\gradlew.bat applyPatches
# edit files under build\minecraft\26.3\patched\
.\gradlew.bat rebuildPatches
git diff -- Shulker
```

**Linux / macOS:**

```bash
./gradlew applyPatches
# edit files under build/minecraft/26.3/patched/
./gradlew rebuildPatches
git diff -- Shulker
```

`applyPatches` mirrors the pristine decompile into `patched/` and applies the
whole patch set over it, so you start from a known state rather than from
whatever a previous run left behind. You then edit `patched/` — it is a real
Gradle source root, so IntelliJ will index it and let you navigate into
`net.minecraft.*`. `rebuildPatches` diffs `patched/` against `source/` and writes
the difference back into `Shulker/`.

> **Do not run `applyPatches` between editing and rebuilding.** `apply`
> re-mirrors `source/` over `patched/`, which discards the edits you are trying to
> capture. `rebuildPatches` deliberately does not depend on `applyPatches` for
> exactly this reason; keep the same discipline by hand.

Review the diff before committing. `git diff -- Shulker` should show only the
change you meant to make — if it shows a file you never touched, see
[§11 Renaming and reordering](#11-renaming-and-reordering).

## 5. Patch file format

A patch file is named:

```text
NNN-Short-description.patch
```

and contains a git-style unified diff. One file may hold several sections —
several `--- a/<target>` / `+++ b/<target>` blocks — when one logical change
touches more than one file.

- `NNN` is a three-digit number assigned by the build, not chosen by hand.
- `Short-description` uses `A-Z a-z 0-9 . _ -` only; path separators and other
  punctuation become `-`, and runs of `-` collapse.
- A patch applies to `source/` with hunks located by content, not by line number,
  so a patch keeps applying when unrelated lines shift above it.
- The files are stored with LF endings (pinned by `.gitattributes`), because the
  rebuild compares them byte for byte.

Regeneration is deterministic: `rebuildPatches` uses Myers O(ND) diffing with
prefix/suffix trimming, so the same edits always produce the same bytes.

## 6. Categories

There are exactly three categories, and the directory a patch lives in decides
everything about it:

| Directory | Apply order | Source set | Typical targets |
|---|---|---|---|
| `Shulker/code` | 1st | `minecraft` (compiled) | `net/minecraft/**/*.java` |
| `Shulker/data` | 2nd | `minecraftResources` | data files, resources |
| `Shulker/modules` | 3rd | `minecraftModules` | `org/veltismc/**` |

Within a category, patches apply in file-name order — which, after a rebuild, is
number order.

When a rebuild creates a patch for a file no patch addresses yet, it picks the
category from the target path using one rule, stated once so every rebuild
agrees:

```text
org/veltismc/...            -> modules
*.java                      -> code
everything else             -> data
```

If that rule puts your file in the wrong category, move the finished `.patch`
into the right directory and rebuild again.

## 7. Numbering rules

These are the rules that make `Shulker/` reviewable and mergeable.

1. `NNN` runs from `001` upward and is **three digits**.
2. **Each category is its own series.** `code`, `data` and `modules` each start
   at `001`; a number is never shared across categories.
3. **Numbers are contiguous.** After any `rebuildPatches` run, a category contains
   `001`, `002`, `003`, … with no holes.
4. **Numbering follows discovery order**: category order (`code`, then `data`,
   then `modules`), then file-name order within the category.
5. **A deletion pulls the series up.** Remove `001` and `002` becomes `001`; no
   gap is left behind.
6. **Renumbering touches only the `NNN-` prefix.** The description, the
   category, the targets and every byte of the diff stay exactly as they were. A
   rebuild that changes content when you only moved a file is a bug.
7. **Ordering never comes from the filesystem.** No directory iteration order, no
   timestamps, no worker completion order — the same patch set numbers the same
   way on every machine.

You do not pick numbers yourself. Create the file, run `rebuildPatches`, and take
the number it is given.

## 8. Patch chains

Several patches may address the same file. That is how two unrelated changes to
one file stay separately reviewable — for example, a logging improvement and a
runtime hook both touching `DedicatedServer.java`.

`rebuildPatches` understands chains. It replays the chain one patch at a time
against the pristine file, recovering every intermediate state:

- **No edits** → every patch in the chain regenerates byte-identically, and the
  rebuild reports `0 regenerated`.
- **You edit the file** → the change lands in the **last** patch of the chain,
  because that is the only position from which your edited tree is reachable.
  Every earlier patch keeps its bytes, so an edit never rewrites a patch you did
  not touch.
- **You revert the file to pristine** → no patch in the chain needs it; a patch
  left with nothing at all is deleted.

Chains are ordered by number, so reordering a chain changes what it means. If you
need two changes to one file to be independent, give them separate patches and
keep them in the order you want them applied.

> Older versions of this repository refused to rebuild while a chain existed and
> told you to merge them by hand. That is no longer the case; do not merge chains
> that you split on purpose.

## 9. Creating a patch

Edit or create a file under `patched/`, then rebuild. A file that no patch
addresses gets its own patch, numbered as the next entry in its category and
described from its path:

```text
build/minecraft/26.3/patched/net/minecraft/util/Helper.java
    -> Shulker/code/003-net-minecraft-util-Helper.patch
```

Case is preserved; only path separators and punctuation become `-`.

To choose the description instead, pass `-PpatchName`. The number still comes
from the position — this sets the description only.

**Windows:**

```powershell
.\gradlew.bat rebuildPatches "-PpatchName=Improve Helper Logging"
```

**Linux / macOS:**

```bash
./gradlew rebuildPatches "-PpatchName=Improve Helper Logging"
```

Both produce `003-Improve-Helper-Logging.patch`. A leading number is stripped, so
`"-PpatchName=007-Improve-Logging"` does not become `001-007-Improve-Logging`.

Two deliberate behaviours:

- **The rebuild creates no new patch** → the property is ignored with a warning
  rather than failing. Passing it out of habit is harmless.
- **The rebuild creates more than one new patch** → the rebuild refuses, names
  the competing targets and writes nothing. Guessing which file you meant would
  silently put your name on the wrong patch.

## 10. Deleting a patch

Order matters here more than anywhere else.

```text
1. delete the .patch file
2. run applyPatches        # rebuild patched/ from the patches that remain
3. edit patched/ again, if you meant to keep part of the change
4. run rebuildPatches      # the survivors renumber contiguously
```

**Windows:**

```powershell
Remove-Item patches\code\001-Improve-Command-Logging.patch
.\gradlew.bat applyPatches
.\gradlew.bat rebuildPatches
git diff -- Shulker
```

**Linux / macOS:**

```bash
rm Shulker/code/001-Improve-Command-Logging.patch
./gradlew applyPatches
./gradlew rebuildPatches
git diff -- Shulker
```

**Why step 2 is not optional.** After you delete the file, `patched/` still
contains that patch's changes. `rebuildPatches` diffs `patched/` against
`source/`, so it would diff the change straight back into a new patch file and
your deletion would silently never happen.

The rebuild protects you from this. Each run records what it applied in
`build/minecraft/<version>/build/applied-patches.txt` — category, name, SHA-256
and targets. If a recorded patch no longer exists on disk, the rebuild stops
before writing anything and says so:

```text
[VeltisPatch] A patch file was deleted after this workspace was last patched
  Missing: code/001-Improve-Command-Logging.patch
    Last known targets: net/minecraft/commands/Commands.java
  Reason: patched/ still contains that patch's changes, so a rebuild would
          diff them straight back into a new patch file and the deletion would
          never have happened
  Fix: run ./gradlew applyPatches to rebuild patched/ from the patches that
       remain, edit the result if you meant to change more, then rebuild
  Note: nothing was written
```

Follow the `Fix:` line. Nothing was modified.

## 11. Renaming and reordering

Both are ordinary edits to file names, followed by the same two commands.

**To change a description**, rename keeping the number:

```text
003-Old-Name.patch  ->  003-New-Name.patch
```

**To change apply order**, change the numbers — order within a category is number
order. Renaming two files to swap their descriptions also swaps their order:

```text
001-Base.patch  002-Feature.patch   ->   001-Feature.patch  002-Base.patch
```

Then re-establish the workspace and let the build settle the numbering:

```powershell
.\gradlew.bat applyPatches
.\gradlew.bat rebuildPatches
git diff -- Shulker
```

(`./gradlew applyPatches` and `./gradlew rebuildPatches` on Linux/macOS.)

**What must not change.** After a rename or a renumber, the category, the targets
and every byte of the diff are unchanged. Only `NNN-` moves. If `git diff` shows a
content change to a patch you only renamed, that is a bug — please report it with
the output of `git diff -- Shulker`.

## 12. Verifying your change

Run these from the repository root before you open a pull request.

```powershell
.\gradlew.bat applyPatches      # the patch set still applies cleanly
.\gradlew.bat clean build       # every module compiles, every test passes
.\gradlew.bat buildVeltisMC     # the distributable is produced
```

```bash
./gradlew applyPatches
./gradlew clean build
./gradlew buildVeltisMC
```

Two checks worth doing by hand when you touched numbering:

- **Idempotence.** Run `rebuildPatches` a second time. It must report
  `0 regenerated, 0 created, 0 removed`, and `git diff -- Shulker` must be empty.
  A rebuild with no edits produces no version-control diff.
- **Round trip.** After `rebuildPatches`, run `applyPatches` again. The patched
  tree must come out identical — the regenerated set reproduces exactly the tree
  you edited.

Run `applyPatches` before `build`, not after: `clean` does not remove
`build/minecraft/`, so the two do not interfere, but starting from a freshly
applied set is what makes the result meaningful.

Never delete a failing test to make the build green — fix the code, or the test
if it asserts the wrong behaviour.

## 13. Command reference

Every command has been run from the repository root.

| Purpose | Windows | Linux / macOS |
|---|---|---|
| Mirror pristine source and apply `Shulker/` | `.\gradlew.bat applyPatches` | `./gradlew applyPatches` |
| Turn `patched/` edits back into `Shulker/` | `.\gradlew.bat rebuildPatches` | `./gradlew rebuildPatches` |
| …naming the one new patch it creates | `.\gradlew.bat rebuildPatches "-PpatchName=Improve Helper Logging"` | `./gradlew rebuildPatches "-PpatchName=Improve Helper Logging"` |
| Discard `patched/`, `classes/`, `resources/` | `.\gradlew.bat cleanVeltisPatches` | `./gradlew cleanVeltisPatches` |
| Run every test | `.\gradlew.bat test` | `./gradlew test` |
| Clean build of every module | `.\gradlew.bat clean build` | `./gradlew clean build` |
| Full pipeline + distributable | `.\gradlew.bat buildVeltisMC` | `./gradlew buildVeltisMC` |
| Refresh the IDE model | `.\gradlew.bat idea` | `./gradlew idea` |

`applyPatches` and `rebuildPatches` are aliases. The pipeline's own task names
are `applyVeltisPatches` and `rebuildVeltisPatches`; both spellings run the same
task, so nothing is duplicated and nothing can drift. CI, the README and the
engine's log lines use the long names, and either works everywhere above.

The Minecraft version and the patch worker count are build properties in
`gradle.properties`:

```properties
minecraftVersion=26.3
patchWorkers=4
```

Bumping `minecraftVersion` re-runs the whole chain against the new version; no
URL is ever hardcoded. Note that patches are authored against a specific
decompile, so a version bump usually means the patch set needs updating.

## 14. Troubleshooting

**`A patch file was deleted after this workspace was last patched`**
You deleted a patch and rebuilt without re-applying first. Run `applyPatches`,
then `rebuildPatches`. See [§10](#10-deleting-a-patch).

**`-PpatchName="…" cannot name this rebuild`**
The rebuild created more than one new patch, so there is no single file for the
name. Rebuild without the property, then rename the file by hand; or narrow the
edit so only one new file appears. Nothing was written.

**`A changed file cannot be expressed as a text patch`**
A file that is not valid UTF-8 differs from its pristine counterpart — you edited
a binary under `patched/`. Restore it from `source/`. Unified diffs are
line-oriented, so there is no way to record that change.

**`Failed to apply patch: <name>`**
The patch itself does not apply. The message names the category, target, hunk and
reason, plus the patch's SHA-256. Usually the decompile moved — the source changed
underneath the patch. Re-run `applyPatches` to get a clean baseline and check
whether the file changed upstream.

**The rebuild reports `0 regenerated` but you expected changes**
Either the edit was made in `source/` instead of `patched/`, or `applyPatches`
was run after the edit and re-mirrored the baseline over it. Check which file you
opened — the IDE source root is `patched/`.

**`net.minecraft.*` does not resolve in the IDE**
The workspace does not exist yet. Run `./gradlew prepareMinecraft`, then
`./gradlew idea` (or just build) and re-import.

**A build failed part-way through**
Every step writes a fixed location, so rerunning resumes rather than restarting.
`./gradlew cleanVeltisPatches` throws away the patched workspace only; `./gradlew
clean` throws away all build output — including the Minecraft download and the
decompile under `build/minecraft/`. Rerunning re-fetches them unless the Gradle
build cache still holds them, and every artifact is SHA-1 verified against
Mojang's metadata either way.

## 15. Rules that keep the distributable clean

The distributable is one file, `build/distributions/veltismc.jar`, plus the root
`libraries/` tree of runtime jars. Several rules keep it that way. They apply to
any change you make to the build.

- **Prefer JDK APIs.** Reach for a library only when the JDK genuinely has no
  answer, and say so in the pull request.
- **Classify any dependency you add** as one of `REQUIRED_RUNTIME`,
  `MINECRAFT_RUNTIME`, `BUILD_ONLY`, `DEVELOPMENT_ONLY` or `TEST_ONLY`, and
  record which component needs it. `BUILD_ONLY` and `TEST_ONLY` must stay out of
  the shipped jar.
- **Never shade or bundle a third-party jar into the distributable.** Log4j,
  Gson, SnakeYAML, ASM, JNA, JOML, Vineflower and the decompiler toolchain belong
  in `libraries/` or in the build classpath — not copied into the jar. Do not
  introduce an uber-jar, a nested `*.jar`, or a shaded package.
- **Do not duplicate what Minecraft already provides.** A library Mojang ships in
  `libraries/` is already on the server's classpath at run time.
- **The decompiler, `javac`, test classes, sources and Gradle metadata do not
  ship.** They are development tooling; the runtime never touches them.
- **`Shulker/` is the single source of truth** for changing Minecraft. Nothing is
  bundled for run-time source application.

`.gitignore` blocks `*.jar` except the Gradle wrapper, so a bundled jar cannot be
committed by accident.

### The dependency audit

Every third-party dependency declared anywhere in this build, classified. The
five `REQUIRED_RUNTIME` rows are the entire bootstrap: they are what the
distributable's manifest `Class-Path` names, fetched and SHA-1-verified into the
`libraries/` tree beside the jar by stage 0 — and never copied into the jar
itself.

| Dependency | Version | Needed by | Class | Where it lives |
|---|---|---|---|---|
| `org.apache.logging.log4j:log4j-api` | 2.26.0 | `:patch-engine` (`api()`), `:server` | `REQUIRED_RUNTIME` | `libraries/` — Mojang publishes this exact coordinate, so it is fetched from `libraries.minecraft.net` and cross-checked against Mojang's manifest rather than duplicated |
| `org.apache.logging.log4j:log4j-core` | 2.26.0 | `:patch-engine` (`api()`), `:launcher` | `REQUIRED_RUNTIME` | same as above |
| `com.google.code.gson:gson` | 2.14.0 | `:patch-engine`, `:server` | `REQUIRED_RUNTIME` | same as above — Mojang's own coordinate |
| `org.apache.logging.log4j:log4j-jul` | 2.26.0 | `:launcher`, `:server` | `REQUIRED_RUNTIME` | `libraries/` — Maven Central; Mojang ships `log4j-slf4j2-impl`, not the JUL bridge, so this one is VeltisMC's |
| `org.yaml:snakeyaml` | 2.4 | `:server` (`veltis.yml`) | `REQUIRED_RUNTIME` | `libraries/` — Maven Central; Mojang does not publish it |
| Mojang's libraries — 56 coordinates (brigadier, datafixerupper, authlib, ...) | per Mojang's version manifest | the server itself | `MINECRAFT_RUNTIME` | `libraries/`, each SHA-1-verified; the three rows above that Mojang also publishes are the *same files* fetched once, never two copies |
| `org.vineflower:vineflower` | 1.12.0 | `:patch-engine` (`compileOnly`, compiles `MinecraftDecompiler`), `:patch-engine` tests (`testRuntimeOnly`), root `pipelineClasspath` | `BUILD_ONLY` | nowhere. Deliberately **not** `implementation`: `implementation` is inherited by `:server` and `:launcher` `runtimeClasspath`, and those two are inputs to `uberJar` (which unpacks them) and to the bootstrap table — so declaring it there made the runtime depend on the packaging filter to stay clean rather than on the dependency graph. It now reaches only the compile classpath of the module that compiles the decompiler, that module's test runtime, and `pipelineClasspath`, the one classpath that actually decompiles. `verifyDistributableContent` fails the build if decompiler classes appear in the jar |
| `org.ow2.asm:asm` + `asm-commons` (and their `asm-tree`/`asm-analysis`/`asm-util` transitive modules, aligned by `resolutionStrategy.force` in the root script) | 9.10.1 | `:patch-engine` (access widening) | `BUILD_ONLY` | nowhere: build classpath only |
| Minecraft compile classpath — `server-classes.jar` plus the workspace's verified `libraries/*.jar` | 26.3 | `:server` compilation | `BUILD_ONLY` | `build/minecraft/26.3/` only; excluded from every artifact |
| IntelliJ IDEA project files and the `idea` Gradle plugin | — | contributor IDE | `DEVELOPMENT_ONLY` | gitignored; never part of a build product |
| `org.junit:junit-bom`, `org.junit.jupiter:junit-jupiter`, `org.junit.platform:junit-platform-launcher` | 5.12.1 | tests in all three modules | `TEST_ONLY` | test classpath only |
| `log4j-core`/`log4j-jul` as `testRuntimeOnly` | 2.26.0 (`:server`), 2.25.2 (`:patch-engine`) | test logging | `TEST_ONLY` | test classpath only; the odd 2.25.2 is test-only and ships nowhere |
| Gradle wrapper and the build's plugins (`java`, `java-library`, `application`, `idea`) | 9.5.1 | every build | `BUILD_ONLY` | build tooling, not shipped |
| *(anything else)* | — | — | `UNNECESSARY` | does not exist: no dependency in this build is unreachable, and one that no component can explain must be removed |

The three modules also depend on each other (`:launcher` and `:server` on
`:patch-engine`) — internal wiring, not a dependency to classify. The gate that
keeps this table true is `:launcher:verifyDistributableContent`, which runs
inside `build`, prints a six-line content report of the distributable (classes,
resources, nested JARs, development classes, test classes, third-party entries)
and fails the build on anything but the first two.

## 16. Opening a pull request

Branch from the latest main and keep the change focused.

Before opening it:

```powershell
.\gradlew.bat build buildVeltisMC
```

```bash
./gradlew build buildVeltisMC
```

Checklist:

- [ ] `git diff -- Shulker` shows only the patches you meant to change.
- [ ] `rebuildPatches` run twice is stable — the second reports `0 regenerated, 0
      created, 0 removed`.
- [ ] Numbering in every category is contiguous, starting at `001`.
- [ ] `applyPatches`, `clean build` and `buildVeltisMC` all succeed.
- [ ] Any patch-number reference in `README.md` or `CONTRIBUTING.md` still
      matches the new numbering (renumbering does not update prose).
- [ ] Any new dependency is classified under [§15](#15-rules-that-keep-the-distributable-clean).
- [ ] The pull request says what changed and why, which patch files were touched,
      the Minecraft version used, the commands you ran, and any limitations.

**Commit** patch files under `Shulker/`, module sources and tests, Gradle and
build-script changes the workflow needs, and documentation.

**Do not commit** `build/`, `.gradle/`, `Vanilla/`, `Veltis/`, `out/`, `run/`,
IDE metadata, compile logs, temporary patch files (`*.writing`, `*.widening`,
`*.building`), or server data (`eula.txt`, `server.properties`, `logs/`,
`config/`) — all gitignored.

Do not copy, fork or vendor Bukkit, Spigot, Paper or CraftBukkit source into this
project, and do not add their APIs. VeltisMC is a bare-NMS server; gameplay is
vanilla plus the patch set.
