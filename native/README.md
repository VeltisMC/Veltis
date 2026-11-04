# `native`

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
| `docs/` | The 15-document foundation set (see Documentation below). |
| `tools/` | `check-pe-imports.ps1` — the single authority for the Windows runtime policy, used by both CI and `verifyNativeRuntimePolicy`. |
| `cmake/toolchains/` | Cross-compilation toolchain files for Windows ARM64, Linux AArch64 and Linux RISC-V 64. |
| `CMakePresets.json` | Host and cross presets; preset names are listed below. |

## Documentation

`docs/` is this module's specification set. Each document separates what has
been **executed** from what is merely configured — that distinction is the
point of the set:

| Document | Subject |
| --- | --- |
| `FOUNDATION_AUDIT.md` | The audit this foundation round was built from: findings, phases, requirements |
| `ARCHITECTURE.md` | Layering and module structure |
| `MODULE_BOUNDARIES.md` | What may depend on what, and what must never cross |
| `ABI_CONTRACT.md` | The 13-export contract: signatures, booleans, statuses, layout |
| `MEMORY_MODEL.md` | Ownership, the no-allocation rule, buffer predicates |
| `THREADING_MODEL.md` | The one lock, lifecycle ordering, what is deliberately not guaranteed |
| `RUNTIME_LIFECYCLE.md` | The init/shutdown/failure state machine |
| `PLATFORM_SUPPORT.md` | Per-platform evidence labels — **the authority on any support claim** |
| `CPU_DISPATCH.md` | When ISA specialization is allowed to exist |
| `BUILD_SYSTEM.md` | CMake, presets, Gradle wiring, troubleshooting |
| `JAVA_FFM_INTEGRATION.md` | The downcall shape, arena rules, Java fallback contract |
| `TESTING_STRATEGY.md` | Every test layer, the sanitizer lanes, what cannot run on which host |
| `SECURITY_MODEL.md` | Threat model, validation order, explicit non-protections |
| `BENCHMARKING.md` | Method, current numbers, the optimization acceptance bar |
| `CI_SUPPORT_MATRIX.md` | CI jobs, runner labels, and the fact that no CI run has executed yet |

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

The corresponding environment variables `CMAKE`, `CTEST` and `GIT` are also
accepted, and the matching properties are `-PveltisNativeCmake`,
`-PveltisNativeCtest` and `-PveltisNativeGit`. `git` is needed only by the
packaging and source-archive tasks, which build their file lists from the index.
Only the native tasks look for these tools; configuration and every Java task do
not, so a Java-only build never asks for a compiler.

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

# Package, then check the manifest against the bytes actually on disk
./gradlew :veltis-native:verifyNativeArtifact

# Tracked-files-only source archive, extracted and built outside the checkout
./gradlew :veltis-native:packageNativeSourceArchive :veltis-native:verifyNativeSourceArchive

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
native/build/native/<platform-key>/veltis_native.dll          (Windows)
native/build/native/<platform-key>/libveltis_native.so        (Linux)
native/build/native/<platform-key>/libveltis_native.dylib     (macOS)
native/build/native/<platform-key>/test-fixtures/             (incompatible fixtures)
```

`<platform-key>` is `windows-x86_64`, `windows-aarch64`, `linux-x86_64`,
`linux-aarch64`, `macos-x86_64` or `macos-aarch64` (see the support table).

### Manual CMake

The Gradle tasks are a thin wrapper; a direct CMake build is equivalent:

```bash
cmake -S native -B native/build/native/cmake/manual \
  -DCMAKE_BUILD_TYPE=Release \
  -DVELTIS_NATIVE_OUTPUT_DIR="$PWD/native/build/native/linux-x86_64"
cmake --build native/build/native/cmake/manual --parallel
ctest --test-dir native/build/native/cmake/manual --output-on-failure
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
`-PveltisNativeOsxArchitectures=arm64|x86_64` and the deployment floor uses
`-PveltisNativeOsxDeploymentTarget=11.0` (see the next section); Windows static
runtime selection uses `-PveltisNativeStaticRuntime=true|false`, and it is on for
every Windows toolchain unless you ask for the shared CRT.

### Minimum macOS version

`CMAKE_OSX_DEPLOYMENT_TARGET` is set **before** `project()` in `CMakeLists.txt`.
That placement is the whole point: the Darwin platform modules translate the
variable into `-mmacosx-version-min` while `project()` runs, so a value assigned
afterwards reaches neither the compiler nor the linker and the binary silently
carries the host SDK's default.

