# veltis-native Testing Strategy

What is tested, at which layer, with which tool, on which host — and, with the
same care, what has never run and what cannot run where.

Companion documents: [SECURITY_MODEL.md](SECURITY_MODEL.md) for the threats these
tests are shaped against, [BENCHMARKING.md](BENCHMARKING.md) for measurement
(that is not testing), [CI_SUPPORT_MATRIX.md](CI_SUPPORT_MATRIX.md) for where
each layer is supposed to execute, [ABI_CONTRACT.md](ABI_CONTRACT.md) and
[RUNTIME_LIFECYCLE.md](RUNTIME_LIFECYCLE.md) for the contracts being pinned.

## 1. The rules this strategy follows

1. **A check that did not run is not a check.** Every lane here either fails
   loudly when its tool is missing or is explicitly documented as not executed.
   There is no "skipped, therefore green" path: `verifyNativeRelease` fails when
   any Java integration test other than the one tolerated skip reports a skip,
   `analyzeNativeStatic` fails when `cppcheck` is absent, and CMake refuses a
   sanitizer it cannot compile *and link* rather than producing an uninstrumented
   binary that someone would later quote as evidence.
2. **Test the artifact, not a mock, at least once per layer.** The fixture
   libraries are real shared libraries with deliberately wrong metadata; the
   export check loads the real artifact through `LoadLibrary`/`dlopen`; the Java
   end-to-end test runs against the DLL the build just produced.
3. **Correctness is differential where two implementations exist.** The native
   transform and the Java fallback must produce identical bits, so most
   behavioural tests assert against `javaTransform` rather than against a
   hand-written expectation.
4. **Cross-compilation is never a test.** See §8.

## 2. The layers

```
Layer 4  Server            :server:test  (NativeBootstrapTest, 160 tests total)
Layer 3  Java integration  :veltis-native:test  (81 tests, real DLL + fixtures)
Layer 2  ABI contract      native_abi_contract_c / _cxx  (compile-time + runtime)
Layer 1  Native unit/CTEST native_tests, native_export_check, fuzz target
Layer 0  Static            cppcheck, gcc -fanalyzer, compiler warnings, actionlint
```

Each layer can run without the ones above it. Layer 0 needs no build; layer 1
needs CMake and a compiler; layer 3 needs a JVM and (for most of it) a built
library; layer 4 is the ordinary Java build.

### Layer 1 — CTest (native side)

Five tests, wired in `native/tests/CMakeLists.txt`, all executed on this host:

| Test | What it proves |
| --- | --- |
| `veltis_native_tests` | Unit and contract behaviour: versions, status strings, lifecycle idempotence, the transform contract, integer boundaries, a 1 MiB batch, the null/zero-length/mismatch matrix, misalignment, overlap, byte-extent overflow, error clearing, thread-local isolation of `last_error`, 100 init/shutdown cycles, concurrent init/shutdown/transform stress, shutdown while transforms run, and direct unit tests of the `native_buffer_checks.hpp` predicates |
| `veltis_native_exports` | The artifact the host will load has the host's binary format (PE/ELF/Mach-O), exports **exactly** the 13 public names, and does not leak the internal `veltis_native_set_last_error`/`veltis_native_clear_last_error` bridge. It also fails if an allocator-shaped name (`alloc`, `free`, `release`, `last_error_free`) ever appears, which is how the "no allocator contract" rule in MEMORY_MODEL stays enforced rather than habitual |
| `veltis_native_abi_contract_c` | The public header compiles as **pure C**, and every export's signature is pinned as a function-pointer type — a changed parameter, a dropped `const` or a different return type is a *compile* error, before any library exists |
| `veltis_native_abi_contract_cxx` | The same header compiles as C++20 with C++ consumers' expectations (no macros leaked, `extern "C"` linkage correct) |
| `veltis_native_fuzz_abi_inputs` | Seeded, bounded differential fuzz of the argument-validation surface (§6) |

Run with `./gradlew :veltis-native:testNative` or directly with
`ctest --test-dir native/build/native/cmake/<platform> --output-on-failure`.

Current result (Windows 11 x86-64, 2026-11-04): **5/5 passed**, in both the
Release lane and the Debug lane.

### Layer 2 — ABI contract

The two contract units exist because the ABI's most likely failure mode is a
silent signature drift that still compiles somewhere. They are listed separately
from `native_tests` so a C-only consumer's failure reads as "the header is not C"
and not as a unit-test failure. See [ABI_CONTRACT.md](ABI_CONTRACT.md) for the
full rule set (struct layout, alignment, boolean representation, error codes).

### Layer 3 — Java integration (`:veltis-native:test`)

