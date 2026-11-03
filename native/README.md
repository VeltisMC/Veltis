# `veltis-native`

The optional native engine for VeltisMC: a small C/C++ shared library with a
stable C ABI, plus the Java Foreign Function & Memory (FFM) integration the
server uses to call it.

The native library is **never required**. The Java classes in this module
compile with every ordinary `./gradlew build`, `:server` depends only on those
classes, and the server starts and runs on the Java fallback when no compatible
library is installed. CMake and a C/C++ compiler are needed only for the
`native` tasks below.

## Layout

| Path | Role |
| --- | --- |
| `include/veltis/native/veltis_native.h` | The public C ABI. Every exported symbol is declared here. |
| `include/veltis/native/veltis_native.hpp` | Internal C++ surface. **Not** part of the ABI. |
| `src/c/native_utils.c` | C-only pieces: versions, status strings, thread-local last error. |
| `src/c/native_internal.h` | Bridge between the C utility layer and the C++ engine, deliberately unexported. |
| `src/cpp/algorithms.cpp` | The deterministic bulk transform. |
| `src/cpp/native_engine.cpp` | Engine state and the exported C ABI entry points. |
| `src/main/java/org/veltismc/nativeengine/` | FFM loader, configuration, status, lifecycle, Java fallback. |
| `src/test/java/` | Java unit tests and the real-library end-to-end test. |
| `tests/` | Native C++ tests, the incompatible-library fixtures and `export_check.c`. |
| `cmake/toolchains/` | Cross-compilation toolchain files for Windows ARM64, Linux AArch64 and Linux RISC-V 64. |
| `CMakePresets.json` | Host and cross presets; preset names are listed below. |

## Architecture

```
               server bootstrap
                      |
               NativeEngine (Java)  -----> Java fallback (identical output)
                      |
               NativeLoader (FFM)
                      |
        veltis_native_transform_i32, veltis_native_init, ...
                      |
             native_engine.cpp (C++)
                      |
               algorithms.cpp
```

Rules the module keeps:

- **One ABI.** Only the functions declared in `veltis_native.h` are exported.
  CMake sets hidden symbol visibility, and the C++ engine class is not exported.
- **No C++ across the boundary.** No exceptions, STL types, references or
  ownership transfer cross an exported function. Every entry point catches
  exceptions and reports an integer status.
- **No allocator contract.** The caller owns every buffer. The library only
  writes the exact output extent it is given.
- **Thread-safe, process-wide lifecycle.** `veltis_native_init()` and
  `veltis_native_shutdown()` are idempotent. The last-error string is
  thread-local and valid until the next call on that thread.
- **Additive versioning.** `VELTIS_NATIVE_ABI_VERSION` changes only for a
  binary-incompatible change. The Java side requires an exact ABI, engine major
  `1`, and accepts an equal or newer minor.

### The implemented operation

`veltis_native_transform_i32` is a real, deterministic bulk transform:

```
output[i] = (uint32_t)input[i] * 31u + (uint32_t)salt + i    (mod 2^32)
```

The unsigned arithmetic makes overflow defined. `NativeEngine.javaTransform`
uses Java's two's-complement `int` arithmetic, which produces the same bits, so
the end-to-end test can assert native and fallback results are identical for
every input, including `Integer.MIN_VALUE` and `Integer.MAX_VALUE`.

Capabilities are reported by the library, not assumed by Java:

| Capability | Bit |
| --- | --- |
| `VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32` | `1 << 0` |
| `VELTIS_NATIVE_CAPABILITY_SELF_TEST` | `1 << 1` |

## ABI and buffer contract

- **Byte order is explicit.** No multibyte value is serialized across this ABI.
  Buffers are ordinary arrays of `int32_t` in the caller's address space and
  every result is a numeric `int32` value, so little-endian and big-endian hosts
  produce the same numbers. The Java FFM layer uses the host's native byte order
  on both sides of the call.
- **Fixed widths.** The header requires `CHAR_BIT == 8` and exactly 32-bit
  `int32_t`/`uint32_t` (plus 64-bit `uint64_t`) at compile time; a toolchain
  that violates either fails to compile rather than changing the ABI silently.
- **Lengths are element counts, not bytes.** `size_t` is used so a 32-bit C
  consumer still compiles, but only 64-bit platforms are supported releases.
- **Calling convention is explicit.** `VELTIS_NATIVE_CALL` is `__cdecl` on
  32-bit Windows and empty elsewhere, so a foreign consumer cannot select a
  different convention by accident.