Precedence, highest first:

1. `-DCMAKE_OSX_DEPLOYMENT_TARGET=…` (or `-PveltisNativeOsxDeploymentTarget=…`,
   which Gradle forwards)
2. a value already in the CMake cache from an earlier configure
3. the `MACOSX_DEPLOYMENT_TARGET` environment variable
4. this project's floor, `11.0`

A value you supplied is never overwritten. The chosen target is recorded in the
manifest as `runtime.minimumOsVersion`, and the macOS CI job fails if
`vtool -show-build` reports a different `minos` than the manifest claims — the
documented floor and the linked floor have to be the same number.

`11.0` means "this library is built to run on macOS 11 and later, and it has been
linked against that floor". It is not a claim that every release from Big Sur
onward was executed: the only macOS evidence this repository has is whatever the
`macos-aarch64` and `macos-x86_64` CI jobs produce.

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
| Windows x86-64 | LLVM-MinGW Clang 23.1.4, Ninja | ✅ | ✅ | ✅ | ✅ | ✅ | **Verified** (2026-11-03; ASan+UBSan lane also passed 2026-11-04) |
| Windows x86-64 | MinGW-w64 GCC 16.2.0 (winlibs, msvcrt), Ninja | ✅ | ✅ | ✅ | ✅ | ✅ | **Verified** (2026-11-04) |
| Windows x86-64 | MSVC, clang-cl | — | — | — | — | — | **Not tested**; no MSVC toolchain on the verification host |
| Windows ARM64 | LLVM-MinGW cross | ✅ | ✅ | ✅ | — | — | **Cross-compiled only** |
| Windows ARM64 | MSVC (native `windows-11-arm` runner) | — | — | — | — | — | CI defined, blocking |
| Linux x86-64 | GCC, Clang | — | — | — | — | — | CI defined, not yet run |
| Linux AArch64 | GCC (native runner) | — | — | — | — | — | CI defined, not yet run |
| Linux AArch64 | GNU cross | — | — | — | — | — | Preset defined, not run here |
| macOS Apple Silicon | Apple Clang | — | — | — | — | — | CI defined, not yet run |
| macOS Intel x86-64 | Apple Clang | — | — | — | — | — | CI defined, not yet run |
| FreeBSD x86-64/ARM64 | — | — | — | — | — | — | **Not supported**; investigation job only |
| Linux RISC-V 64 | GNU cross | — | — | — | — | — | **Not supported**; compile-only preset |

Both verified rows were produced on Windows 11 x86-64 with JDK 25.0.5.0.3 and
CMake 4.4.5. The GCC row was re-verified on **2026-11-04**: the Release, Debug
and `-Werror` configurations each passed CTest 5/5, and `verifyNativeRelease`
completed its full checklist in one run — configure, Release build, CTest (5
tests: unit contract, export/binary-format check, the C and C++ ABI contract
units and the seeded fuzz target), the Java FFM suite (**81 tests, 0 failures,
1 tolerated skip**), packaging, manifest-vs-artifact, the Windows runtime policy
(imports only `KERNEL32.dll` and `msvcrt.dll`, no compiler runtime), and the
source archive (**54 tracked files**) extracted and rebuilt from scratch
outside the working tree. The Clang lane was verified 2026-11-03 (Release and
Debug) and additionally ran the AddressSanitizer + UBSan configuration on
2026-11-04 (CTest 5/5 under instrumentation). An earlier round cross-compiled
Windows ARM64 locally (COFF ARM64, 13 exports) but the ARM64 DLL was never
executed. MSVC and clang-cl were not built: this host has no Visual Studio. No
Linux, macOS, FreeBSD or RISC-V binary was built or run, because the local
machine has no such toolchain, runner or emulator.

The loader recognizes the six Tier 1 keys independently of this table so a
future build can ship without a Java change. FreeBSD, the other BSDs and RISC-V
report an explicit "unsupported platform" status and keep the Java fallback.

## What the verified Windows x86-64 run covers (2026-11-04)