81 tests in six suites, executed against the freshly built DLL:

| Suite | Tests | Focus |
| --- | ---: | --- |
| `NativeLoaderTest` | 36 | OS/arch normalization, filename selection, PE/ELF/Mach-O and fat-header probing, wrong-architecture and wrong-format rejection *before* the OS loader maps the file, a configured path the filesystem cannot name, unsupported-platform diagnostics, search order |
| `NativeEngineTest` | 18 | init/shutdown idempotence, post-shutdown fallback, 1 MiB batches, 8 concurrent callers, `close()` during active calls, native/Java equivalence |
| `NativeEndToEndTest` | 11 | The real library through the FFM bindings: load, ABI/version negotiation, transform equality over boundary values, self-test, capability bits, release on stop |
| `NativeConfigTest` | 5 | Property overlay precedence (`server.properties` vs `-Dveltis.native.*`) |
| `NativeCapabilitiesTest` | 4 | Capability bits are read from the library, never assumed |
| `FailureLogThrottleTest` | 7 | The burst/interval suppression schedule and its suppressed-count reporting |

Current results (2026-11-04):

- native-enabled: **81 tests, 0 failures, 1 skipped**
  (`javaOnlySuiteHasNoNativeFixtureConfigured`, which only asserts in the
  Java-only configuration);
- Java-only (`-Dveltis.native.test.expectJavaOnly=true`): **81 tests, 0
  failures, 25 skipped** — and the flag makes the suite assert that no fixture
  leaked into the JVM, so "Java-only works" is checked rather than assumed.

The deliberately incompatible fixtures (wrong ABI `999`, newer major, failing
init, failing transform, missing export, version-less minimal library) are real
shared libraries built by CMake into `test-fixtures/`, so each rejection path
exercises a genuine `LoadLibrary`/`dlopen` failure instead of a mock.

### Layer 4 — Server (`:server:test`)

160 tests, 0 failures, 8 skipped (2026-11-04), including
`NativeBootstrapTest` in both configurations CI uses: once with
`-Dveltis.native.path=<library>` (asserts native availability and release on
stop) and once with an empty path (asserts the Java fallback and post-stop
state). This is the layer that proves "native failure never prevents server
startup" as an executed fact rather than a design claim.

## 3. Sanitizers

CMake accepts `-DVELTIS_NATIVE_SANITIZERS=address,undefined,thread,memory`
(Gradle: `-PveltisNativeSanitizers=...`), puts each sanitizer lane in its own
build tree, and validates each requested sanitizer against a **compile-and-link
probe** (`cmake/sanitizer-probe.cpp`) before any target exists. The probe exists
because a toolchain that accepts `-fsanitize=X` but ships no runtime fails at
link time — and a build that quietly produced an uninstrumented binary would
later be read as a sanitizer run.

Verified status of each combination, all of it produced on this host on
2026-11-04 with LLVM-MinGW (Clang 23) unless stated:

| Sanitizer | Toolchain | Status | Evidence |
| --- | --- | --- | --- |
| AddressSanitizer + UBSan | LLVM-MinGW Clang 23 (Windows x86-64) | **Executed** | configure probe passed; full build; **CTest 5/5 passed under instrumentation** (`windows-x86_64-san-address-undefined` tree) |
| AddressSanitizer | MinGW-w64 GCC 16.2 | Not executed | probe path exists; no run recorded here |
| AddressSanitizer | MSVC (`/fsanitize=address`) | Not tested | no Visual Studio on this host; CI lane defined |
| ThreadSanitizer | LLVM-MinGW / MinGW GCC | **Refused at configure, by design** | the link probe fails and CMake stops with `this toolchain cannot compile and link -fsanitize=thread` — this toolchain ships no TSan runtime |
| MemorySanitizer | LLVM-MinGW | **Refused at configure, by design** | same probe, same message for `-fsanitize=memory` |
| TSan + ASan/MSan together | any | **Refused at configure, by design** | the runtimes instrument memory the same way and cannot share a process; the message says to use two build directories |
| non-`address` sanitizer under MSVC | MSVC | **Refused at configure, by design** | MSVC provides only `/fsanitize=address`; the message points at this document |
| Any sanitizer on Linux/macOS | GCC/Clang/Apple Clang | Not tested | no such host here; CI has no sanitizer job (see §9) |

Two details are worth recording because they were real defects found by running
the lane rather than reading it:

- The lane originally passed `-fsanitize-recover=off`, which **neither clang nor
  GCC accepts** (clang rejects `off` outright), so every object failed to
  compile. It is now `-fno-sanitize-recover=<san>`, which is the spelling both
  compilers accept, and the comment in `CMakeLists.txt` records why. A
  sanitizer lane that has never been run cannot catch this class of bug — which
  is the argument for running it once on every host that has one.
