# Build system — CMake and Gradle foundation

Scope: how `veltis-native` is configured, compiled, tested, packaged and verified,
and which decisions the build enforces rather than trusts. Every numbered claim
about compiler versions, artifact sizes and pass counts comes from a build
executed on this host (one Windows x86-64 machine — which is not a claim about a
Windows version floor, see `PLATFORM_SUPPORT.md` §8; CMake 3.31.6, Ninja 1.12.1,
MinGW-w64 GCC 16.2.0, LLVM-MinGW Clang 23.1.4) on 2026-11-03/04. Where a target
could not be executed here it is labelled **not tested**, using the labels defined in
`PLATFORM_SUPPORT.md` §1.

Line references are to `native/build.gradle.kts` and `native/CMakeLists.txt` as
they stand now.

---

## 1. Division of responsibility

| Layer | Owns | Does not own |
| --- | --- | --- |
| CMake (`CMakeLists.txt`, `CMakePresets.json`, `cmake/toolchains/*.cmake`) | translation flags, target properties, runtime policy per toolchain, export lists, the build-info metadata, CTest registration | which compiler a *later* Gradle invocation wanted (see §4) |
| Gradle (`build.gradle.kts`) | task graph, tool discovery, platform/build-type/toolchain selection, artifact packaging, manifests, checksums, archives, verification gates | any compile flag of its own |