- **CTest, 5/5 passed** in Release: `veltis_native_tests` (unit and contract
  behaviour), `veltis_native_exports` (host binary format + exactly the 13
  public names, no internal symbols), `veltis_native_abi_contract_c` and
  `veltis_native_abi_contract_cxx` (the public header compiles as pure C and as
  C++20, every export's signature pinned as a function-pointer type), and
  `veltis_native_fuzz_abi_inputs` (seeded, bounded differential fuzz of the
  argument-validation surface). The Debug and `-Werror` configurations each
  passed the same 5/5.
- **Sanitizer lane, CTest 5/5 under AddressSanitizer + UBSan** (LLVM-MinGW
  Clang, own build tree `windows-x86_64-san-address-undefined`) — the first
  sanitizer execution in this repository's history. ThreadSanitizer and
  MemorySanitizer are **refused at configure time** on this toolchain, by
  design, with a compile-and-link probe message; TESTING_STRATEGY.md §3 holds
  the full executed/refused/not-tested table.
- **Java module suite, 81 tests with 0 failures and 1 skipped** against the
  real DLL (`:veltis-native:check -PveltisNativeTest=true`). The single skip is
  `javaOnlySuiteHasNoNativeFixtureConfigured`, which only asserts in the
  Java-only configuration.
- **Java-only suite, 81 tests with 25 skipped and 0 failures**
  (`:veltis-native:test -Dveltis.native.test.expectJavaOnly=true`). The flag
  makes Gradle detach every fixture path *and* makes the suite assert that no
  fixture leaked into the JVM, so "Java-only" is checked rather than assumed.
- **Server suite, 160 tests with 0 failures and 8 skipped** (`:server:test`),
  including `NativeBootstrapTest` **1/1 passed with the library** (asserts
  native availability and release on stop) and **1/1 passed without it**
  (asserts the Java fallback and post-stop state). `:patcher:test`: 236 tests,
  0 failures. `buildVeltisMC`: BUILD SUCCESSFUL.
- **Binary inspection:** PE x86-64, 295,661 bytes, exactly 13 exported names
  and no internal exports, built by GNU 16.2.0; imports are limited to
  `KERNEL32.dll` and `msvcrt.dll` with no compiler runtime
  (`tools/check-pe-imports.ps1` — the same script CI calls, so the two cannot
  disagree).
- **Source archive:** `packageNativeSourceArchive` zipped **54 tracked files**
  into `build/native/dist/veltis-native-source.zip`, and
  `verifyNativeSourceArchive` extracted it and built `veltis_native.dll` from
  scratch outside the working tree. The task prints the archive's sha256; this
  file deliberately quotes no hash for it, because `README.md` is itself one of
  the 54 files — naming the hash here would change it.
- **Release verification report:** `verification-report-windows-x86_64.json`
  records the commit, the tree state (honestly `tree dirty` — this checkout is
  not clean), the toolchain, and the distribution archive hash
  `88365923c9dbb3c027e7fe99c86bc9f75c57449b8e4efd5db2f1174ebbd910bd`.
- **Static analysis and lint:** `gcc -fanalyzer` on `native_utils.c` is clean
  (GCC 16.2.0, exit 0). `cppcheck` is not installed here, so
  `analyzeNativeStatic` fails loudly rather than skipping — install it (or pass
  `-PveltisNativeCppcheck=<path>`) to run that half. `.github/workflows/gradle.yml`
  passes `actionlint` 1.7.12 (exit 0).
- **Benchmark re-run:** `benchmarkNative` executed; the harness's own verdict
  line reads `threshold: native-engine did not beat the allocation-free Java
  baseline at any measured batch size`. Numbers: BENCHMARKING.md.
- **Reproducible bytes (earlier round):** three clean Release builds from
  different build directories produced the same SHA-256
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
- the export/binary-format check described above;
- the two ABI contract units: the public header must compile as pure C **and**
  as C++20 with every export's signature pinned as a function-pointer type, so
  a changed parameter, a dropped `const` or a different return type fails to
  compile before any library exists;
- the seeded differential fuzz target (fixed seed, 4,000 rounds by default):
  null, zero-length, length-mismatch, byte-extent, alignment and overlap
  rejections exercised in *combination*, every pointer constrained to three
  real over-aligned buffers so the harness itself can never fault.

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
- failure-log throttling: burst admission, the one-per-minute interval, the
  suppressed-count report and thread safety (`FailureLogThrottleTest`);
- the server startup/shutdown path with and without the library.

User-visible status is honest about the distinction: a library that was never
found reports `loaded=false`; one that was found and rejected reports
`loaded=true` with the reason, because those are different operator problems.
The "not found" reason now prints the exact absolute paths it searched, which is
the difference between "install the library" and "install it *here*".

### Where the suite stands today

The same module, on the same Windows x86-64 host, with the current sources
(all runs 2026-11-04):

| Run | Command | Result |
| --- | --- | --- |
| native-enabled | `./gradlew :veltis-native:test --rerun-tasks` | 81 tests, 1 skipped, 0 failures |
| Java-only | `./gradlew :veltis-native:test -Dveltis.native.test.expectJavaOnly=true --rerun-tasks` | 81 tests, 25 skipped, 0 failures |
| server | `./gradlew :server:test` | 160 tests, 0 failures, 8 skipped; `NativeBootstrapTest` green with and without the library |
| patcher | `./gradlew :patcher:test` | 236 tests, 0 failures |
| whole repository | `./gradlew build` | BUILD SUCCESSFUL |

The single skip in the native-enabled run is
`javaOnlySuiteHasNoNativeFixtureConfigured`, which only asserts in the Java-only
configuration; the 25 skips there are the tests that need a library or a fixture.
The suite grew from 72 to 81 tests in this foundation round: the new
`FailureLogThrottle` suite (7 tests) plus two further loader/engine cases.
Among the loader cases are a library from another operating system and one for
another architecture, both rejected *before* the OS loader is asked to map
them, and a configured path the filesystem cannot name (a NUL byte) coming back
as a reported failure rather than an exception. The Java-only run is what proves
the "the server never requires native availability" rule on a machine that has
built nothing.

These numbers were produced on 2026-11-04 against a library rebuilt from the
current C/C++ sources (Release, GNU 16.2.0) — they cover the code as it stands,
not a stale artifact. Earlier rounds' numbers (61, then 72 tests) are superseded.

## CI coverage

`.github/workflows/gradle.yml` has four native jobs. The full matrix — every
runner label, every step, what each job proves, and what CI deliberately does
*not* run (sanitizers, `cppcheck`, benchmarks, `-Werror`) — is documented in
[`docs/CI_SUPPORT_MATRIX.md`](docs/CI_SUPPORT_MATRIX.md); this section is the
summary:

- `native-host` — a matrix of Windows x86-64 (MSVC, clang-cl, LLVM-MinGW),
  Windows ARM64 (MSVC), Linux x86-64 (GCC and Clang), Linux AArch64 (GCC) and
  macOS (Apple Silicon and Intel). Each entry runs
  `:veltis-native:verifyNativeRelease -PveltisNativeTest=true`, which configures,
  builds, runs CTest and the export check, runs the Java FFM suite, packages the
  artifact, validates the manifest against the bytes it describes, rebuilds the
  source archive from its own extraction and writes a verification report. It
  then inspects dependencies on that OS (PE imports via
  `tools/check-pe-imports.ps1` on Windows, `DT_NEEDED` plus the recorded
  glibc/libstdc++ requirements and an RPATH check on Linux,
  slices/deployment target/`vtool -show-build`/`otool -L` on macOS), runs the
  Java-only suite with `expectJavaOnly`, runs the server bootstrap test with and
  without the library, and uploads `build/native/dist/<platform>` as
  `veltis-native-<platform>-<toolchain>`.
  Three of those are gates rather than inspections: the Windows step reads
  `runtime.staticRuntime` from the manifest and only tolerates
  `vcruntime*/msvcp*` imports when it says `false`; the macOS step fails if the
  `minos` that `vtool` reads out of the binary differs from the manifest's
  `minimumOsVersion`; and `verifyNativeRelease` fails when any Java integration
  test other than `javaOnlySuiteHasNoNativeFixtureConfigured` reported a skip.
  A skipped native test is treated as a missing native test, not as a pass.
- `native-cross` — compile-only jobs for Windows ARM64, Linux AArch64 and Linux
  RISC-V. Each verifies binary format, machine, the exact 13 exports against the
  public header, the recorded manifest fields (`os`, `architecture`, `platform`,
  `pointerBits`, `abiVersion` and the export list) against what the artifact
  actually is, and dependencies, then proves the artifact is never executed:
  no CTest tests are registered, and Gradle's `testNative` guard must refuse to
  run. Windows ARM64 and Linux AArch64 are **blocking**; only the Tier 2 RISC-V
  investigation is allowed to fail.
- `native-debug` — Windows x86-64 MSVC and Linux x86-64 GCC in the Debug
  configuration, because Release alone can hide assertion-sensitive defects.
- `native-freebsd-investigation` — non-blocking FreeBSD VM build used to find
  portability defects. It is not a support claim.

There is no job-level `continue-on-error` on `native-host`; every Tier 1 entry
is a required check. Failures are never hidden behind an optional matrix value.

The jobs have been written and lint-clean (`actionlint` 1.7.12, exit 0,
2026-11-04) but **have not yet run on the commit in question** — no push or PR
has been made from this work, so no hosted runner has executed them.
Lint-clean is not run-clean: treat every row above without a ✅ as unverified
until its job is green. Two currency notes live in `CI_SUPPORT_MATRIX.md`: the
Apple Silicon lane's `macos-14` label was retired platform-wide on 2026-11-02
(the lane now uses `macos-15`), and actionlint does not validate runner-label
existence — that fix came from reading GitHub's runner reference, not from the
linter.

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
| `sourceCommit`, `sourceTreeState` | `git rev-parse HEAD` / `git status --porcelain` at packaging time |

`runtime.minimumOsVersion` is populated **only where it was actually set**:
macOS, from the deployment target that reached the linker. On Windows and Linux
it stays an empty string, because nothing measured a floor there — the loader does
not know which OS revision a `KERNEL32.dll`/`libc.so.6` import needs, and the
recorded glibc/libstdc++ symbol versions live in the CI job log, not in a claim.
`verifyNativeArtifact` enforces that asymmetry: a non-empty value on a non-macOS
target is a failure, and an empty value on macOS is a failure.

`runtime.staticRuntime` records what the link step really did. On Windows the
default is a static C runtime for every toolchain — MinGW-w64 GCC via `-static`,
which is the only spelling that also keeps `libwinpthread-1.dll` out of the
import table, LLVM-MinGW via `-static-libgcc -static-libstdc++`, MSVC and
clang-cl via `MSVC_RUNTIME_LIBRARY=MultiThreaded` (`/MT`) — because a DLL copied
beside a JAR cannot ship `vcruntime140.dll` with it. The Windows CI dependency check reads this
field and only allows `vcruntime*/msvcp*` imports when it says `false`, so the
check cannot pass a build that the manifest describes as static, or fail one it
describes as dynamic. What remains after that is the C runtime the toolchain
targets: the 2026-11-04 MinGW-w64 GCC artifact imports `KERNEL32.dll` and
`msvcrt.dll` and nothing else, while the 2026-11-03 LLVM-MinGW (UCRT) artifact
imports `KERNEL32.dll` plus `api-ms-win-crt-*`. Both are Windows system
components, but neither shape was executed here on a down-level install: a
Windows host missing the UCRT update is outside what this module has tested.

### Verifying a packaged artifact

```bash
./gradlew :veltis-native:verifyNativeArtifact          # manifest vs. the files on disk
./gradlew :veltis-native:packageNativeSourceArchive    # tracked sources only, reproducible
./gradlew :veltis-native:verifyNativeSourceArchive     # extract that zip and build it
```

`verifyNativeArtifact` is the release gate that stops a manifest from being a
story. It re-reads the header, the manifest, `SHA256SUMS` and the archive, and
compares them against the binary itself:

- every SHA-256 is recomputed, and any file in the distribution that `SHA256SUMS`
  does not sign is a failure — including a stale library from a previous build;
- `abiVersion`, `version`, `exports` and `exportCount` must equal what
  `veltis_native.h` declares, and `abiVersion` must equal the version the Java
  loader negotiates;
- the container and architecture read out of the binary (through the same
  `NativeLoader.probeBinary` the server uses, invoked reflectively from the build
  script) must match `os`, `architecture` and `platform`;
- `pointerBits` must be 64, `buildType`, both compiler ids/versions and
  `targetTriple` must be present, and `staticRuntime` must be a boolean;
- the archive must contain exactly the library, its manifest and `SHA256SUMS`, and
  the `.zip.sha256` beside it must match the archive's recomputed hash.

All the problems found in one run are reported together, because half a fix is
how a wrong manifest survives review.

`packageNativeSourceArchive` zips **`git ls-files` and nothing else**, so a
`CMakeCache.txt`, a stray `.o` or a local toolchain path cannot reach a release
archive no matter how many half-configured build trees the working directory has.
The zip is reproducible (sorted entries, timestamps pinned, no Zip64).
`verifyNativeSourceArchive` then extracts it into `build/native/source-check/`,
asserts the required files are present, asserts no generated file is, and runs a
real CMake configure and build **on the extracted copy** — a source tree that
only builds inside a working checkout is not a source release. It writes nothing
outside that directory and never reorganises the source tree to make itself pass.

Nothing from the toolchain or the operating system is bundled: no compiler
runtime, no `libstdc++`, no system dylib. `SHA256SUMS` covers both the library
and the manifest, so an edited manifest is detectable before it is trusted.

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
check, Java FFM integration, packaging, manifest-vs-artifact validation, the
source archive rebuilt from its own extraction, checksums and a report recording
the source commit, whether the checkout was clean, the toolchain, and the archive
hash. It refuses to run against a cross-compiled artifact or without
`-PveltisNativeTest=true`.

The full release procedure, in order, on a **clean checkout**:

1. `git status --porcelain` must be empty; record `git rev-parse HEAD`.
2. `./gradlew :veltis-native:cleanNative`
3. `./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true`
   — build, CTest, export check, Java integration, package, checksums, report.
4. `./gradlew :veltis-native:verifyNativeArtifact` — the manifest, `SHA256SUMS` and the
   archive re-checked against the bytes of the library itself. CI runs the same
   gate on every `native-host` matrix entry and the equivalent checks against
   cross-compiled artifacts in `native-cross`, so a manifest that describes a
   different build than the one it ships fails a job instead of a reviewer.
5. `./gradlew :veltis-native:verifyNativeSourceArchive` — extract the tracked-files-only
   archive and build that copy from scratch.
6. `./gradlew :veltis-native:test "-Dveltis.native.test.expectJavaOnly=true"`
   — the Java-only fallback suite with no fixture.
7. Dependency inspection: `tools/check-pe-imports.ps1` on Windows; `readelf -d`
   plus recorded glibc/libstdc++ requirements on Linux; `lipo -archs`,
   `vtool -show-build` and `otool -L` on macOS.
8. `./gradlew -Dveltis.native.path=<library> :server:cleanTest :server:test
   --tests org.veltismc.runtime.NativeBootstrapTest` — bootstrap and shutdown
   with the library, then again with an empty path for the Java-only path.
9. Confirm `SHA256SUMS` against the produced files.
10. Confirm `verification-report-<platform>.json`: `commit`, `treeState=clean`,
    `librarySha256`, `archiveSha256`.
11. On every release target, run steps 3–8 on that target's runner (or confirm
    the corresponding `native-host` CI run is green for the same commit).