- **Alignment and overlap are rejected, not undefined.** Pointers must be
  naturally aligned for `int32_t`, input and output must not overlap, and a
  length whose byte extent would overflow `size_t` is rejected with
  `VELTIS_NATIVE_ERROR_INVALID_ARGUMENT`. A zero-length call returns
  `VELTIS_NATIVE_OK` before any pointer check.
- **No unaligned dereference.** The implementation uses `memcpy`/byte-safe
  access for the byte-order-neutral patterns and never reinterprets an
  arbitrarily aligned address.
- **13 exports exactly.** `native_export_check` loads the built library with
  `LoadLibrary`/`dlopen`, resolves all 13 public names, and proves the internal
  `veltis_native_set_last_error`/`veltis_native_clear_last_error` bridge symbols
  are not visible. The check also verifies the artifact's binary format matches
  the host (PE, ELF or Mach-O) so a mislabelled cross-build cannot pass.

## Prerequisites

For the Java side, nothing beyond the repository's normal JDK requirement.

For the native side:

- CMake 3.21 or newer (CTest ships with it)
- A C11/C++20 compiler:
  - Windows x86-64: Visual Studio 2022 (MSVC), clang-cl, MinGW-w64 GCC or LLVM-MinGW
  - Windows ARM64: MSVC or an ARM64 LLVM-MinGW cross toolchain
  - Linux x86-64/AArch64: GCC or Clang
  - macOS: Apple Clang (Xcode command-line tools)
- A generator: Ninja is recommended; Makefiles and Visual Studio work too

If a tool is not on `PATH`, name it explicitly:

```bash
./gradlew :veltis-native:buildNative -PveltisNativeCmake=/path/to/cmake
```

The corresponding environment variables `CMAKE` and `CTEST` are also accepted.
Only the native tasks look for these tools; configuration and every Java task
do not.

## Building and testing

```bash
# Configure, compile and link; output lands in build/native/<platform>/
./gradlew :veltis-native:buildNative

# Run the C/C++ tests and the export/binary-format check through CTest
./gradlew :veltis-native:testNative

# Run Java unit tests and, when a library exists, the real end-to-end test
./gradlew :veltis-native:test

# Do all of it, in the right order
./gradlew :veltis-native:check -PveltisNativeTest=true

# Compare the Java fallback, an allocation-free Java baseline and the native
# engine across batch sizes (measurement tool, not a test)
./gradlew :veltis-native:benchmarkNative

# Remove this module's native build tree
./gradlew :veltis-native:cleanNative
```

`buildNative` is deliberately **not** wired into the normal `build` graph.
`-PveltisNativeTest=true` is the opt-in that makes the Java test task build the
native library first and adds `testNative` to `check`. Without it, the
end-to-end test skips when no library has been built, so Java-only CI keeps
working with no compiler installed.

An explicit empty `-Dveltis.native.test.*=` value disables that fixture, which is
how CI runs the Java-only fallback suite. When the property is absent and the
build is not cross-compiled, the Gradle build points the tests at the host
library it produced.

Native artifacts:

```
veltis-native/build/native/<platform-key>/veltis_native.dll          (Windows)
veltis-native/build/native/<platform-key>/libveltis_native.so        (Linux)
veltis-native/build/native/<platform-key>/libveltis_native.dylib     (macOS)
veltis-native/build/native/<platform-key>/test-fixtures/             (incompatible fixtures)
```

`<platform-key>` is `windows-x86_64`, `windows-aarch64`, `linux-x86_64`,
`linux-aarch64`, `macos-x86_64` or `macos-aarch64` (see the support table).

### Manual CMake

The Gradle tasks are a thin wrapper; a direct CMake build is equivalent:

```bash
cmake -S veltis-native -B veltis-native/build/native/cmake/manual \
  -DCMAKE_BUILD_TYPE=Release \
  -DVELTIS_NATIVE_OUTPUT_DIR="$PWD/veltis-native/build/native/linux-x86_64"
cmake --build veltis-native/build/native/cmake/manual --parallel
ctest --test-dir veltis-native/build/native/cmake/manual --output-on-failure
```

`CMakePresets.json` defines these presets:

| Preset | Kind |
| --- | --- |
| `windows-x86_64-msvc` | Native; Visual Studio 2022 generator |
| `windows-x86_64-clang-cl` | Native; Ninja with clang-cl |
| `windows-x86_64-mingw-gcc` | Native; Ninja with MinGW-w64 GCC |
| `windows-x86_64-llvm-mingw` | Native; Ninja with LLVM-MinGW |
| `windows-arm64-mingw-cross` | **Compile-only** cross build |
| `linux-x86_64-gcc` | Native |
| `linux-x86_64-clang` | Native |
| `linux-aarch64-native` | Native AArch64 |
| `linux-aarch64-cross` | **Compile-only** cross build |
| `linux-riscv64-cross` | **Compile-only** investigation target |
| `macos-aarch64` | Native Apple Silicon |
| `macos-x86_64` | Native/Intel or Rosetta |

```bash
cmake --preset linux-x86_64-gcc
cmake --build --preset linux-x86_64-gcc --parallel
ctest --preset linux-x86_64-gcc --output-on-failure
```

### Cross-compilation

Cross builds compile and link the target library, then stop. CTest, the Java FFM
integration tests and the benchmark are **not** run because the producing host
cannot execute the artifact. The Gradle tasks refuse to run tests against a cross
build instead of reporting a misleading failure.

```bash
# Windows ARM64 with LLVM-MinGW (quote the property on PowerShell)
./gradlew :veltis-native:buildNative \
  "-PveltisNativePlatform=windows-aarch64" \
  "-PveltisNativeToolchainFile=cmake/toolchains/windows-arm64-mingw.cmake" \
  "-PveltisNativeGenerator=Ninja"

# Linux AArch64 with a GNU cross toolchain
cmake --preset linux-aarch64-cross
cmake --build --preset linux-aarch64-cross --parallel

# Linux RISC-V 64 (Tier 2 investigation; not a support claim)
cmake --preset linux-riscv64-cross
cmake --build --preset linux-riscv64-cross --parallel
```

Toolchain files honour `VELTIS_ARM64_CC`/`VELTIS_ARM64_CXX` (Windows ARM64) and
`VELTIS_CROSS_PREFIX` (Linux). macOS multi-arch selection uses
`-PveltisNativeOsxArchitectures=arm64|x86_64`; Windows static runtime selection
uses `-PveltisNativeStaticRuntime=true|false` (default is on for MinGW and off
elsewhere).

### What the status words mean

- **Configured** — CMake's configure step completed for the target.
- **Compiled** — every C/C++ object for the target was produced.
- **Linked** — the target shared library was produced.
- **Executed** — the CTest suite (`native_tests` + `native_export_check`) ran on
  the target OS/CPU, natively or in a full-system emulator.
- **Integration-tested** — the Java FFM suite and the server
  `NativeBootstrapTest` ran against the real library on that target.

The verification report uses these statuses:

- **Executed and integration-tested** — CTest and the Java suites ran on the
  target itself.
- **Built and linked** — native host build produced and inspected, execution is
  CI-gated but not yet green on a runner.
- **Cross-compiled only** — artifact produced on a foreign host, inspected, never
  executed.
- **Not tested** — defined but not run.
- **Unsupported** — deliberately not a target.

Compiling or linking is not evidence of support. Only a row with Executed and
Integration-tested is listed as verified, and a cross-build is never promoted.

## Supported platforms

Only one target has been executed and integration-tested **by this repository
so far**. The other Tier 1 entries have CI jobs defined, but the jobs have not
yet produced a green run on a real runner, so they are deliberately marked
unverified. This table is the authority; a preset or CI job is not a support
claim.

| Target | Toolchain | Configured | Compiled | Linked | Executed | Integration | Status |
| --- | --- | :---: | :---: | :---: | :---: | :---: | --- |
| Windows x86-64 | LLVM-MinGW Clang 23.1.4, Ninja | ✅ | ✅ | ✅ | ✅ | ✅ | **Verified** (2026-10-31) |
| Windows x86-64 | MSVC, clang-cl, MinGW-w64 GCC | — | — | — | — | — | CI defined, not yet run |
| Windows ARM64 | LLVM-MinGW cross | ✅ | ✅ | ✅ | — | — | **Cross-compiled only** |
| Windows ARM64 | MSVC (native `windows-11-arm` runner) | — | — | — | — | — | CI defined, blocking |
| Linux x86-64 | GCC, Clang | — | — | — | — | — | CI defined, not yet run |
| Linux AArch64 | GCC (native runner) | — | — | — | — | — | CI defined, not yet run |
| Linux AArch64 | GNU cross | — | — | — | — | — | Preset defined, not run here |
| macOS Apple Silicon | Apple Clang | — | — | — | — | — | CI defined, not yet run |
| macOS Intel x86-64 | Apple Clang | — | — | — | — | — | CI defined, not yet run |
| FreeBSD x86-64/ARM64 | — | — | — | — | — | — | **Not supported**; investigation job only |
| Linux RISC-V 64 | GNU cross | — | — | — | — | — | **Not supported**; compile-only preset |