- ASan/UBSan on Windows rejects the static-runtime link and the build system
  turns `VELTIS_NATIVE_STATIC_RUNTIME` off for that lane with a `STATUS`
  message, because the sanitizer runtimes are shared libraries. Release builds
  are unaffected.

Instrumentation is compile-time `-fno-omit-frame-pointer -g` plus
`-fno-sanitize-recover` for `address`/`undefined`/`memory`, so a defect aborts
the test instead of being logged and survived.

## 4. Compiler diagnostics and static analysis

- **Warnings.** Every target passes through `veltis_enable_warnings`:
  `-Wall -Wextra -Wpedantic -Wshadow -Wwrite-strings -Wmissing-declarations
  -Wredundant-decls` (MSVC: `/W4`). `-PveltisNativeWerror=true` promotes them to
  errors — **executed clean on this host, 2026-11-04** (full `check` with
  `VELTIS_NATIVE_WERROR=ON`, GCC 16.2 lane).
- **`analyzeNativeStatic`** runs `cppcheck --inline-suppr --std=c++20` over the
  C++ sources and `gcc -fanalyzer` over `src/c/native_utils.c` (the translation
  unit that owns the thread-local error slot, and the one GCC's analyzer
  understands best). It is deliberately **not** attached to `build`: it is a
  review tool, and a third-party checker's opinion must not decide whether a
  release artifact exists.
  - `cppcheck`: **not executed here** — the binary is not on PATH, and the task
    fails with an explicit message rather than skipping. Install cppcheck (or
    pass `-PveltisNativeCppcheck=<path>`) to run it.
  - `gcc -fanalyzer`: **executed clean** against `native_utils.c` with MinGW-w64
    GCC 16.2.0 on 2026-11-04 (exit 0, no findings), run directly because the
    task fails earlier at the missing `cppcheck`.
- **Workflow lint.** `.github/workflows/gradle.yml` is validated with
  `actionlint` 1.7.12, **exit 0**, on 2026-11-04. Lint-clean is not
  run-clean; see [CI_SUPPORT_MATRIX.md](CI_SUPPORT_MATRIX.md).

## 5. Build-configuration coverage

| Configuration | Status (2026-11-04) |
| --- | --- |
| Release, MinGW-w64 GCC 16.2, Ninja | **Executed**: `check` 5/5 + Java suites green; this is the `verifyNativeRelease` lane |
| Debug, same toolchain | **Executed**: `check -PveltisNativeBuildType=Debug`, CTest 5/5 |
| Release, `-Werror` | **Executed**: clean |
| Release, ASan+UBSan | **Executed**: CTest 5/5 under instrumentation |
| Release, LLVM-MinGW Clang | Executed earlier (2026-11-03 evidence, prior round); not re-run in this round's report |
| MSVC / clang-cl / Linux / macOS / ARM64 | Not tested locally — CI lanes exist, never executed |

The Gradle test task and CTest share one build tree per lane
(`build/native/cmake/<platform>[-san-<list>]`), and `nativeLibraryIsStale`
compares the library's timestamp with the newest native source, so a Java suite
cannot silently load a library older than the sources it claims to test.

## 6. Fuzzing

`native/tests/fuzz_abi_inputs.cpp` is a **seeded, bounded, differential** fuzz
of the public ABI surface, and it is a normal CTest case (label `fuzz`,
`VELTIS_FUZZ_ROUNDS=4000`) so every lane runs it.

- **Seeds.** The default seed (`0x5EED2026`) is a constant in the file, so a
  green lane means *that* corpus passed. A failing run prints the seed and the
  case, and `VELTIS_FUZZ_SEED`/`VELTIS_FUZZ_ROUNDS` reproduce it exactly.
- **Bounded.** Round count is fixed; nothing allocates per round, so the
  harness cannot exhaust memory and a leak in the library cannot be confused
  with a leak in the harness.
- **Input constraints.** Every pointer is either null or inside one of three
  real, over-aligned allocations; no length that could be accepted exceeds the
  buffer it points at. This is what makes fuzzing a library whose *job* is to
  reject bad arguments safe.
- **What it looks for.** Not the individual checks (fixed cases cover those)
  but their **interaction**: length equality, then zero, then the two nulls,
  then byte extent, then alignment, then overlap. A randomized triple that
  trips two checks at once exposes an implementation that answers with the
  wrong status code, or that inspects a buffer before deciding the buffer is
  legal.