12. Do **not** tag or publish without explicit authorization; the report is the
    evidence, not the release.

Toolchain versions used by the current run (2026-11-04): CMake 4.4.5, Ninja,
MinGW-w64 GCC 16.2.0 as the primary host lane and LLVM-MinGW Clang 23.1.4 for
the sanitizer lane, JDK 25.0.5.0.3 locally and JDK 26 in CI.

**Currency.** These numbers describe 2026-11-04, not a stale session: the C and
C++ sources were rebuilt from the current tree in Release, Debug and `-Werror`
configurations (CTest 5/5 each), under AddressSanitizer + UBSan (CTest 5/5),
and `verifyNativeRelease` completed end to end — including the from-scratch
source-archive rebuild — against that freshly built library. The Java suites
(81 + 160 + 236 tests) ran against it as well. Evidence from earlier rounds
that still stands, labeled as such above: the 2026-11-03 LLVM-MinGW
verification, the three-build byte-reproducibility check, and the local
Windows ARM64 cross-compilation (compiled, never executed). No number in this
file should be read as coverage of a platform whose support-table row says
**Not tested**.

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
cp native/build/native/linux-x86_64/libveltis_native.so \
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
measures and prints separately (36.2 ns per `System.nanoTime` call on the
current host; two calls per sample). Heap allocation comes from
`ThreadMXBean.getCurrentThreadAllocatedBytes`; heap use from `MemoryMXBean`;
committed virtual memory from the operating-system MXBean. Sizes run from 1 to
1,048,576 elements (11 sizes; every one between 16 and 65,536 is omitted below
for width).