The verified row was produced on Windows 11 x86-64 with JDK 25, CMake 4.4.5 and
Ninja. The same commit was cross-compiled locally for Windows ARM64 (COFF ARM64,
13 exports) but the ARM64 DLL was never executed. No Linux, macOS, FreeBSD or
RISC-V binary was built or run during that verification, because the local
machine has no such toolchain, runner or emulator.

The loader recognizes the six Tier 1 keys independently of this table so a
future build can ship without a Java change. FreeBSD, the other BSDs and RISC-V
report an explicit "unsupported platform" status and keep the Java fallback.

## What the verified Windows x86-64 run covers

- **CTest, 2/2 passed:** `veltis_native_tests` and `veltis_native_exports`.
- **Java module suite, 61 tests with 0 failures and 1 skipped** against the
  real DLL (`:veltis-native:check -PveltisNativeTest=true`). The single skip is
  `javaOnlySuiteHasNoNativeFixtureConfigured`, which only asserts in the
  Java-only configuration.
- **Java-only suite, 61 tests with 24 skipped and 0 failures**
  (`:veltis-native:test -Dveltis.native.test.expectJavaOnly=true`). The flag
  makes Gradle detach every fixture path *and* makes the suite assert that no
  fixture leaked into the JVM, so "Java-only" is checked rather than assumed.
- **Server bootstrap, 1/1 passed with the library** (`NativeBootstrapTest`
  asserts native availability and release on stop) and **1/1 passed without it**
  (asserts the Java fallback and post-stop state).
- **Debug configuration, CTest 2/2 and 61 tests with 0 failures and 1 skipped**
  with `-PveltisNativeBuildType=Debug`, then restored to Release.
- **Binary inspection:** PE x86-64, 504,320 bytes, exactly 13 exported names and
  no internal exports; imports are limited to `KERNEL32.dll` and the UCRT
  `api-ms-win-crt-*` set (`tools/check-pe-imports.ps1`).
- **Reproducible bytes:** three clean Release builds from different build
  directories produced the same SHA-256
  (`1442c8ea095f29747f60132ea37aef95e4b6c21df9169a6ced6eedbb12aaafb0`), and
  repackaging produced an identical archive hash.

The C/C++ suite covers:

- null/zero-length matrix, output shorter than input, unequal lengths and
  negative/overflowing sizes;
- misaligned pointers, overlapping input/output and byte-extent overflow, each
  returning `VELTIS_NATIVE_ERROR_INVALID_ARGUMENT` with a populated last error;
- error clearing after a successful call and thread-local isolation between
  callers;
- byte-order-neutral patterns, 1 MiB transforms and boundary element counts;
- 100 init/shutdown cycles, concurrent init/shutdown/transform stress, and
  shutdown while transforms are running;
- the export/binary-format check described above.

The Java suite covers:

- the loader against deliberately broken fixtures: wrong ABI, newer major,
  missing transform export, failing initialization, failing transform, a
  minimal library with no version symbols, a corrupted file and a truncated
  file;
- PE/ELF/Mach-O and universal/fat header probing, wrong-architecture and
  wrong-format diagnostics generated before the OS loader is asked to map the
  file;
- OS/architecture normalization (including `Darwin` not being mistaken for
  Windows), filename selection by OS only, and unsupported-platform fallback;
- the real library through `NativeEngine`: repeated init/shutdown cycles,
  post-shutdown fallback, 1 MiB batches, 8 concurrent callers, close() during
  active calls, and the Java/native equivalence check;
- the server startup/shutdown path with and without the library.

User-visible status is honest about the distinction: a library that was never
found reports `loaded=false`; one that was found and rejected reports
`loaded=true` with the reason, because those are different operator problems.

## CI coverage

`.github/workflows/gradle.yml` has four native jobs:

- `native-host` — a matrix of Windows x86-64 (MSVC, clang-cl, LLVM-MinGW),
  Windows ARM64 (MSVC), Linux x86-64 (GCC and Clang), Linux AArch64 (GCC) and
  macOS (Apple Silicon and Intel). Each entry runs
  `:veltis-native:verifyNativeRelease -PveltisNativeTest=true`, which configures,
  builds, runs CTest and the export check, runs the Java FFM suite, packages the
  artifact and writes a verification report. It then inspects dependencies on
  that OS (PE imports via `tools/check-pe-imports.ps1` on Windows, `DT_NEEDED`
  plus the recorded glibc/libstdc++ requirements and an RPATH check on Linux,
  slices/deployment target/`otool -L` on macOS), runs the Java-only suite with
  `expectJavaOnly`, runs the server bootstrap test with and without the library,
  and uploads `build/native/dist/<platform>` as `veltis-native-<platform>-<toolchain>`.
- `native-cross` — compile-only jobs for Windows ARM64, Linux AArch64 and Linux
  RISC-V. Each verifies binary format, machine, the exact 13 exports against the
  public header, and dependencies, then proves the artifact is never executed:
  no CTest tests are registered, and Gradle's `testNative` guard must refuse to
  run. Windows ARM64 and Linux AArch64 are **blocking**; only the Tier 2 RISC-V
  investigation is allowed to fail.
- `native-debug` — Windows x86-64 MSVC and Linux x86-64 GCC in the Debug
  configuration, because Release alone can hide assertion-sensitive defects.
- `native-freebsd-investigation` — non-blocking FreeBSD VM build used to find
  portability defects. It is not a support claim.

There is no job-level `continue-on-error` on `native-host`; every Tier 1 entry
is a required check. Failures are never hidden behind an optional matrix value.

The jobs have been written and reviewed but **have not yet run on the commit in
question**. Treat every row above without a ✅ as unverified until its job is
green.

## Packaging a native artifact

```bash
./gradlew :veltis-native:packageNative          # library + manifest + SHA256SUMS
./gradlew :veltis-native:packageNativeArchive   # reproducible zip + .sha256
```

Output is organized per platform key under `build/native/dist/`:

```
dist/windows-x86_64/veltis_native.dll
dist/windows-x86_64/veltis-native-manifest.json
dist/windows-x86_64/SHA256SUMS
dist/veltis-native-windows-x86_64-release.zip
dist/veltis-native-windows-x86_64-release.zip.sha256
dist/verification-report-windows-x86_64.json   (from verifyNativeRelease)
```

The manifest is generated from the public header by CMake
(`veltis-native-build-info.json`) and merged by Gradle, so the recorded ABI
version, native version and export list cannot drift from the code. It carries:

| Field | Source |
| --- | --- |
| `abiVersion`, `version`, `exportCount`, `exports` | parsed from `veltis_native.h` at configure time |
| `platform`, `os`, `architecture`, `pointerBits` | CMake system variables / Gradle platform key |
| `compiler.c/cxx.id`, `compiler.c/cxx.version`, `compiler.targetTriple` | CMake compiler detection |
| `runtime.staticRuntime`, `runtime.minimumOsVersion` | CMake options / `CMAKE_OSX_DEPLOYMENT_TARGET` |
| `sha256`, `sizeBytes`, `buildType` | this packaging invocation |

Nothing from the toolchain or the operating system is bundled: no compiler
runtime, no `libstdc++`, no system dylib. `SHA256SUMS` covers both the library
and the manifest, so a edited manifest is detectable before it is trusted.

The build is byte-reproducible: PE link timestamps (`/Brepro`,
`--no-insert-timestamp`) and Mach-O UUIDs (`-no_uuid`) are suppressed, and
archive entries are sorted with fixed timestamps. Verified locally: three clean
Release builds and two packaging runs produced identical hashes.

No universal macOS binary is produced: it would require building and validating
both slices on one host, and `lipo -archs` in CI asserts the artifact contains
exactly one architecture.

## Release verification

`./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true` performs
the module-scoped half of the checklist: configure, Release build, CTest, export
check, Java FFM integration, packaging, checksums and a report recording the
source commit, whether the checkout was clean, the toolchain, and the archive
hash. It refuses to run against a cross-compiled artifact or without
`-PveltisNativeTest=true`.

The full release procedure, in order, on a **clean checkout**:

1. `git status --porcelain` must be empty; record `git rev-parse HEAD`.
2. `./gradlew :veltis-native:cleanNative`
3. `./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true`
   — build, CTest, export check, Java integration, package, checksums, report.
4. `./gradlew :veltis-native:test "-Dveltis.native.test.expectJavaOnly=true"`
   — the Java-only fallback suite with no fixture.
5. Dependency inspection: `tools/check-pe-imports.ps1` on Windows; `readelf -d`
   plus recorded glibc/libstdc++ requirements on Linux; `lipo -archs`,
   `vtool -show-build` and `otool -L` on macOS.
