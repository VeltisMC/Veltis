# veltis-native — Foundation Audit (Phase 0)

Date: 2026-11-03. Host for all executed verification: Windows 11 x86-64
(10.0.26300), JDK 25.0.5.0.3, Gradle 9.5.1, CMake 3.31.6, Ninja 1.12.1,
LLVM-MinGW 20261020 (Clang 23.1.4), winlibs MinGW-w64 GCC 16.2.0
(msvcrt, posix, seh). Branch `Add/C_C++_Module`, HEAD `a06cf39`, never pushed.

This document is the gate for the rest of the foundation work. Nothing is
refactored until it is complete and reviewed.

## 1. Scope and method

Read in full, line by line:

- `native/include/veltis/native/veltis_native.h` (226 lines) and `veltis_native.hpp` (58)
- `native/src/c/native_internal.h` (22), `native/src/c/native_utils.c` (93)
- `native/src/cpp/native_engine.cpp` (224), `native/src/cpp/algorithms.cpp` (32)
- `native/CMakeLists.txt` (365), `native/tests/CMakeLists.txt` (59), `native/CMakePresets.json` (287)
- `native/cmake/veltis-native-build-info.json.in` (26), `native/cmake/toolchains/*.cmake` (3 files)
- `native/tests/native_tests.cpp` (520), `native/tests/export_check.c` (264), `native/tests/fixtures/{fixture,minimal}.c`
- `native/src/main/java/org/veltismc/nativeengine/*.java` — `NativeLoader` (867), `NativeEngine` (348),
  `NativeConfig` (118), `NativeStatus` (66), `NativeCapabilities` (60), `FailureLogThrottle` (94)
- `native/src/test/java/org/veltismc/nativeengine/*.java` — `NativeLoaderTest` (698), `NativeEndToEndTest` (430),
  `NativeEngineTest` (330), `FailureLogThrottleTest` (172), `NativeBenchmark` (410), `NativeConfigTest` (94),
  `NativeCapabilitiesTest` (52)
- `native/build.gradle.kts` (1149), `.github/workflows/gradle.yml` (971), `native/tools/check-pe-imports.ps1` (170)
- `native/README.md` (905), `server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java` (native section only)

The uploaded `native.zip` snapshot (1,990,715 bytes, 358 entries, 299 of them
generated build output) was inspected for comparison only. The repository
working tree is authoritative; no generated file was copied from the archive.

Module total: 8,289 lines.

Evidence labels used throughout:

- **Executed** — ran on the host above during this or the previous session, with a result observed.
- **Source-verified** — true because the code says so and the code was read; not run.
- **Assumption** — plausible, unproven, must not be documented as fact.

## 2. What the module actually is

`veltis-native` is an **optional native accelerator facade**, not a native
engine in the general sense. The complete public surface is 13 exports covering
a version handshake, a lifecycle pair, a capability bitmask, a self-test, a
last-error accessor and exactly one computation:

| Group | Exports | Source |
| --- | --- | --- |
| Handshake | `veltis_native_abi_version`, `veltis_native_version_major/minor/patch`, `veltis_native_version_string`, `veltis_native_capabilities` | `native/src/c/native_utils.c`, `native_engine.cpp:131` |
| Lifecycle | `veltis_native_init`, `veltis_native_is_initialized`, `veltis_native_shutdown` | `native_engine.cpp:100,111,122` |
| Diagnostics | `veltis_native_status_string`, `veltis_native_last_error` | `native_utils.c`, `native_engine.cpp:140` |
| Self-test | `veltis_native_self_test` | `native_engine.cpp:140` |
| Compute | `veltis_native_transform_i32` | `native_engine.cpp:158`, `algorithms.cpp` |

**No server code path calls the native library.** The only Java consumer is
`NativeEngine.transform(...)`, and the only production caller of that is the
bootstrap lifecycle (`VeltisBootstrap.initializeNative` / `stopNative`), which
loads and shuts the engine down without ever invoking a native computation.
The README's own investigation section records the decision: the profiled
hotspot is `ChunkDataCodecImpl.encode`, a byte-serialization loop, and the
correct fix is Java-side, not FFM-per-block. That finding stands and is the
reason this phase exists: the foundation must be sound whether or not a native
workload ever arrives.

## 3. Verified behavior

### 3.1 ABI and C/C++ layer

- **Source-verified:** the ABI is frozen at version 1, version string `1.0.0`,
  capability bits `TRANSFORM_I32 = 1<<0` and `SELF_TEST = 1<<1`, and status codes
  `OK=0` through `LENGTH_MISMATCH=-6`
  ([veltis_native.h](../include/veltis/native/veltis_native.h)).