Current run (Windows 11 x86-64, 20 logical processors, JDK 25.0.5.0.3, HotSpot
64-bit, library built Release by MinGW-w64 GCC 16.2.0, **2026-11-04**):

| Batch | `java` | `java-optimized` | `native-engine` | `native-reusable` |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 30.7 | 17.4 | 4.0 | 12.0 |
| 16 | 469.9 | 644.7 | 74.1 | 189.6 |
| 256 | 1748.8 | 1800.2 | 840.1 | **2077.1** |
| 1,024 | 1394.6 | 2043.6 | 1176.2 | **3975.7** |
| 4,096 | 871.2 | 2153.3 | 813.4 | **3131.7** |
| 65,536 | 1411.4 | 2065.8 | 1273.4 | **3459.7** |
| 262,144 | 1323.3 | 1991.6 | 412.7 | 2077.6 |
| 1,048,576 | 1077.7 | **1956.8** | 377.8 | 1447.8 |

M elements/s, higher is better. Bold marks the best row value.

What the numbers say:

- The **production native path never beat the allocation-free Java baseline at
  any batch size** — the harness prints that verdict itself:
  `threshold: native-engine did not beat the allocation-free Java baseline at
  any measured batch size`. At 1 MiB it is ~2.9× slower than plain `java` and
  ~5× slower than `java-optimized`: the two mandatory copies and per-call
  arena work dominate.