The split is deliberate: the C ABI and the runtime policy are properties of the
library, so CMake derives them from the public header
([CMakeLists.txt:252-277](../CMakeLists.txt#L252-L277)) rather than from a list a
second build file could contradict. Gradle never restates an ABI fact it can read.

`java` plugin tasks (`compileJava`, `test`, `check`) and native tasks are only
connected when a caller asks (§3).

---

## 2. Gradle knobs

| Property | Default | Effect |
| --- | --- | --- |
| `-PveltisNativePlatform` | host key (`os-arch`) | target platform; also the output directory name, so lanes cannot overwrite each other |
| `-PveltisNativeBuildType` | `Release` | `CMAKE_BUILD_TYPE` |
| `-PveltisNativeToolchainFile` | unset | CMake toolchain file; presence alone marks the build cross (§6) |
| `-PveltisNativeGenerator` | see §5 | `-G` passed to CMake |
| `-PveltisNativeCc` / `-PveltisNativeCxx` | unset → `CC` / `CXX` | compiler selection (§4) |
| `-PveltisNativeStaticRuntime` | unset → per-toolchain default | `VELTIS_NATIVE_STATIC_RUNTIME`, normalised against CMake's own boolean spellings ([build.gradle.kts:106-116](../build.gradle.kts#L106-L116)) |
| `-PveltisNativeOsxArchitectures` / `-PveltisNativeOsxDeploymentTarget` | unset | macOS universal/arch and min-target pinning |
| `-PveltisNativeCmake` / `-PveltisNativeCtest` / `-PveltisNativeGit` | `cmake` / `ctest` / `git` on PATH | tool override (§3) |
| `-PveltisNativeTest=true` | off | `test` depends on `buildNative`, and `check` depends on `testNative` ([build.gradle.kts:671-680](../build.gradle.kts#L671-L680)) |

CMake-side inputs read from Gradle: `VELTIS_NATIVE_OUTPUT_DIR`,
`VELTIS_NATIVE_PLATFORM_KEY`, `VELTIS_NATIVE_BUILD_TESTS`,
`VELTIS_NATIVE_STATIC_RUNTIME`.

Warnings are `-Wall -Wextra -Wpedantic` (or `/W4` for MSVC) with no `-Werror`
([CMakeLists.txt:143-145](../CMakeLists.txt#L143-L145)); a new warning therefore
does not fail a lane. Turning that into a gate is a Phase 8 item, not a silent
assumption.

---

## 3. The Java-only rule

A standard Java build must not need CMake, Ninja, a compiler or git. Three
mechanisms hold that line, and all three were executed with none of those tools
on PATH:

1. **No native task is wired into `build`, `check` or `assemble` unless
   `-PveltisNativeTest=true` is passed.** `:veltis-native:build` ran with
   `cmake`, `ninja` and `gcc` all absent and succeeded.
2. **Tools are resolved at execution time.** `requireTool`
   ([build.gradle.kts:194-222](../build.gradle.kts#L194-L222)) is called inside
   `doFirst`, so *configuring* the project never touches a tool. Its order is
   Gradle property → environment variable → PATH, and its failure names the task
   that needs the tool and states that ordinary Java builds do not.
3. **The Java suite degrades, it does not fail.** Each of the seven
   `veltis.native.test.*` fixture properties is left unset when there is no
   library (`configureFixturePath`, [build.gradle.kts:584-598](../build.gradle.kts#L584-L598)),
   and the tests use `Assumptions`, so a missing native library is a skip, not an
   error.

| Invocation | Result on this host |
| --- | --- |
| `:veltis-native:build` (no toolchain on PATH) | passed; 0 native tasks ran |
| `:veltis-native:test --rerun-tasks --no-build-cache` (no toolchain on PATH) | executed: 81 tests, 0 failures, 1 skipped |
| `:veltis-native:test -Dveltis.native.test.expectJavaOnly=true` | 81 tests, 25 skipped, 0 failures — the leak guard asserts no fixture reached the JVM |

`-Dveltis.native.test.expectJavaOnly=true` is a *claim about absence*: it wins
over "a library happens to exist in this working tree" and makes any fixture
property an immediate test failure.

---

## 4. Compiler selection, and the cache that ignores it

Selection is available four ways, in the order a caller is likely to need them:

- `-PveltisNativeCc` / `-PveltisNativeCxx` — exported into the CMake configure
  process as `CC` / `CXX` ([build.gradle.kts:418-424](../build.gradle.kts#L418-L424));
- `CC` / `CXX` in the environment — what CI uses (`matrix.cc`, gradle.yml);
- `CMakePresets.json` — twelve configure presets (with matching build and test
  presets). Five pin `CMAKE_C_COMPILER` (`clang-cl`, `gcc`, `clang` on Windows,
  `gcc`/`clang` on Linux x86-64); the three cross presets select a
  `toolchainFile`; MSVC names the Visual Studio generator with an `x64`
  architecture; the macOS presets pin `CMAKE_OSX_ARCHITECTURES` and the
  deployment target; `linux-aarch64-native` deliberately uses the host compiler;
- a toolchain file (`-PveltisNativeToolchainFile`, or `toolchainFile` in a
  preset) — the only mechanism that also pins the target triple.

**None of these four is authoritative after the first configure.** CMake writes
the compiler it found into `CMakeCache.txt` and afterwards ignores `CC`, `CXX`
and a changed PATH; the reconfigure succeeds and rebuilds everything with the
cached tool. That is the failure this section exists for, because the artifact
check then reports the compiler that *was used* as if it were the one asked for.

Two rules follow:

- `compilerSelection` is declared as a task **input**, read at fingerprint time
  ([build.gradle.kts:372](../build.gradle.kts#L372)), so a changed request makes
  `configureNative` run again instead of sitting on a stale result;
- `requireCompilerMatchesCache`
  ([build.gradle.kts:309-323](../build.gradle.kts#L309-L323)) then compares the
  request with `CMAKE_C_COMPILER` / `CMAKE_CXX_COMPILER` in the cache and fails
  with the two values, the cache path and `cleanNative` as the remedy. It refuses
  rather than silently deleting a build tree.

The comparison is intentionally loose for a bare name (`gcc` matches
`/usr/bin/gcc`, `clang-cl` matches `…/bin/clang-cl.exe`) and strict for a path
(`D:\a\gcc.exe` must not match `C:\b\gcc.exe`) — `sameCompiler`,
[build.gradle.kts:283-297](../build.gradle.kts#L283-L297).

A toolchain file **exempts** the check (`nativeCompilerSelection` returns empty,
[build.gradle.kts:244-255](../build.gradle.kts#L244-L255)): the file sets the
compilers itself on every configure, so `CC` there names nothing the build uses,
and a shell default would fail the preserved Windows ARM64 and Linux AArch64
cross lanes for the wrong reason.

Executed on the GCC-configured cache (2026-11-04):

| Request | Expected | Result |
| --- | --- | --- |
| `-PveltisNativeCxx=clang++` | fail | failed: `CMAKE_CXX_COMPILER: cache holds '…/c++.exe', this build asks for 'clang++'` + `Fix: …cleanNative` |
| `CC=gcc CXX=c++` | pass | passed, reconfigured, lane verified (§8) |
| copied cache, no toolchain file | fail | failed identically |
| copied cache, `-PveltisNativeToolchainFile=cmake/toolchains/linux-aarch64.cmake` | skipped | guard silent; CMake proceeded to `project()` |

---

## 5. Generator selection

`effectiveNativeGenerator()` ([build.gradle.kts:179-183](../build.gradle.kts#L179-L183)):
an explicit `-PveltisNativeGenerator` wins; otherwise a Windows target that can
see Ninja asks for Ninja; otherwise nothing is passed and CMake chooses.

The middle rule exists because CMake's Windows default is NMake/Visual Studio,
so a MinGW or LLVM-MinGW lane dies inside `project()` with

```
Building for: NMake Makefiles
'nmake' '-?' failed with: no such file or directory
```

—a message naming no compiler that reads like a toolchain bug. Every CI lane
already names its generator, so this only changes what a manual local run does:
from confusing failure to build. An MSVC host without Ninja keeps CMake's choice.

Gradle never invokes Ninja; it asks CMake to, which is why Ninja has no
`requireTool` entry.

---

## 6. Cross-compilation is a different kind of build

`nativeCrossCompiling` ([build.gradle.kts:126](../build.gradle.kts#L126)) is true
when the platform key differs from the host **or** a toolchain file is supplied.
It changes three things, all to stop the build from lying:

- CTest is not registered (`VELTIS_NATIVE_BUILD_TESTS=OFF`), so a foreign binary
  is never executed;
- `testNative` refuses outright, naming both platforms
  ([build.gradle.kts:461-474](../build.gradle.kts#L461-L474));
- no fixture property is set, so the Java suite runs its fallback cases only.

Cross-compiled artifacts are compiled and inspected; per the order's rule, that
is *not* runtime verification (`PLATFORM_SUPPORT.md` §2 records what each
target actually reached).

---

## 7. Build directories

| Path | Contents |
| --- | --- |
| `build/native/<platform>/` | the library, `veltis-native-build-info.json`, `test-fixtures/` |
| `build/native/cmake/<platform>/` | the CMake tree: `CMakeCache.txt`, generated build files, CTest state |
| `build/native/dist/<platform>/` | staged library + manifest + `.sha256` for that lane |
| `build/native/dist/*.zip` | distribution and source archives (names carry platform and build type) |
| `build/native/source-check/` | throwaway extract-and-rebuild tree for archive verification |

Per-platform directories mean a Windows x86-64 build and a Windows ARM64 build
never overwrite each other, and archive names carry the build type, so a Debug
lane cannot be mistaken for a Release lane. One caveat is documented rather than
papered over: `build/native/<platform>/` holds **one** library file, so switching
`-PveltisNativeBuildType` replaces it — the last type built is the type on disk,
and both the manifest and the archive name say which.

`cleanNative` ([build.gradle.kts:491-497](../build.gradle.kts#L491-L497)) deletes
`build/native` and is what every error message in this module recommends; `clean`
depends on it.

---

## 8. Dependency and generated-file tracking

Four rules, each of which replaced a build that could report green about bytes
it did not produce.

1. **`buildNative` takes the CMake cache as an input**
   ([build.gradle.kts:442](../build.gradle.kts#L442)). Gradle cannot see a
   compiler swap, a toolchain file or `-PveltisNativeStaticRuntime=false` through
   the properties it declares; without the cache it reported the previous library
   UP-TO-DATE and every later check measured the wrong artifact (Phase 5 finding).
2. **`configureNative` declares no outputs**
   (comment at [build.gradle.kts:373-378](../build.gradle.kts#L373-L378)).
   Declaring `cmakeBuildDir` handed the tree to Gradle's stale-output cleanup:
   copying a cache from another platform's tree into it made Gradle delete the
   file before the task ran, because that content was already registered as a
   Gradle output elsewhere. CMake owns that directory; re-configuring is cheap and
   CMake itself no-ops, and the compiler check stays unconditional.
3. **The build-info JSON is a build byproduct, not a configure side effect.**
   `veltis_native_build_info`
   ([CMakeLists.txt:371-383](../CMakeLists.txt#L371-L383)) regenerates it beside
   the library on every build and `veltis_native` depends on it. As a configure
   artifact it could be pruned while the library survived, which broke
   `packageNative` (audit §5.6). Observed in the log as
   `[1/1] VeltisNative: refreshing veltis-native-build-info.json beside the library`.
4. **`test` refuses to verify a library older than the sources.**
   `nativeLibraryIsStale` compares the library's mtime with the newest native
   source
   ([build.gradle.kts:558-582](../build.gradle.kts#L558-L582)); when it is stale,
   `doFirst` blanks all seven fixture properties and logs a task-level warning
   naming the file and the rebuild command, and the verdict is declared as
   `inputs.property("nativeFixturesAreFresh")` with the native sources added to
   `inputs.files` ([build.gradle.kts:660-669](../build.gradle.kts#L660-L669)).
   Three details are load-bearing:
   - the decision is taken at fingerprint/execution time, not at configuration
     time, because `-PveltisNativeTest=true` makes `test` depend on `buildNative`
     — a configuration-time check judged the *previous* library and
     `verifyNativeRelease` then reported native cases that had not run;
   - the verdict is an *input* because a reproducible relink after a no-op edit
     leaves the DLL byte-identical, and content hashes alone would keep the task
     UP-TO-DATE on a result recorded under a different verdict;
   - only the real library is judged — a fake's mtime reflects its own sources,
     which say nothing about the premise of the run.

   Executed: a content edit to `src/cpp/algorithms.cpp` without a rebuild made
   `test` re-run, withhold every fixture, print the warning and skip the 25
   native cases visibly; `-PveltisNativeTest=true` rebuilt, flipped the verdict
   input and ran them.

---

## 9. Packaging, manifests and reproducibility

`packageNative` ([build.gradle.kts:749](../build.gradle.kts#L749)) wipes
`dist/<platform>`, copies the library, and writes a manifest whose fields come
from three independent places — the public header (ABI version, version, export
list), the CMake cache (compiler, triple, build type, static-runtime policy) and
git (`sourceCommit`, `sourceTreeState`) — plus a recomputed SHA-256.
`verifyNativeArtifact` ([build.gradle.kts:1155](../build.gradle.kts#L1155)) then
checks every claim against the bytes on disk, reading the PE header through
`NativeLoader` — the same code the server runs at startup — and the container
size against `VELTIS_NATIVE_EXPORT_COUNT`.

`packageNativeArchive` ([build.gradle.kts:810-829](../build.gradle.kts#L810-L829))
is a `Zip` with `isPreserveFileTimestamps = false` and
`isReproducibleFileOrder = true`. Measured: the Release zip re-hashed to
`c0244e46873077f89c66c9771a9ffe4a648af39f89fbd26af654e408ea379bd4` on separate
runs, identically. What is *not* reproducible: the library itself, because PE
build IDs and timestamps are compiler output — reproducibility here is about the
archive, not a bit-for-bit guarantee about the DLL.

---

## 10. Source archive

`packageNativeSourceArchive` ([build.gradle.kts:912](../build.gradle.kts#L912))
takes its file list from `git ls-files` (`trackedNativeSources`,
[build.gradle.kts:885](../build.gradle.kts#L885)), not from a pattern list, and
fails closed when the listing is empty or the index and disk disagree. Before
archiving it checks `untrackedNativeBuildInputs`
([build.gradle.kts:861-882](../build.gradle.kts#L861-L882)): a source file that
has not been `git add`ed would produce an archive that cannot compile, so that is
an error rather than a missing file in the release. The check compares the file
*extension* against `nativeBuildInputExtensions`; an earlier version called
`Regex.matches` on a `\.(cpp|…)$` pattern, and because `matches` anchors the whole
input that filter could only have matched a file literally named `.cpp` — a guard
that could never fire. Both directions are now executed:

| Case | Result |
| --- | --- |
| untracked `src/cpp/zz_probe_untracked.cpp` present | failed: "The source archive would be incomplete: these build inputs exist on disk but are not in the git index… `src/cpp/zz_probe_untracked.cpp`" |
| same file removed | passed, 37 tracked files |

`verifyNativeSourceArchive` ([build.gradle.kts:959](../build.gradle.kts#L959))
extracts the archive outside the working tree and builds it from scratch with a
different source and binary directory, so the check cannot be satisfied by the
tree it came from. It captures the child's combined output and prints it on
failure — the audit's §5.6 note that a swallowed CMake stderr cost a whole
session still holds as the reason for that code path.

Executed (2026-11-04): 37 tracked files, 59 zip entries, zero build output, and
the extracted tree built to
`build/native/source-check/cmake-build/out/veltis_native.dll`. The archive
contains no `native/build/**`, no CMake cache, no object files and no JARs — the
generated content of the supplied upload archive is exactly what this task exists
to exclude. Digest behaviour, measured: the same tree re-hashed to `a54160a9…`
from two different invocations (Debug and Release), and changed to `be669f5d…`
only after `native/build.gradle.kts` — itself an archived file — was edited.

---

## 11. Verification entry point

`verifyNativeRelease` ([build.gradle.kts:1516](../build.gradle.kts#L1516)) is one
command for a host lane: configure, build, CTest, Java FFM suite, export and
manifest checks, runtime-policy check, packaging, archives, source-archive
rebuild, then a JSON report. It is what CI calls, and the local runs below are
its output.

| Lane | Command | Result |
| --- | --- | --- |
| Release (GCC 16.2.0) | `:veltis-native:verifyNativeRelease -PveltisNativeTest=true` | passed — CTest 4/4; Java 81 tests / 0 failures / 1 skipped; DLL 295661 bytes, abi 1, 13 exports; imports `KERNEL32.dll`, `msvcrt.dll`, no compiler runtime; source archive rebuilt from scratch |
| Debug (same compiler) | `… -PveltisNativeBuildType=Debug -PveltisNativeTest=true` | passed — CTest 4/4; Java 81 / 0 / 1 skipped; DLL 334937 bytes, manifest `build Debug`, static-runtime policy still holds (`KERNEL32.dll`, `msvcrt.dll`, no compiler runtime); `veltis-native-windows-x86_64-debug.zip` = `244f3f6a…` |

Both lanes were re-run after every build-script change in this phase landed, and
the Release lane re-run last, immediately after the §10 archive-filter fix. The
Release zip re-hashed to `c0244e46…` on four separate runs, and the source archive
to `a54160a9…` from both the Debug and the Release invocation of the same tree —
compiler-independent content, identical digest — changing to `be669f5d…` only once
`native/build.gradle.kts` itself was edited.

`verifyNativeRuntimePolicy` reads the static-runtime decision from the cache, not
from the property, and shells out to `tools/check-pe-imports.ps1` — the single
authority for PE import policy, shared with CI (Phase 5 removed a duplicate
implementation of that rule).

---

## 12. Build matrix, as it stands today

Configurations exist for all ten targets the order names. Status is what has been
*executed*, not what is configured:

| Target | Compiler | Configuration | Status here |
| --- | --- | --- | --- |
| Windows x86-64 | MSVC | preset `windows-x86_64-msvc` | not tested (no Visual Studio on this host) |
| Windows x86-64 | clang-cl | preset `windows-x86_64-clang-cl` | not tested |
| Windows x86-64 | LLVM-MinGW Clang 23.1.4 | Gradle lane + preset | **native build and runtime tested** |
| Windows x86-64 | MinGW-w64 GCC 16.2.0 | Gradle lane | **native build and runtime tested** (Release and Debug) |
| Windows ARM64 | MSVC | — | not configured (needs an MSVC ARM64 toolchain) |
| Windows ARM64 | LLVM-MinGW cross | `cmake/toolchains/windows-arm64-mingw.cmake` | cross-compiled and artifact verified; never executed |
| Linux x86-64 | GCC / Clang | presets | not tested (no Linux host) |
| Linux ARM64 | GCC | preset + cross toolchain | not tested here; cross lane configured |
| macOS ARM64 / x86-64 | Apple Clang | presets | not tested (no macOS host) |

No CI run of these definitions has ever executed; that limitation is recorded in
`CI_SUPPORT_MATRIX.md` (Phase 10) rather than inferred from YAML.

---

## 13. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `cache holds '…', this build asks for '…'` | CMake reuses its cached compiler and ignores `CC`. Run `:veltis-native:cleanNative` and rebuild (§4). |
| `… predates the native sources in this tree, so no native fixture is offered` | The suite ran Java-only against a library older than the sources. Rebuild with `-PveltisNativeTest=true`; a run with this warning says nothing about current C/C++ code. |
| `testNative cannot run a cross-compiled build` | Correct behavior: the artifact is for another platform. Use `buildNative`, and test on a native runner (§6). |
| `cmake was not found on PATH … ordinary Java builds and :server do not need this tool` | A native task was requested without a toolchain. Install it, or pass `-PveltisNativeCmake=<path>`. If you meant a Java build, drop the native task. |
| `'nmake' '-?' failed with: no such file or directory` | Generator, not compiler. Let `effectiveNativeGenerator()` decide, or pass `-PveltisNativeGenerator=Ninja` (§5). |
| Native task list looks empty / a native warning never prints | In a Gradle Kotlin DSL script, `/*` **inside** a `/** … */` block comment opens a second nesting level, so a glob like `` `src/**` `` in prose silently comments out the rest of the file — and an unterminated comment is *not* a compile error in a script. Diagnose with `:veltis-native:tasks --all` or `--dry-run` on a native task; `:veltis-native:help` is not a valid probe because Gradle 9 does not evaluate project scripts for it. |
| `git` needed but the build is Java-only | Only the source archive and the manifest commit fields call git; both are native tasks. |