- **Source-verified:** width and layout pins exist on both sides of the bridge —
  six `_Static_assert` checks in `native_utils.c` and four `static_assert`
  checks in [native_engine.cpp:10-14](../src/cpp/native_engine.cpp#L10-L14)
  (`int32_t`/`uint32_t` == 4, `uintptr_t` == `sizeof(void*)`,
  `atomic<bool>::is_always_lock_free`). A wrong-width platform fails at compile
  time rather than at runtime.
- **Source-verified:** the transform contract is
  `output[i] = (uint32_t)input[i] * 31u + salt + i (mod 2^32)`, implemented once
  in `algorithms.cpp` with `std::bit_cast<std::int32_t>` for the return
  conversion, and mirrored exactly by `NativeEngine.javaTransform`
  ([NativeEngine.java:328](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L328)).
- **Executed:** the transform's checks run in this exact order
  ([native_engine.cpp:129-193](../src/cpp/native_engine.cpp#L129-L193)) —
  engine-not-initialized, `input_length != output_length`, `input_length == 0`
  (returns OK before any pointer is inspected), null input, null output,
  byte-extent overflow, input alignment, output alignment, range overlap — and
  only then runs the algorithm. Order is part of the contract because it decides
  which message an operator sees; `test_null_and_zero_length_matrix` and
  `test_alignment_overlap_and_overflow` pin it, including `SIZE_MAX` rejection.
- **Executed:** `veltis_native_transform_i32` never allocates. Grep over
  `native/src/cpp` and `native/src/c` for `malloc|new|thread|mutex` returns
  exactly two hits — the `is_always_lock_free` static assert and the
  `naturally_aligned` helper. **The native library performs zero heap
  allocation, starts zero threads and takes zero locks.** Every buffer is
  caller-owned.
- **Source-verified:** C++ exceptions do not cross the boundary. All six exports
  in `extern "C"` ([native_engine.cpp:69-195](../src/cpp/native_engine.cpp#L69-L195))
  are wrapped in `try`/`catch`, and the two internal bridge symbols
  `veltis_native_set_last_error` and `veltis_native_clear_last_error`
  ([native_internal.h:15-16](../src/c/native_internal.h#L15-L16)) are asserted
  absent from the built binary by `tests/export_check.c`.
- **Executed:** `veltis_native_last_error` is thread-local (`__declspec(thread)`
  on MSVC-family, `_Thread_local` elsewhere, `#error` otherwise), buffer 256
  bytes, cleared on success. The 8-thread CTest case proves per-thread isolation.

### 3.2 Java integration layer

- **Executed:** `verifyNativeRelease` runs green end-to-end on Windows x86-64
  with LLVM-MinGW Clang 23.1.4 and, separately, with winlibs MinGW-w64 GCC
  16.2.0 (2026-11-03). Both produce a library, pass CTest 2/2, pass
  `NativeEndToEndTest`, and pass the artifact/manifest/source-archive gates.
- **Source-verified:** the load sequence in `NativeLoader.tryLoad` is ABI
  version first, then engine major/minor/patch, then version string, then a
  `status_string` probe, then `Bindings.bind` — so a foreign library is refused
  before any downcall handle is created
  ([NativeLoader.java:327-368](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L327-L368)).
- **Source-verified:** `size_t` maps to `ValueLayout.JAVA_LONG`, not `JAVA_INT`
  ([NativeLoader.java:131-142](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L131-L142)).
  The comment records the reason; the FFM descriptor is the only place this
  could silently break.
- **Executed:** a native call takes the `NativeEngine` read lock and a
  `LoadedLibrary` read lock; `shutdown()`/`close()` take the matching write
  locks. `NativeEndToEndTest.closeWaitsForInFlightCallsAndRejectsLaterOnes` and
  `shutdownWhileCallsAreActiveNeverProducesWrongResults` run real overlapping
  transforms and assert no wrong result is ever returned.
- **Executed:** repeated failures are bounded. `FailureLogThrottle`
  ([FailureLogThrottle.java](../src/main/java/org/veltismc/nativeengine/FailureLogThrottle.java))
  allows a 3-failure burst then one message per 60 s window carrying the
  suppressed count. `NativeEngineTest.repeatedNativeTransformFailuresDoNotWriteAWarningPerCall`
  asserts 23 suppressed; `FailureLogThrottleTest` (7 cases, virtual clock)
  covers window arithmetic, backward clocks, zero interval and 16-thread
  shared-window accounting.
- **Executed:** server startup never fails because of native.
  `VeltisBootstrap.initializeNative` catches `Throwable` and logs;
  `NativeBootstrapTest` passes 12/12 covering all nine documented failure
  shapes.
- **Executed:** Java-only builds need no CMake and no compiler. Tool resolution
  happens inside `doFirst`, so configuration and `compileJava` succeed with no
  native toolchain on `PATH` — verified by the `-DveltisNativeTest=false`
  path and by running with `PATH` stripped.

### 3.3 Packaging and artifacts

- **Executed:** the manifest for each built library records the recomputed
  SHA-256, the container format and architecture read back through
  `NativeLoader` (the same code the server runs), and the ABI fields read from
  the public header. Fields CMake cannot observe at configure time are absent
  rather than guessed.
- **Executed:** the Windows import check proves the runtime policy. MSVC and
  clang-cl get `/MT`, LLVM-MinGW gets `-static-libgcc -static-libstdc++`,
  MinGW-w64 GCC gets the driver-level `-static`. That last spelling is the only
  one that also removes `libwinpthread-1.dll`; the two earlier attempts
  (`-Wl,-Bstatic,-lwinpthread,-Wl,-Bdynamic`) produced a byte-identical DLL that
  still imported it.
- **Executed:** `packageNativeSourceArchive` builds from `git ls-files`, and
  `verifyNativeSourceArchive` extracts, configures and builds the archive from
  scratch on this host.

## 4. Architectural decisions worth preserving

1. **The header is the single ABI authority.** CMake derives the export list,
   ABI version and export count by parsing `veltis_native.h`
   ([CMakeLists.txt:251-276](../CMakeLists.txt#L251-L276)) instead of maintaining a
   parallel list, so a signature change and a manifest change cannot drift apart.
2. **Explicitly-declared exports only** — hidden visibility plus
   `LINKER:--no-undefined` — so an accidental symbol becomes a link error rather
   than a support contract.
3. **Caller owns every buffer.** This removes cross-boundary ownership, free
   functions and allocator matching from the problem space entirely. It is the
   correct v1 decision.
4. **One bulk call, not one call per element.** `nativeTransform` allocates a
   confined arena, does two `MemorySegment.copy` operations and one downcall.
5. **Status codes, never exceptions, across the ABI**, plus a thread-local
   last-error string that describes the calling thread's last call.
6. **Binary probing before loading.** `probePe`/`probeElf`/`probeMachO`/`probeFat`
   read container headers by random access and refuse a library for another OS
   or architecture before `System.loadLibrary` semantics are involved — proven
   by the three loader cases added last session.
7. **Reproducible links** (`/Brepro`, `-Wl,--no-insert-timestamp`; Mach-O keeps
   its required `LC_UUID`), so an artifact digest is a meaningful identity.
8. **Idempotent, lock-guarded lifecycle on both sides**, so a mis-ordered
   integration cannot produce a half-open library.

## 5. Gaps

### 5.1 Architecture (Phase 1)

- The C/C++ tree is flat: `src/c` and `src/cpp` only. There is no `abi`, `core`,
  `runtime`, `diagnostics`, `platform/*` or `architecture/*` boundary.
- `native_engine.cpp` mixes three responsibilities in 224 lines: C ABI
  marshalling, argument validation, and engine state. That is a real coupling
  problem only if more capabilities are added; splitting it now, with one
  compute export, would be cosmetic.
- **Decision recorded:** do not move files for layout's sake. The target layout
  belongs in `ARCHITECTURE.md` as the contract new code follows, and
  directories get created when a capability actually lands in one.

### 5.2 ABI contract (Phase 2)

- The contract is documented in comments scattered across the header, not in a
  dedicated document. Calling convention (`VELTIS_NATIVE_CALL` is `__cdecl` only
  on 32-bit Windows and empty elsewhere), boolean representation (`int32_t`, not
  `bool`), and the "no struct passes the boundary" rule are all real rules with
  no single place that states them.
- **There are no structs in the ABI at all.** Several requested "struct size and
  alignment" compatibility tests are therefore vacuous for v1. The honest version
  is a test that asserts the ABI contains no aggregate parameters, plus the
  width static asserts, so the test becomes meaningful the day a struct is added.
- `VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL` is declared but **never returned by the
  real library** — grep shows it only in `status_string` tests and the fixture.
  That is dead-but-contractual surface; document it, do not delete it.
- `ERROR_UNSUPPORTED` is returned only by the deliberate fixtures, never by the
  production library.

### 5.3 Memory (Phase 3)

- There is no allocator, no aligned allocation helper, no arena and no pool.
  That is **by design**, not an oversight: the native side never allocates, and
  the Java side uses `Arena.ofConfined()` per call.
- The requested overflow-safe arithmetic already exists in two places and needs
  no new infrastructure: `checked_byte_extent`
  ([native_engine.cpp:65](../src/cpp/native_engine.cpp#L65)) and
  `LoadedLibrary.requireExtent` using `Math.multiplyExact`.
- The one genuine gap: the **Java production path allocates 8 bytes per element
  per call** (input segment + output segment + result array). `~8 B/elem` is
  measurable and is the reason `native-engine` never beat `java-optimized` in
  the benchmark. That is an integration-shape problem (Phase 7/9), not a
  native-memory-infrastructure problem. Inventing a native allocator here would
  violate the order's own constraint.

### 5.4 Threading and lifecycle (Phase 4)

- `Engine::initialize()` stores one `atomic<bool>`. There is nothing to roll
  back, so "partial-init rollback" is currently a vacuous requirement — the
  honest answer is that initialization is a single flag store and cannot be
  partial, and the lifecycle document should say that instead of describing a
  rollback that does not exist.
- `veltis_native_shutdown()` does **not** wait for in-flight native calls. The
  protection is entirely Java-side (`ReentrantReadWriteLock` in `NativeEngine`
  and in `LoadedLibrary`). A non-Java consumer calling `shutdown` concurrently
  with `transform_i32` gets no native-side guarantee. Today the transform reads
  only caller memory plus the flag, so there is no use-after-free, but the
  contract must state this rather than imply symmetry.
- **Native state is process-wide; Java models it per `LoadedLibrary`.** Two
  `LoadedLibrary` instances mapping the same DLL share one init flag and one
  thread-local error buffer. `close()` on one therefore shuts the engine for the
  other. Correctness survives because the other instance falls back to Java, and
  `test_shutdown_while_transforms_run` documents the shape, but nothing
  documents that the library is a process singleton. That is a real, if narrow,
  contract gap.
- No native background thread exists, and none may be added without a workload.

### 5.5 Platform and CPU (Phase 5)

- No CPU feature detection, no dispatch, no `architecture/` implementations. The
  algorithm is one integer loop; the compiler autovectorizes it. Adding SIMD
  intrinsics now would be pure ceremony and the order forbids it.
  **Phase 5 outcome:** kept, and written up as a decision with measurements and a
  nine-condition gate in `CPU_DISPATCH.md` — see §11 "Phase 5".
- `windows-x86_64-msvc` and `-clang-cl` presets exist and are **unrunnable on
  this host** (no Visual Studio installed). They are source-verified only.
- Linux glibc/libstdc++ floors are recorded by CI from the built artifact, not
  invented — the CI step exists and has never run here.
- `VELTIS_NATIVE_MINIMUM_OS` is empty for Windows and Linux at configure time
  ([CMakeLists.txt:516-518](../CMakeLists.txt#L516-L518)); the comment already
  calls that the honest answer. Keep it.

### 5.6 Build system (Phase 6)

- **Configure-time build-info JSON.** `configure_file` used to run at configure
  and write into `VELTIS_NATIVE_OUTPUT_DIR`
  ([CMakeLists.txt:537-549](../CMakeLists.txt#L537-L549) now renders into the
  build tree and the `veltis_native_build_info` target copies it beside the
  library). Gradle's stale-output
  pruning can remove that file while the library remains, which previously broke
  `packageNative`. Fix: make the JSON a real build-byproduct (or regenerate it
  as part of the build), not a configure side effect.
- **`providers.exec` swallows CMake stderr.**
  [build.gradle.kts:727-731](../build.gradle.kts#L727-L731) reports only
  "non-zero exit value 1". That is exactly what made the last `verifyNativeSourceArchive`
  failure take a full session to diagnose. Capture and print the child output on
  failure.
- **Untracked-file trap.** `packageNativeSourceArchive` lists `git ls-files`.
  `FailureLogThrottle.java` and `FailureLogThrottleTest.java` are currently
  untracked, so the freshly extracted `native/build/native/source-check/` tree
  on disk does not contain them. That is correct behavior — but it means anyone
  who forgets `git add` ships an archive missing a compiled source. The verify
  step should fail loudly on a required-source mismatch rather than relying on
  the five hard-coded names at
  [build.gradle.kts:690-696](../build.gradle.kts#L690-L696).
- **Stale generated trees.** Several `native/build/native/**` snapshots from
  earlier sessions are still on disk. They are ignored by git, but Java tests
  that resolve a default search path can pick up an old library and produce a
  green run that proves nothing about current sources. The order's rule "Do not
  depend on stale Gradle output snapshots" applies directly; `cleanNative` and an
  explicit "built in this session" marker are the fix.

### 5.7 Java FFM (Phase 7)

All three questions below are resolved; see §11 "Phase 7" and
`JAVA_FFM_INTEGRATION.md`. They are left in place because the answers are the
audit's evidence trail.

- `STRING_READ_LIMIT = 4096` reinterprets a returned pointer to a fixed bound
  before `getString`; a non-NUL-terminated 4 KB+ buffer would read the limit
  rather than fault, but nothing documents that the native side always NUL-
  terminates within 256 bytes. It does — `native_utils.c`'s buffer is 256 — so
  the limit is safe by accident of two unrelated constants. Make it explicit.
- `transform()` copies `int[]` in and out element-wise through
  `MemorySegment.copy`, which is correct and bulk. No per-element downcall exists.
- `NativeConfig` precedence (`-D` over `server.properties`) and invalid-boolean
  fallback are tested; `veltis.native.path` resolution is documented in README
  only, not in a dedicated integration document.

### 5.8 Testing, sanitizers, fuzzing (Phase 8)

- 18 native test functions, ~30 loader tests, 11 end-to-end tests, 7 throttle
  tests — a genuinely good base. Gaps:
  - **No sanitizer build has ever run here.** ASan/UBSan on the Windows library
    and TSan on the concurrent cases are unexecuted. Linux ASan/UBSan/TSan via
    CI is defined but this branch has zero CI runs.
  - **No fuzzing at all.** `transform_i32` with attacker-controlled length and
    pointers is a legitimate target for a bounded harness; there is none.
  - **No allocation-failure tests** — and none are meaningful while the native
    side allocates nothing. Record that as an explicit unavailable case.
  - The `SIZE_MAX`/overflow cases are covered, but there is no test for a
    library that returns a status code outside the documented enum.
- **Zero CI evidence.** The branch has never been pushed, so every `.github/
  workflows/gradle.yml` job — `native-host`, `native-cross`, `native-debug`,
  `native-freebsd-investigation` — is source-verified and nothing more. No run
  may be described as passing.

### 5.9 Benchmarks (Phase 9)

- `NativeBenchmark` is a real harness (4 implementations, 11 sizes, 3 rounds,
  warmup, percentile reporting, `ThreadMXBean` allocation bytes, an explicit
  timer-overhead sample, and correctness assertion before timing). It is not JMH
  and does not control frequency or JIT order.
- **The README's reference table is stale.** Those numbers were produced against
  the 2026-10-31 library; two fresh toolchain builds exist now and have not been
  benchmarked. Any refresh must re-run, not re-quote.
- Missing from the current shape: no regression comparison against a recorded
  baseline file, no bytes/s or ops/s column, no native-only (no-FFM) row, no
  p95/p99 consistently across all four implementations.

### 5.10 Documentation and CI (Phases 10–11)

- `native/docs/` did not exist until this audit. 14 of the 15 required documents
  are still missing.
- README carries **two now-false statements**: the "stale 2026-10-31 library"
  caveat at [README.md:423-425](../README.md#L423-L425) and the "Currency of that
  run" paragraph at [README.md:617-624](../README.md#L617-L624), both of which
  assert that nothing has been recompiled. Fresh LLVM-MinGW and MinGW-w64 GCC
  builds now exist and pass. Correct them.
- **CI has no MinGW-w64 GCC host lane.** The matrix covers MSVC, clang-cl and
  LLVM-MinGW on Windows. The exact bug that cost the most time this project —
  `libwinpthread-1.dll` silently reappearing under a `staticRuntime: true`
  manifest — is a MinGW-w64 GCC-only failure mode and no CI job can catch it.
  Adding that lane, with the import-policy assertion, is the highest-value CI
  change available.
- Cross-compile jobs must be labeled as compile-only. They already assert a
  local-execution guard, but the job names do not tell a reader that nothing ran.

## 6. Assumptions currently stated as facts

1. "Linux builds link system `libstdc++`, `libgcc_s`, `libm`, `libc`" — the CI
   check enforces that list, but this host has never produced a Linux artifact.
2. "The macOS floor is 11.0" — link-time `vtool` evidence, never an execution.
3. The `windows-x86_64-msvc` and `clang-cl` presets are assumed to configure
   correctly; no Visual Studio host has run them.
4. The README's ~25x `ByteBuffer` codec speed-up is a one-off temporary
   harness result, not a checked-in, reproducible measurement.
5. `NativeBenchmark`'s "41.6 ns FFM downcall" is a single-host sample; portable
   only as an order of magnitude.

Each must be labeled in the final report with one of: build and runtime tested,
cross-compiled and artifact verified, build tested only, not tested, unsupported.

## 7. Risk register

| # | Risk | Likelihood today | Impact | Mitigation path |
| --- | --- | --- | --- | --- |
| R1 | `libwinpthread`/shared-CRT regression ships silently | Medium (no CI lane) | Server fails to start the library on a clean Windows box | Phase 10: pinned MinGW-w64 GCC lane + import assertion |
| R2 | Gradle stale-output pruning deletes build-info JSON | **Already happened once** | `packageNative` fails or ships an incomplete manifest | Phase 6: make it a build byproduct |
| R3 | CMake failure surfaces as "exit value 1" | Present | Debugging cost, misdiagnosis | Phase 6: capture stderr |
| R4 | Untracked source silently missing from archive | Present | Released archive does not build | Phase 6: required-source set from compile inputs |
| R5 | Stale `native/build/native/**` library gives a false green | **Present on this host** | Wrong conclusions recorded as verified | Phase 6: session marker + clean-first verification |
| R6 | Process-wide native state vs per-`LoadedLibrary` Java model | Low (one shared instance today) | A second loader consumer breaks the other's engine | Phase 4: document the singleton contract |
| R7 | Native `shutdown` does not wait for in-flight calls | Low (transform is stateless) | A future stateful capability becomes a UAF | Phase 4: lifecycle document + guard before adding state |
| R8 | Docs describe infrastructure that does not exist (rollback, allocators, struct ABI) | High if written carelessly | Reviewers trust a false contract | Phases 2–5: state vacuity explicitly |
| R9 | Zero CI runs for an entire feature branch | Certain | No platform claim is defensible | Report honestly; label every non-host result unverified |

## 8. Phase mapping

| Phase | Deliverables | Precondition from this audit |
| --- | --- | --- |
| 1 | `ARCHITECTURE.md`, `MODULE_BOUNDARIES.md` | Target layout is a rule for new code, not a move of old code (§5.1) |
| 2 | `ABI_CONTRACT.md` + 10 ABI tests | Struct tests are vacuous; dead status codes documented, not deleted (§5.2) |
| 3 | `MEMORY_MODEL.md` + tests | No native allocator; caller-owned buffers; Java-side copy cost is the real finding (§5.3) |
| 4 | `THREADING_MODEL.md`, `RUNTIME_LIFECYCLE.md` | Flag-only init; Java-side lock authority; process singleton (§5.4) |
| 5 | `PLATFORM_SUPPORT.md`, `CPU_DISPATCH.md` | No SIMD today; every non-Windows floor measured or marked unverified (§5.5) |
| 6 | `BUILD_SYSTEM.md` + R2/R3/R4/R5 fixes | Concrete defects listed above |
| 7 | `JAVA_FFM_INTEGRATION.md` | §5.7 checklist |
| 8 | `TESTING_STRATEGY.md`, `SECURITY_MODEL.md` | Sanitizer/fuzzing gaps and explicitly unavailable combinations |
| 9 | `BENCHMARKING.md` | Stale README table; add baseline comparison and the missing columns |
| 10 | `CI_SUPPORT_MATRIX.md` + MinGW-w64 GCC lane | R1, R9 |
| 11 | README refresh + `native/docs/` completion | Two false README statements; 14 missing documents |

## 9. Explicit non-goals of this foundation

- No new native workload. No native code exists to make the module look bigger.
- No native allocator, arena, pool or lock-free structure without profiling
  evidence that does not exist.
- No thread pool, no background thread.
- No SIMD implementation to populate `architecture/`.
- No new export or removal of an existing one.
- No change to the chunk codec or any Minecraft-facing behavior.
- No directory moves performed for cosmetic cleanliness.
- No commit, tag, push, or release.

## 10. Verification actually executed for this audit

- `git status --porcelain` / `git ls-files native` — inventory and tracked-file
  set, including the untracked throttle classes.
- Grep over `native/src/**` for allocation, threading and locking primitives —
  the "native never allocates or threads" finding.
- Grep for `BUFFER_TOO_SMALL` / `ERROR_UNSUPPORTED` — dead status surface.
- `wc -l` over the module — 8,289 lines.
- Prior-session Executed results carried forward: `verifyNativeRelease` green
  under LLVM-MinGW Clang 23.1.4 and MinGW-w64 GCC 16.2.0 on 2026-11-03; CTest
  2/2 both; `NativeBootstrapTest` 12/12; Java-only suite with no toolchain on
  `PATH`; ARM64 cross-compile succeeds and never executes.

Not executed anywhere in this audit: any Linux, macOS, MSVC, clang-cl, FreeBSD,
RISC-V or AArch64-native run; any sanitizer; any fuzzer; any CI workflow.

## 11. Post-audit changes (appended as later phases land)

### Phase 1 — architecture and module boundaries

Delivered `ARCHITECTURE.md` and `MODULE_BOUNDARIES.md`. No file was moved, and
no new `src/` directory was created: §5.1 established that a layout move here
would be cosmetic. The documents carry the target layout as a rule for new code,
with explicit trigger conditions for creating `memory/`, `platform/*`,
`architecture/*`, `runtime/` and `core/`, and §7 of `ARCHITECTURE.md` records the
five debt items instead of pretending they are resolved.

Two corrections found while writing them, both verified against the source:

- the zero-length case returns `OK` **before** the null-pointer checks, so
  `NULL, 0` is a defined no-op rather than a rejection;
- the TLS keyword selection lives in `native_utils.c:14-20`, not
  `native_internal.h`.

### Phase 2 — ABI contract and compatibility tests

Delivered `ABI_CONTRACT.md` plus four of the ten required tests that did not
exist before:

| New | What it proves |
| --- | --- |
| `tests/abi_contract_checks.h` | widths, alignment of the exposed primitive set, frozen enum values, and every exported signature pinned as a function-pointer type — shared by both languages |
| `tests/abi_contract_c.c` (CTest `veltis_native_abi_contract_c`) | the header compiles as C11; the library's self-reported version/ABI/capabilities/status domain match the header; an **independent C reference** verifies the transform formula; buffer-size, overlap, extent-overflow and error-slot rules hold |
| `tests/abi_contract_cxx.cpp` (CTest `veltis_native_abi_contract_cxx`) | the same in C++20, plus `decltype` return-type pins that only a C++ compiler can state |
| `tests/export_check.c` additions | unexpected-symbol rejection: 11 allocator-shaped and `_v2`/`_ex` variant names must not resolve, alongside the existing 2 internal bridge symbols |

Also fixed while there: `export_check.c` cast a `FARPROC` straight to `void*`,
which GCC rejected under `-Wpedantic`; it now goes through `uintptr_t`.

Test 9 (struct size and alignment) is **documented as vacuous** rather than
invented: the ABI exposes no aggregate. The contract states the rule a future
struct must satisfy, and the assertions that do exist cover the primitives the
boundary actually uses.

Executed verification after Phase 2:

- CTest 4/4 under LLVM-MinGW Clang 23.1.4 (C11 + C++20).
- CTest 4/4 under winlibs MinGW-w64 GCC 16.2.0 (msvcrt-posix-seh), warning-free.
- `:veltis-native:verifyNativeRelease` green: `testNative` 4/4, manifest verified
  (PE/x86_64, 517,632 bytes, abi 1, 13 exports), source archive rebuilt from
  scratch, integration suite 80 tests / 0 failures / 1 tolerated skip.
- Nothing was committed; the branch is still `Add/C_C++_Module` at `a06cf39`.

One new observation for Phase 6: the source archive still reports **31 tracked
files**, because `packageNativeSourceArchive` lists `git ls-files` and the Phase 2
test files are untracked. The archive configures with `VELTIS_NATIVE_BUILD_TESTS=OFF`,
so this is not a broken release today, but it is risk R4 in a fresh costume.

### Phase 3 — memory model

Delivered `MEMORY_MODEL.md` and one piece of genuinely reusable infrastructure.
No allocator, pool, arena or lock-free structure was introduced, because §5.3
established there is nothing to allocate: the zero-match grep is now recorded in
the document as a reproducible check rather than a claim.

- **New:** `src/cpp/native_buffer_checks.hpp` — `checked_byte_extent<T>`,
  `naturally_aligned<T>` and `byte_ranges_overlap` moved out of the anonymous
  namespace in `native_engine.cpp` into `veltis::native::detail`, with the extent
  helper made a template so a future element type reuses it. The call sites in
  `native_engine.cpp` are unchanged in behavior; the now-unused `<limits>` include
  was dropped.
- **New test:** `native_tests.cpp:test_buffer_predicates_directly` tests the
  predicates as functions, reaching what an export cannot: the exact
  `SIZE_MAX / sizeof(T)` boundary, refusal leaving the caller's out-parameter
  untouched, every misalignment offset 1..3, touching ranges (which must **not**
  be rejected), a zero-length range at the same address, and an extent that would
  wrap the address space. The overlap cases use synthesized addresses, because
  forming a pointer 1 KB past a 16-byte array is undefined behaviour — GCC said so
  under `-Warray-bounds` before the test was rewritten.
- `tests/CMakeLists.txt` gives `native_tests` the library's `src/cpp` directory as
  a PRIVATE include, and `CMakeLists.txt` adds it to the library's PRIVATE set.

### Risks R3 and R4 — both materialized, both fixed

Writing the Phase 3 header produced the exact failure the audit predicted, which
is the strongest possible confirmation that the two were real:

1. `verifyNativeRelease` failed at `verifyNativeSourceArchive` reporting only
   *"Process 'command cmake.exe' finished with non-zero exit value 1"*.
2. The cause was invisible until the output was captured: the archive is built
   from `git ls-files`, so `native_buffer_checks.hpp` — a build input the library
   `#include`s — was not in it, and the extracted tree could not compile.

Fixes in `native/build.gradle.kts`:

- **R3:** `providers.exec` cannot be redirected through its `Provider`, and
  rejecting that attempt produced a second failure
  (*"Could not create provider for value source ProcessOutputValueSource"*). The
  archive configure/build step now runs through `ProcessBuilder` with
  `redirectErrorStream(true)`, and a non-zero exit raises a `GradleException`
  containing the command and the child's full output.
- **R4:** `packageNativeSourceArchive` gained a `doFirst` that runs
  `git ls-files --others --exclude-standard` and fails with the list of any
  untracked, non-ignored build input (`.c .h .hpp .cc .cpp .cxx .in .cmake`,
  `CMakeLists.txt`, `CMakePresets.json`) telling the operator to add or remove it.
  Git's own ignore rules decide what counts, so build output stays out of the
  comparison. The required-file list in `verifyNativeSourceArchive` now names the
  shared header and the three Phase 2 test units.
  **This claim was overstated when written.** The extension filter used
  `Regex.matches`, which anchors the whole input, so it could only ever match a
  file literally named `.cpp`: the guard as shipped could not fire, and the
  "untracked input" case was never actually reproduced after the fix. Phase 6
  found and fixed this with both directions executed — see §11 *Phase 6*. The
  staging of `native_buffer_checks.hpp` was real, so the archive itself was
  complete; only the protection was missing.
- The six untracked build inputs (the new header, the three ABI test files, and
  the two `FailureLogThrottle` classes from the previous session) were **staged
  with `git add`; nothing was committed**. The archive went from 31 to 37 files.

Executed after the fixes, both lanes:

| Lane | CTest | Manifest | Source archive | Integration suite |
| --- | --- | --- | --- | --- |
| LLVM-MinGW Clang 23.1.4 | 4/4 | 517,632 B, 13 exports, abi 1 | 37 files, rebuilt from scratch | 80 tests, 0 failures, 1 tolerated skip |
| MinGW-w64 GCC 16.2.0 | 4/4 | 295,661 B, 13 exports, abi 1 | same 37-file sha256 | 80 tests, 0 failures, 1 tolerated skip |

The source archive hash is identical across the two compiler lanes, which is the
reproducibility property the packaging was built to have. Both builds are
warning-free under `-Wall -Wextra -Wpedantic`.

Remaining audit items unchanged: R2 (configure-time build-info JSON), R5 (stale
`native/build/native/**` snapshots), R8, R9.

### Phase 4 — threading and runtime lifecycle

Two documents added: `THREADING_MODEL.md` and `RUNTIME_LIFECYCLE.md`. No source
file changed except one new test, because the audit's finding was that this area
is already correct and simply undocumented. What the phase established:

- **The library creates no threads and holds no locks.** Grepped
  `src/c`, `src/cpp` and `include` for `std::thread`, `std::jthread`,
  `pthread_create`, `CreateThread`, `_beginthread`, `std::async`, `detach`,
  `thread_pool`, `std::mutex`, `lock_guard`, `scoped_lock`,
  `condition_variable` and `semaphore`: **zero matches**. `std::atomic` appears
  twice — the flag declaration and the `is_always_lock_free` assert. `std::thread`
  exists only in `tests/native_tests.cpp`, which is why the absence claim needs
  the grep recorded rather than left to a reader's impression.
- **Acquire/release on the lifecycle flag currently publishes nothing** (there is
  no associated data). The orderings are kept deliberately, and the threading
  document states why: they are the correct spelling for the first capability
  that adds shared state, and a relaxed load would become a bug silently at that
  moment.
- **The Java lock hierarchy is two locks, always taken downward**:
  `NativeEngine.lifecycle` → `LoadedLibrary.lifecycle`. `LoadedLibrary` cannot
  reach `NativeEngine`, so no cycle is expressible. Operations take *read* locks,
  so native code must assume concurrent entry — the rule a future stateful
  capability has to obey, now written down.
- **No re-entrant deadlock on `close()`**, because none of the 13 exports takes a
  callback. That argument is recorded as something a future callback must
  re-prove, not as a permanent property.
- **Publication order `library` → `status` is load-bearing.** Reversing it makes a
  racing `transform()` with fallback disabled throw `IllegalStateException` on a
  server whose native support just came up; the current order makes the same race
  fall back to `javaTransform`. Documented in the lifecycle document §4 with both
  orders compared.
- **`initialized = true` before `loadLocked()`** is not a partial publication:
  `initialized` means "the one autoload question has been answered", and `library`
  is the only field that gates operations. Consequence stated plainly: after a
  failed `initialize()`, retry with `load()`, not a second `initialize()`.
- **`shutdown()` nulls `library` before calling `close()`**, and `close()` takes
  the library write lock — the same window guarded from two sides.
- **§5.4's "partial-init rollback is vacuous" finding was right, and narrow.**
  Natively it is vacuous (one flag store). Java-side it is *not*: `loadLocked()`
  has three refusal branches that each close an unpublished handle. Two are
  reachable and tested; the third (capability query throws) is **unreachable by
  construction**, because `Bindings.bind` resolves every symbol before
  `LoadedLibrary` exists. That is now labelled "untested by construction" instead
  of being described as covered.
- **R6 and R7 are closed as documentation, not as code.** Native state is
  process-wide while Java models it per `LoadedLibrary`; the server uses only
  `NativeEngine.shared()` (verified: `new NativeEngine(` appears in main sources
  exactly once, in the `SHARED` initializer), and the Java test task runs one
  forked JVM sequentially (`build.gradle.kts:367-369` sets `useJUnitPlatform()`
  with no `maxParallelForks`). The practical constraint worth remembering —
  enabling parallel test forks would make lifecycle tests order-dependent through
  native state — is recorded in `THREADING_MODEL.md` §9.

One new test, `NativeEngineTest.concurrentLifecycleCallsPublishOneConsistentState`
(8 threads × 60 rounds; even threads call `initialize`, odd threads deliberately
use the *lock-free* `status()`/`nativeAvailable()` reads and then transform). It
asserts every initializer observes one identical `NativeStatus` instance, that a
lock-free observer never sees a null status, a `nativeAvailable()`/`status()`
disagreement, or a wrong transform result, and that the engine ends with no
library published. Scope limit, stated in the test itself: with a *missing*
library the disagreement branch is a guard that cannot fire, so the paired-read
case against a live library remains covered by
`NativeEndToEndTest.shutdownWhileCallsAreActiveNeverProducesWrongResults` rather
than by this unit.

Two verification gaps are left open, both recorded in `THREADING_MODEL.md` §12
rather than glossed: the concurrency evidence is correctness-based, **not
ThreadSanitizer-based** (TSan is Linux-only here and cannot combine with ASan on
the Windows host), and nothing gates the no-thread rule the way
`tests/export_check.c` gates the export set.

Stage-end verification, executed on the MinGW-w64 GCC 16.2.0 lane after these
documents landed:

| Check | Result |
| --- | --- |
| CTest | 4/4 passed (`veltis_native_tests`, `veltis_native_exports`, `veltis_native_abi_contract_c`, `veltis_native_abi_contract_cxx`) |
| `:veltis-native:test -PveltisNativeTest=true` | 81 tests, 0 failures, 0 errors, 1 skipped (`javaOnlySuiteHasNoNativeFixtureConfigured`, the intended guard) |
| `NativeEndToEndTest` | 11/11 against the freshly built real library, 0 skipped |
| `NativeEngineTest` | 18/18, including the new lifecycle race test |

### Phase 5 — platform and CPU

Two documents added: `PLATFORM_SUPPORT.md` and `CPU_DISPATCH.md`. This phase is
mostly *measurements* — the platform configuration already existed — but the
measurements found three real defects, all in the verification layer rather than
the library, and all fixed.

**Defects found and fixed**

- **`buildNative` could not see a change of compiler or runtime policy, and
  reported the previous artifact UP-TO-DATE.** It declared `nativeSources`,
  `platform` and `buildType` as inputs; neither the compiler identity nor
  `-PveltisNativeStaticRuntime` reaches it. The first runtime-policy run therefore
  inspected a Clang-built DLL while reporting the GCC lane — a check that had
  passed on bytes it did not describe. Fixed by declaring
  `build/native/cmake/<platform>/CMakeCache.txt` as an input
  ([build.gradle.kts:298](../build.gradle.kts#L298)); the cache is the fingerprint
  of the compile and link configuration CMake actually generated. Proven by the
  next run, which re-linked and then *failed* with the real dynamic imports.
- **`verifyNativeRuntimePolicy` read the runtime policy from the Gradle property**
  — what was requested — instead of from the cache, i.e. what was linked. Same
  class of bug, same fix.
- **`-PveltisNativeStaticRuntime=ON` would have configured a dynamic link.**
  `configureNative` converted the property with Kotlin's `String.toBoolean()`,
  which accepts only `"true"`, so `ON` (the spelling CMake itself is documented
  with) became `OFF` silently. The property is now normalised against CMake's own
  accepted spellings and rejects anything else outright
  ([build.gradle.kts:96-118](../build.gradle.kts#L96-L118)).
- **Two Windows lanes were bitten by the same generator trap.** With no Visual
  Studio, CMake's Windows default is NMake, so a MinGW or LLVM-MinGW configure
  dies inside `project()` with `'nmake' '-?' … no such file or directory` — a
  message that names no compiler and reads like a toolchain bug. `effectiveNativeGenerator()`
  now selects Ninja when none was named, Ninja exists, and the target is Windows;
  an MSVC host without Ninja keeps CMake's choice, and CI is unaffected because
  every lane names its generator.
- **A second PE-import policy implementation was written and then deleted.** The
  first version of `verifyNativeRuntimePolicy` carried its own allow/forbid lists
  and parsed `llvm-readobj`/`objdump` output. [native/tools/check-pe-imports.ps1](../tools/check-pe-imports.ps1)
  already is this repository's authority — dependency-free PE parsing, MinGW *and*
  MSVC rules, machine-field check, already wired into CI. Two implementations of
  one policy can drift, so the Gradle task now shells out to that script
  (`-AllowMsvcruntime` exactly when the cache says the link is dynamic) and the
  duplicate lists are gone. `libatomic` was added to the script's runtime pattern
  while doing this.

**New executed evidence** (all 2026-11-03; full tables in
`PLATFORM_SUPPORT.md` §3.3 and `CPU_DISPATCH.md` §4)

- `windows-x86_64` LLVM-MinGW Clang 23.1.4 lane promoted to **Native build and
  runtime tested**: CTest 4/4 and 81 Java tests, 0 failures, against the
  Clang-built DLL.
- `windows-aarch64` cross artifact promoted to **Cross-compiled and artifact
  verified**: machine `0xAA64`, 13 exports, no compiler runtime imported, never
  executed.
- Four import tables measured, including two deliberate negative cases — the GCC
  dynamic build rejects with `libgcc_s_seh-1.dll`/`libstdc++-6.dll`, the LLVM-MinGW
  dynamic build with `libc++.dll`/`libunwind.dll`. The winpthread class of
  regression now has a failing-direction proof, not only a passing one.
- Baseline-ISA claims are disassembly results, not assumptions: the shipped
  x86-64 artifacts use `%xmm`/legacy SSE2 with zero VEX/AVX forms, the aarch64
  artifact uses ASIMD with zero SVE/SME. Both compilers report vectorizing the one
  loop (16-byte / width 4).
- Still unmeasurable here, and recorded as such rather than worked around: Linux
  and macOS artifacts (no WSL, no containers, no macOS host), MSVC and clang-cl
  lanes (no Visual Studio), and therefore **any** GLIBC/GLIBCXX or Windows-version
  floor. No CI run has ever executed these workflow definitions.

CPU dispatch is documented as a deliberate absence with a gate, per the audit's
own §5.5 finding; no `architecture/` directory was created, and the trigger rule
in `CPU_DISPATCH.md` §5 still governs when one may appear.

Stage-end verification, executed after the fixes above, on the restored
MinGW-w64 GCC 16.2.0 lane:

| Check | Result |
| --- | --- |
| `:veltis-native:testNative` (CTest) | 4/4 passed |
| `:veltis-native:verifyNativeRuntimePolicy` | passed; `KERNEL32.dll`, `msvcrt.dll`; `staticRuntime=true` read from the cache |
| `:veltis-native:test -PveltisNativeTest=true` | 81 tests, 0 failures, 0 errors, 1 skipped |
| same three tasks on the Clang lane | identical results |
| negative case (`VELTIS_NATIVE_STATIC_RUNTIME=OFF`) | failed as intended, naming both MinGW runtime DLLs |
| `:veltis-native:verifyNativeRelease -PveltisNativeTest=true` | passed; report now lists `runtime-policy` among its checks, and the source archive built from scratch |
| `:veltis-native:test` with no CMake, Ninja or compiler on PATH | passed — configuring the module never touches a native tool |
| `:veltis-native:test -Dveltis.native.test.expectJavaOnly=true` | 81 tests, **25 skipped**, 0 failures — the fixture-free fallback suite, with every native-dependent case skipping and the leak guard asserting no fixture reached the JVM |

### Phase 6 — build system

One document added, `BUILD_SYSTEM.md`, and all four Phase 6 risks closed —
R2 (build-info JSON), R3 (swallowed CMake output), R4 (untracked build input),
R5 (stale library giving a false green). Two of the four had to be *re-tested in
the failing direction* before they could be called fixed, and one of them turned
out not to work.

**First, the incident that changed how much of this session is trusted.**

A KDoc comment added to `native/build.gradle.kts` contained the glob ``src/**``
inside backticks. In Kotlin, `/*` inside an open `/** … */` block **opens a second
nesting level**, and an unterminated block comment that reaches end of file is not
a compile error in a Gradle script. Every task registration, every `logger.warn`
and every `println` after that line therefore stopped existing, silently:

- `:veltis-native:test` ran with **no fixture properties set at all**, so its
  "native cases skipped" result looked like the new staleness guard working;
- `configureNative`/`buildNative` did not exist, which is why a native warning
  never appeared on the console and the reason appeared to be a logging problem;
- `:veltis-native:help` succeeded, which was read as proof that the script was
  fine. It is not a valid probe: Gradle 9 does not evaluate project scripts for
  `help`.

Detection that actually worked: `:veltis-native:buildNative --dry-run` reported
"task not found", and `tasks --all` listed only `java`-plugin tasks. An init
script hashing the build file proved Gradle was reading the same bytes, ruling out
a stale-copy theory. **Consequence:** several Gradle invocations recorded earlier
in this session ran against the gutted script and were re-run after the repair;
the numbers in the tables below are from the repaired script. The pitfall is
written into `BUILD_SYSTEM.md` §13 so the next reader diagnoses it in one command.

**Defects found and fixed**

- **R2 — the build-info JSON was a configure side effect.** It is now produced by
  a CMake target (`veltis_native_build_info`,
  [CMakeLists.txt:544-549](../CMakeLists.txt#L544-L549)) that `veltis_native`
  depends on, so it is regenerated beside the library on every build (visible as
  `[1/1] VeltisNative: refreshing veltis-native-build-info.json beside the
  library`) and cannot be pruned while the library survives.
- **R4 — the untracked-build-input guard could not fire.** `Regex.matches`
  requires the *entire* input to match, so `buildInput.matches(it)` against a
  `\.(cpp|…)$` pattern was only ever true for a file named `.cpp`. The filter now
  compares the extension against `nativeBuildInputExtensions`
  ([build.gradle.kts:861-882](../build.gradle.kts#L861-L882)) and both directions
  were executed (table below). The earlier §11 entry said "any untracked build
  input" — that sentence has been corrected in place rather than left standing.
- **R5 — the Java suite would verify a library older than the sources.** Fixed by
  the freshness rule described in `BUILD_SYSTEM.md` §8 item 4: mtime comparison
  against `nativeCodeInputs`, all seven fixtures withheld plus a task-level
  warning when the library is stale, the verdict declared as
  `inputs.property("nativeFixturesAreFresh")`, and the native sources added to
  `inputs.files`. Three separate mistakes had to be corrected to get there, each
  found by an executed run rather than by reading: judging every fixture
  individually withheld fakes CMake had correctly not relinked; taking the verdict
  at configuration time judged the *previous* library because
  `-PveltisNativeTest=true` orders `buildNative` first; and a content-only input
  hash left the task UP-TO-DATE after a byte-identical reproducible relink.
- **Explicit compiler selection had no Gradle-level knob, and a changed `CC` was
  silently ignored.** Added `-PveltisNativeCc` / `-PveltisNativeCxx`, exported to
  the configure process as `CC`/`CXX`; `compilerSelection` declared as a
  fingerprint-time input; and `requireCompilerMatchesCache`
  ([build.gradle.kts:309-323](../build.gradle.kts#L309-L323)) fails with both
  values and the `cleanNative` remedy when the cache disagrees, because CMake
  reuses its cached compiler and would otherwise rebuild everything with a tool
  nobody selected and then report that tool as if it had been chosen. Lanes that
  pass a toolchain file are exempt — the file is the authority there, and a shell
  `CC` would break the preserved Windows ARM64 and Linux AArch64 cross lanes.
  While wiring this, an elvis-precedence bug (`a ?: b?.let { put(…) }` never ran
  `put` when `a` was non-null) made the selection always empty; caught by a
  temporary `println` probe, not by reading the code.
- **Declaring the CMake tree as a Gradle output was tried and reverted.** With
  `outputs.dir(cmakeBuildDir)`, Gradle's stale-output cleanup deleted a
  `CMakeCache.txt` that had been copied into that directory, because the same
  content was already registered as a Gradle output elsewhere. `configureNative`
  now deliberately declares no outputs (§8 item 2), which also keeps the compiler
  check unconditional.

**New executed evidence** (2026-11-04, repaired script, MinGW-w64 GCC 16.2.0,
CMake 3.31.6, Ninja 1.12.1)

| Check | Result |
| --- | --- |
| `verifyNativeRelease -PveltisNativeTest=true` (Release) | passed — CTest 4/4; Java 81 tests, 0 failures, 1 skipped; 295,661 B, abi 1, 13 exports; imports `KERNEL32.dll` + `msvcrt.dll`, no compiler runtime |
| `verifyNativeRelease -PveltisNativeBuildType=Debug -PveltisNativeTest=true` | passed — CTest 4/4; Java 81/0/1; 334,937 B, manifest `build Debug`, static-runtime policy still holds |
| Release zip across three runs | identical sha256 `c0244e46…`; source archive identical `a54160a9…` from the Debug and Release invocations, changing to `be669f5d…` only when an archived file was edited |
| `configureNative -PveltisNativeCxx=clang++` against the GCC cache | **failed as intended**, naming both values and `cleanNative` |
| `CC=gcc CXX=c++ configureNative` | passed; guard accepts a bare name against a resolved path |
| same conflict **with** `-PveltisNativeToolchainFile` | guard silent (toolchain file is the authority); CMake proceeded to `project()` |
| untracked `src/cpp/zz_probe_untracked.cpp` → `packageNativeSourceArchive` | **failed as intended**, naming the file |
| same, file removed | passed, 37 tracked files |
| content edit to `algorithms.cpp` without a rebuild → `test` | task re-ran, all fixtures withheld, warning printed, 25 native cases skipped visibly; `-PveltisNativeTest=true` rebuilt and ran them |
| `:veltis-native:build` and `test --rerun-tasks --no-build-cache` with no `cmake`, `ninja` or `gcc` on PATH | passed; 81 tests, 0 failures, 1 skipped; no native task ran |

**What Phase 6 did not do.** No CI definition was edited (they are preserved as
they stand, and no run of them has ever executed — R9 open, Phase 10's to close).
No `-Werror` gate was added, so a new compiler warning still will not fail a lane.
Windows ARM64 **MSVC** has no configuration at all — it needs an MSVC ARM64
toolchain, and inventing a preset that cannot be built would be a false claim.
MSVC, clang-cl, Linux and macOS remain unexecuted on this host: their build
configurations exist, their *results* do not. `README.md` still carries the
statements Phase 11 will correct.

### Phase 7 — Java FFM integration

One document added, `JAVA_FFM_INTEGRATION.md`, and **zero lines of Java
behaviour changed.** That is the phase's main finding, so it is worth stating
what was measured to reach it: all fourteen review points were checked against
the code and against a test that exercises each one, and the answer for all
fourteen was "already correct" — library selection, OS and architecture
detection, ABI validation, resolution of all 13 exports, the five method
descriptors, calling convention, memory layout, pointer handling, error
handling, resource ownership, thread-local error retrieval, init/shutdown and
fallback. The order's rule was to preserve the integration unless a concrete
defect required changes; the defects that turned up were in the *contract text*
and in *one of my own earlier documents*, not in the FFM layer.

**§5.7 resolved.** All three open questions now have answers written down:
`STRING_READ_LIMIT` is no longer safe by accident — the header states the
NUL-terminated ≤255-byte promise, `abi_contract_c.c` enforces it for all three
string exports, and the constant carries the reason for being 4096 rather than
256 ([NativeLoader.java:71-78](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L71-L78)).
The bulk-copy question was answerable from the code and is now documented with
the rule that keeps it that way (§6 of the new document). The
`veltis.native.path` / `server.properties` precedence gap is closed by §7 of the
new document, which now covers `-D`-over-file overlay semantics, what an invalid
boolean does, the fact that an explicit path *replaces* the discovery search
instead of preferring it, and the two facts a README cannot give an operator:
the launcher's JVM options and the release zip being flat, so a library has to
be extracted into `<home>/native/<platform>/`.

**Changes made**

1. `veltis_native.h` "Lifetime and threading" — the pre-init export set is
   enumerated instead of implied, the capability mask is stated to be a property
   of the compiled library rather than of the engine, and the string-return
   promise is written with its length bound. Comment-only: no signature, macro
   or export changed, so ABI stays at 1 and the export count stays at 13.
2. `tests/abi_contract_c.c` — two properties that were prose-only became
   enforced checks: `strlen(...) <= MAX_STRING_BYTES` on `version_string`,
   every `status_string` name and `last_error`, and capability-mask equality
   before `init`, after `init` and after `shutdown`. Both new guards were shown
   failing before they were kept: `MAX_STRING_BYTES=3` produced 8 failures, an
   inverted mask comparison produced 1, and both were reverted and the lane
   re-run green. `test_lifecycle_contract` now re-initialises at the end, with a
   comment saying why — the operation tests that follow it in `main()` need an
   initialized engine, and a lifecycle test that leaves the process shut down
   turns later failures into confusing ones.
3. `NativeLoader.java` — the `STRING_READ_LIMIT` KDoc explains the deliberate
   mismatch with 256: `getString` throws when the terminator falls outside the
   window, and a library that lengthened a message must not convert a
   diagnostic read into a load failure.
4. `RUNTIME_LIFECYCLE.md` §"Why step 4 precedes step 5" — **a Phase 4 claim of
   mine was false.** It said a lock-free reader in `transform()` could observe
   the `library`/`status` pair torn. Reading `NativeEngine` end to end shows
   `transform()` and `selfTest()` both take the read lock while every write to
   either field happens inside the write lock, so an operation cannot observe a
   partial transition at all. The genuine exposure is narrower and diagnostic
   only: the lock-free accessors `status()`, `nativeAvailable()` and
   `capabilities()` can straddle a transition, `nativeAvailable()` has no
   production caller, and the write order chosen in `loadLocked()` makes the
   straddle benign. The section was rewritten rather than softened, because a
   wrong reason for a lock is worse than no reason.
5. `ABI_CONTRACT.md` §5, §8, §10 and §12 now carry the same text as the header
   instead of a vaguer paraphrase; §10 names the two init-requiring exports and
   says so is exhaustive, which is what lets the loader validate a library it
   has not agreed to start.

**Claims reviewed and rejected.** Eight candidate defects were raised during
this pass; six were rejected after reading the code, and the rejections are
recorded with their reasons in `JAVA_FFM_INTEGRATION.md` §8 rather than dropped:
fusing `library`+`status` into one record (the straddle is between two accessor
calls, so fusion fixes nothing), retrying inside `initialize()` (an asserted
one-question contract), unsigned `capabilities` binding, a lock in
`FailureLogThrottle`, reference-counting the process-wide engine (that is R6, a
lifecycle change, not an FFM change), and distinguishing `lastError()` text
sources.

**Executed evidence** (2026-11-04, Windows x86-64, MinGW-w64 GCC 16.2.0,
Release, JDK 25.0.5)

| Lane | Command | Result |
| --- | --- | --- |
| CTest after `cleanNative` | `:veltis-native:cleanNative :veltis-native:testNative -PveltisNativeTest=true` | 4/4 passed; the three test binaries were also run directly and print their own success lines |
| Java integration suite | `:veltis-native:test -PveltisNativeTest=true` | 81 tests, 0 failures, 1 skipped |
| Server bootstrap with the real DLL | `:server:test --tests "*NativeBootstrapTest*" -Dveltis.native.path=…` | 12 tests, 0 failures, 0 skipped |
| Server bootstrap with no library | same, property removed | 12 tests, 0 failures, 4 skipped — the native-requiring cases stand down, startup does not fail |
| Documentation links | `linkcheck.js` over all `native/docs` anchors | 166 anchors, 0 problems |

Two process notes, because both changed what is trusted above. First, the
header and `NativeLoader` edits shifted line numbers, which silently stalees
any anchor that cites them; 18 links across five documents were recomputed, and
each was verified by comparing the *content* at the new range against the
sentence citing it, not only by checking that the range is in bounds. Second,
`:veltis-native:test` refused a hand-restored test source newer than the library
("predates the native sources … its native cases skip") — the Phase 5/6
freshness guard firing on my own working tree. The recorded results are from the
clean rebuild that followed, not from the refused run.

**What Phase 7 did not do.** No Java integration architecture change, as the
order requires. FFM per-call overhead and copy cost are measured, not claimed —
they belong to Phase 9. Sanitizers, fuzzing and `-Werror` belong to Phase 8.
Linux/macOS loader branches, MSVC/clang-cl lanes, Windows ARM64 at runtime and
JDKs other than 25 stay **unverified**: `NativeLoaderTest`'s synthetic PE/ELF/
Mach-O headers prove the parsing, not that a real foreign library loads.
R6 and R7 remain as documented; R9 (zero CI runs) is untouched.