6. `./gradlew -Dveltis.native.path=<library> :server:cleanTest :server:test
   --tests org.veltismc.runtime.NativeBootstrapTest` — bootstrap and shutdown
   with the library, then again with an empty path for the Java-only path.
7. Confirm `SHA256SUMS` against the produced files.
8. Confirm `verification-report-<platform>.json`: `commit`, `treeState=clean`,
   `librarySha256`, `archiveSha256`.
9. On every release target, run steps 3–6 on that target's runner (or confirm
   the corresponding `native-host` CI run is green for the same commit).
10. Do **not** tag or publish without explicit authorization; the report is the
    evidence, not the release.

Toolchain versions used by the recorded run: CMake 4.4.5, Ninja (bundled),
LLVM-MinGW Clang 23.1.4 (`x86_64-w64-mingw32`), Temurin JDK 25 locally and
JDK 26 in CI.

## Implementation status

Implemented and exercised:

- the ABI and version/status diagnostics in `veltis_native.h`;
- the process-wide `init`/`shutdown` lifecycle and capability query;
- `veltis_native_transform_i32`, with an exactly equivalent Java fallback;
- the FFM loader, configuration overlay, status reporting and fallback policy;
- startup integration in `VeltisBootstrap` and release on server stop;
- the CTest suite, export check, Java unit/integration suite and benchmark
  harness.

Deliberately **not** implemented:

- any operation other than the bulk `transform_i32` primitive;
- compression, chunk encoding, lighting, entity or scheduler acceleration;
- automatic installation or download of a native library by the launcher;
- a JNI compatibility layer (the integration is FFM-only, JDK 22+).

## Installing a library

The loader looks for `<platform>/<library>` in this order:

1. `veltis.native.path`, when set — either the shared library file itself or a
   directory containing it.
2. `<server home>/native/<platform-key>/<library>`
3. `<working directory>/native/<platform-key>/<library>`
4. `<working directory>/build/native/<platform-key>/<library>`

For example, on Linux:

```bash
mkdir -p /srv/veltis/native/linux-x86_64
cp veltis-native/build/native/linux-x86_64/libveltis_native.so \
   /srv/veltis/native/linux-x86_64/
```