- The **reusable native path** wins from roughly 256 to 262,144 elements
  (within 5% of `java-optimized` at 262,144 — "roughly even" — and a clear
  ~1.4–2× win at 1,024–65,536) and loses at 1 MiB, where a tight Java loop
  streams through cache better than a copy-heavy native call. That is the only
  shape worth considering for a future integration: caller-owned, reused
  segments and a batch in the hundreds-to-tens-of-thousands range.
- The FFM transition itself is cheap: a trivial downcall
  (`veltis_native_capabilities`) measured **76.4 ns** (76.8 ns in an earlier
  run of the same round; 41.6 ns in the previous round's Clang build). The
  cost is the two memory copies and the per-call allocation, not the call.
- `java-optimized` allocations were zero by construction; `native-engine`
  allocated about 8 bytes per element for its two segments. In total the run
  allocated 2,762.5 MiB across all measured calls, used about 314 MiB of heap,
  and moved committed virtual memory from 898.5 MiB to 925.6 MiB.
- Small-batch p50 values are quantized to 0/100 ns by the timer's resolution.
  These rows are only useful for orders of magnitude, and are not a claim of
  sub-100 ns precision.

These are single-host, non-JMH numbers. They are reproducible in shape, not in
the third digit, so treat them as such. An earlier round's table (an
LLVM-MinGW/Clang build) is superseded by this one; `docs/BENCHMARKING.md`
records how the two differ — the reusable shape moved up to 29% between
toolchains while the Java rows moved under 10% — together with the cross-run
variance, the full 11-size table and the acceptance bar every future
optimization has to clear. There are no Linux or macOS benchmark numbers yet;
the CI host jobs build and test there but do not run a benchmark.

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
- Windows builds statically link their compiler runtime by default — MinGW-w64
  GCC via `-static`, LLVM-MinGW via `-static-libgcc -static-libstdc++`, MSVC and
  clang-cl via `/MT` — so a copied DLL imports only Windows system libraries.
  What remains is the C runtime the toolchain targets: the verified GCC
  artifact imports `msvcrt.dll`, the verified LLVM-MinGW (UCRT) artifact
  imports `api-ms-win-crt-*` (part of Windows 10 and later). This module has
  not tested an install that still needs the standalone UCRT redistributable,
  so a down-level Windows host is outside the evidence. `-PveltisNativeStaticRuntime=false`
  opts back into the shared CRT and the manifest records that choice, which is
  what the CI import check then reads. Linux builds link the system `libstdc++`,
  `libgcc_s`, `libm` and `libc`, which any distribution with a C++ toolchain
  provides; the CI dependency check enforces that list and records the oldest
  glibc/libstdc++ symbol versions the artifact actually references. macOS links
  only system libraries.
- The macOS floor is a link-time promise, not a test matrix: `11.0` is what the
  deployment target says and what `vtool` confirms, but no macOS release between
  11 and the current one has executed this library here.
- `veltis_native_last_error()` is thread-local by design, so the string it
  returns describes the *calling thread's* last call. A caller that logs an error
  from a different thread than the failing downcall gets a message about someone
  else's failure. The Java integration keeps each failure and its `lastError()`
  read on one thread; a future consumer has to do the same.
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