- **Why not libFuzzer.** Deliberate: this host's toolchains ship no sanitizer
  runtimes for a coverage-guided engine to lean on, and coverage guidance
  without ASan finds far less while adding a framework the module does not
  need. The corpus shape is what a libFuzzer target would consume, so adding
  one later is a wrapper, not a rewrite.

Status: **executed** (CTest 5/5 includes it, in Release, Debug and ASan+UBSan
lanes). No long-running developer campaign (`VELTIS_FUZZ_ROUNDS` in the
millions) has been recorded; that is the next step once a sanitizer lane exists
on a second platform.

## 7. Stress and lifecycle testing

Native side (inside `veltis_native_tests`, executed): 100 init/shutdown cycles;
concurrent init/shutdown/transform; shutdown while transforms are in flight;
1 MiB batches; boundary element counts; repeated failure paths (each invalid
argument class) with error-state clearing.

Java side (executed): repeated `initialize`/`shutdown`; `close()` during active
calls (the read/write lock in `LoadedLibrary`); 8 concurrent transform callers;
a failed load retried; post-shutdown fallback.

Not covered anywhere yet: allocation pressure (the library allocates nothing,
so there is currently no allocation path to press), long-running soak (>1 min
of continuous mixed calls), and any ThreadSanitizer view of the Java-side lock
hierarchy — TSan cannot run on this host (§3) and Java's own race detection
would need JCStress, which is not wired in.

## 8. What is deliberately not tested, and why

| Not tested | Reason |
| --- | --- |
| Cross-compiled artifacts (Windows ARM64, Linux AArch64, RISC-V) | The producing host cannot execute them. CTest is configured **off** in every cross preset, no `CTestTestfile.cmake` is registered, and Gradle's `testNative` refuses with `cannot run a cross-compiled build`. CI asserts both refusals as part of the cross job |
| Linux, macOS, MSVC, clang-cl, FreeBSD | No toolchain, runner or emulator on this host. Lanes exist in CI and are **never executed** — see CI_SUPPORT_MATRIX |
| Coverage-guided fuzzing | Needs sanitizer runtimes this host lacks (§6) |
| `cppcheck` | Not installed; the task fails loudly (§4) |
| Mutation testing, property-based Java testing, JCStress | No demonstrated defect class that they would catch here; adding them would be instrumentation without a question to answer |
| Benchmarks as gates | Benchmarks measure; they do not pass or fail. See BENCHMARKING |

## 9. How to run every lane locally

```bash
# Layers 1-3 in one go (needs CMake + compiler on PATH)
./gradlew :veltis-native:check -PveltisNativeTest=true -PveltisNativeGenerator=Ninja

# Debug configuration
./gradlew :veltis-native:check -PveltisNativeTest=true \
  -PveltisNativeBuildType=Debug -PveltisNativeGenerator=Ninja

# Warnings as errors
./gradlew :veltis-native:check -PveltisNativeTest=true -PveltisNativeWerror=true

# Sanitizers (own build tree; AddressSanitizer+UBSan verified to work with LLVM-MinGW)
./gradlew :veltis-native:testNative "-PveltisNativeSanitizers=address,undefined"

# Java-only layer 3 (no compiler required at all)
./gradlew :veltis-native:test -Dveltis.native.test.expectJavaOnly=true

# Layer 4
./gradlew :server:test

# Layer 0
./gradlew :veltis-native:analyzeNativeStatic          # needs cppcheck
gcc -fanalyzer -c native/src/c/native_utils.c -I native/include -o /dev/null
actionlint -no-color .github/workflows/gradle.yml

# Long fuzz run
VELTIS_FUZZ_SEED=0x1234 VELTIS_FUZZ_ROUNDS=10000000 \
  native/build/native/<platform>/native_fuzz_abi_inputs.exe
```

## 10. Adding a test for a new capability

1. Contract first: pin the signature in `abi_contract_c.c` **and**
   `abi_contract_cxx.cpp` (a changed signature must fail to compile).
2. Native: argument matrix (null, zero, mismatch, misaligned, overlapping,
   extent overflow), boundary values, differential expectation against the Java
   reference, and a lifecycle case if the capability has state.
3. Export surface: add the name to `export_check.c`'s expected list only after
   the export exists; the list is an exact-match assertion in both directions.
4. Java: one loader/binding test, one equivalence test (native vs `javaTransform`
   equivalent), and confirm the Java-only run still shows the capability as
   absent rather than failing.
5. Fuzz: if the new export takes pointers and lengths, it is already inside the
   fuzz target's argument space — extend the target's dispatch for it.
6. Re-run §9's lanes that the host can execute, and record which ones could not
   run. Do not report an unexecuted lane as passed.