On Windows, copy `veltis_native.dll` to
`<server home>\native\windows-x86_64\`. On macOS the file is
`libveltis_native.dylib` under `macos-aarch64` or `macos-x86_64`.

The launcher jar does **not** package a native library. An operator chooses
whether to install one, and the version/ABI check in the loader is what decides
whether it can be used.

## Configuration

Settings are read from `<home>/server.properties` and overlaid with
`-Dveltis.native.*` system properties, so a permanent value and a one-run
override use the same names:

```properties
# Master switch. false means no library is looked for at all.
veltis.native.enabled=true
# Load during startup. false leaves the engine disabled until NativeEngine.load().
veltis.native.autoload=true
# Use the Java implementation when native support is unavailable or fails.
veltis.native.fallback=true
# Cheap invocation counters for diagnostics.
veltis.native.stats=false
# Optional file or directory. Empty means use the search order above.
veltis.native.path=
```

On Linux, `-D` overrides look like:

```bash
java -Dveltis.native.path=/srv/veltis/native/linux-x86_64/libveltis_native.so -jar veltis.jar
```

## Behaviour and fallback

- **Disabled:** `enabled=false` reports `NativeStatus.disabled` and every
  operation uses Java.
- **Missing:** no library in any location reports `loaded=false` and Java is
  used. This is normal on a fresh install and is logged at `INFO`, not as an
  error.
- **Unsupported platform:** the loader reports the exact platform key, lists
  the recognized set and keeps the Java fallback. It does not attempt to load a
  foreign binary.
- **Found but incompatible:** an ABI mismatch, an engine major mismatch, a
  missing export or an initialization failure reports `loaded=true` with a
  reason. The loader closes the library, logs a warning during startup, and
  leaves the server on Java.
- **Fallback disabled:** with `veltis.native.fallback=false`, an operation that
  cannot run natively throws `IllegalStateException` instead of silently
  switching implementations. Use this when an operator needs to prove native
  execution.
- **Repeated lifecycle:** `initialize`/`shutdown` are idempotent and safe to call
  in any order. A failed load never leaves a half-open library behind.
- **Close during calls:** a loaded library takes a read lock for every native
  call; `close()` takes the write lock, so it waits for in-flight calls before
  shutting the engine down and releasing the FFM arena. Later calls throw
  `IllegalStateException`.

The server bootstrap reads this configuration during startup, logs a single
status line, and calls `shutdown` during server stop. Nothing in the startup
path fails the server if native support is unavailable.

## Benchmark methodology and results

`NativeBenchmark` is a measurement tool, not a test. It runs through
`:veltis-native:benchmarkNative`, which builds the native library first and then
executes in a normal JVM:

```bash
./gradlew :veltis-native:benchmarkNative
```

It compares four implementations on deterministic inputs (an xorshift-seeded
`int[]`, salt `-987654321`):

| Name | What it measures |
| --- | --- |
| `java` | `NativeEngine.javaTransform`: allocates the result array, exactly like the server's fallback. |
| `java-optimized` | The same arithmetic writing into a caller-supplied array: the fastest shape pure Java can use. |
| `native-engine` | The production integration path: confined arena, copy in, downcall, copy out. |
| `native-reusable` | One shared arena with pre-allocated segments, so only compute and the two mandatory copies are measured. |

Each batch size is warmed up, then measured for three rounds of timed calls.
Every result is checked against `javaTransform` before it is timed, so a wrong
implementation fails instead of producing a fast number. Percentiles are over
individual calls and therefore include timer overhead, which the harness
measures and prints separately (34.6 ns per `System.nanoTime` call on the
reference host; two calls per sample). Heap allocation comes from
`ThreadMXBean.getCurrentThreadAllocatedBytes`; heap use from `MemoryMXBean`;
committed virtual memory from the operating-system MXBean. Sizes run from 1 to
1,048,576 elements.

Reference run (Windows 11 x86-64, 20 logical processors, JDK 25.0.5.0.3,
HotSpot 64-bit, library built with LLVM-MinGW/Clang 23 in Release):

| Batch | `java` | `java-optimized` | `native-engine` | `native-reusable` |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 31.9 | 17.8 | 4.6 | 16.0 |
| 16 | 483.2 | 665.3 | 86.3 | 254.2 |
| 256 | 1756.2 | 1872.9 | 922.4 | **2919.2** |
| 1,024 | 1508.5 | 2094.0 | 1428.2 | **5487.1** |
| 4,096 | 875.6 | 2185.3 | 879.1 | **3639.9** |
| 65,536 | 1294.9 | 2076.9 | 1394.1 | **4464.8** |
| 262,144 | 1330.5 | 2067.2 | 408.1 | 1972.9 |
| 1,048,576 | 1139.9 | **1988.7** | 373.0 | 1829.9 |

M elements/s, higher is better. Bold marks the best row value.

What the numbers say:

- The **production native path never beat the allocation-free Java baseline at
  any batch size**, so the harness reports "threshold: not measured" rather than
  inventing one. At 1,024 elements the two are close; beyond that the copies and
  per-call arena work dominate.
- The **reusable native path** wins from roughly 256 to 65,536 elements, then
  loses again at 262,144 and above, where a tight Java loop streams through
  cache better than a copy-heavy native call. That is the only shape worth
  considering for a future integration: caller-owned, reused segments and a
  batch in the hundreds-to-tens-of-thousands range.
- The FFM transition itself is cheap: a trivial downcall
  (`veltis_native_capabilities`) measured **41.6 ns**. The cost is the two
  memory copies and the per-call allocation, not the call.
- `java-optimized` allocations were zero by construction; `native-engine`
  allocated about 8 bytes per element for its two segments plus the result
  array. In total the run allocated 2,762.8 MiB across all measured calls, used
  about 314 MiB of heap, and moved committed virtual memory from 898 MiB to
  919 MiB.
- Small-batch p50 values are quantized to 0/100 ns by the timer's resolution.
  These rows are only useful for orders of magnitude, and are not a claim of
  sub-100 ns precision.

These are single-host, non-JMH numbers. They are reproducible in shape, not in
the third digit, and treat them as such. There are no Linux or macOS benchmark
numbers yet; the CI host jobs build and test there but do not publish a
benchmark.

## Real server workload investigation

Before adding any native call to the engine, an existing workload was profiled
rather than guessed at. `EngineResourceBenchmark` (64 chunks, the "resource"
configuration with two workers and a one-CPU JVM) measured **278.5 chunks/s**,
**201 MiB peak** and **98 MiB retained**. A JFR profile of that run produced 163
execution samples, of which:

- **77 (~47%)** were inside `ChunkDataCodecImpl.encode` — the chunk save path;
- roughly another fifth were in `LightJob` (`computeSkyLight`, `sectionAt`,
  `Chunk.section`);
- the enclosing frames were `ChunkSaveJob` and the scheduler's worker loop.

The codec is therefore the first CPU consumer, and it is a Java serialization
problem, not a compute problem. A temporary harness compared the real codec with
an equivalent `ByteBuffer` implementation of the same format:

| Operation | Actual codec | `ByteBuffer` | Speed-up |
| --- | ---: | ---: | ---: |
| encode | 1,771 µs | 70 µs | ~25x |
| decode | 1,626 µs | 66 µs | ~25x |

The `ByteBuffer` output was **byte-identical** and decoded back to the same
snapshot, so this is a safe Java-side improvement, not a format change.

**Decision: no native workload was integrated.** The only native operation is a
bulk integer transform, no real server code calls it, and the profiled hotspot
is a byte-serialization loop that would require one FFM call per block — the
exact pattern the integration rules forbid. Fixing the codec in Java is the
correct first step. If that lands, re-profile before considering native
acceleration again; do not add native code merely because the module exists.

## Memory and lifecycle

- **The caller owns every buffer.** No exported function allocates memory the
  caller must free, and no ownership is transferred across the ABI boundary.
- **Production path (`NativeEngine.transform`).** Each call opens a confined
  arena, allocates an input and an output segment (8 bytes per element total),
  copies in, calls, copies out, and closes the arena. That memory is released
  at the end of the call even when the native call fails.
- **Reusable shape (`NativeBenchmark.NativeReusable`).** One shared arena and two
  pre-allocated segments; the caller keeps them alive and is responsible for
  closing the arena. This is the shape that produced the only wins in the
  benchmark, and it is *not* wired into the server.
- **Last error is thread-local.** `veltis_native_last_error()` points at storage
  valid until the next call on the same thread; it must never be freed.
- **Closing a loaded library is final.** `NativeLoader.LoadedLibrary.close()`
  waits for in-flight calls, releases the symbol lookup and the library arena;
  any later call throws `IllegalStateException` instead of touching unmapped
  code.
- **Engine lifecycle is locked.** `NativeEngine.initialize` and `shutdown` take
  a write lock; transforms take a read lock. An in-flight native call completes
  before unload, and a call that arrives after shutdown uses the Java fallback
  (or throws when fallback is disabled). Repeated `initialize`/`shutdown` calls
  are idempotent.
- **Failed load leaves nothing open.** Every rejection path closes the library
  before returning, and a failed manual `load()` can be retried.

## Limitations

- The module accelerates one bulk primitive. It is not a general native
  acceleration layer, and the server does not depend on it for correctness.
- Windows MinGW builds statically link the MinGW C++ runtime by default, so a
  copied DLL imports only Windows system libraries. Linux builds link the
  system `libstdc++`, `libgcc_s`, `libm` and `libc`, which any distribution with
  a C++ toolchain provides; the CI dependency check enforces that list. macOS
  links only system libraries.
- Only FFM (JDK 22+) is supported. There is no JNI fallback for older JDKs.
- The launcher never packages or downloads a library. An operator installs one
  and is responsible for matching the ABI and platform.
- The benchmark is not JMH and does not control CPU frequency, JIT compilation
  per implementation or the operating system scheduler. Use it to compare
  shapes of integration, not to publish a speed-up claim.
- Linux, macOS and non-x86-64 variants are loader-recognized and have CI jobs,
  but they have not yet run green. Until the support table shows Executed and
  Integration-tested, no such claim is made.

## Extending the ABI

1. Add the declaration and a `VELTIS_NATIVE_CAPABILITY_*` bit to
   `veltis_native.h`.
2. Implement it behind the C ABI in `native_engine.cpp` (catching exceptions and
   returning a status code) and put the algorithm in `algorithms.cpp`.
3. Add the bit to `NativeCapabilities` and a downcall to `NativeLoader.Bindings`.
4. Give `NativeEngine` a Java implementation with identical output and a test
   that asserts native/fallback equivalence.
5. Extend `tests/native_tests.cpp` for argument, boundary and lifecycle cases,
   and add the export to `tests/export_check.c`.
6. Update the support table only after the new export has run on a target.

Bump `VELTIS_NATIVE_VERSION_MINOR` for an additive change; bump
`VELTIS_NATIVE_ABI_VERSION` only when an existing signature or semantic changes
in a way older callers cannot use.
