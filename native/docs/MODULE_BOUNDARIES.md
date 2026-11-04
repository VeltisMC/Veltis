# veltis-native Module Boundaries

The rules that keep the layering in [ARCHITECTURE.md](ARCHITECTURE.md) from
eroding. Every rule below names the thing that enforces it. A rule with no
enforcement is marked **review**, which means a human must check it — and those
are the ones that decay.

## 1. Dependency direction

A dependency arrow means "may include / may call". Nothing may point upward.

```
VeltisBootstrap
   └─> NativeEngine ──> NativeStatus ──> NativeCapabilities
          │                 NativeConfig
          └─> NativeLoader ─> (java.lang.foreign)
                        │
                        ▼  process boundary: dlopen / LoadLibrary + C ABI
                   extern "C" exports (native_utils.c, native_engine.cpp)
                        │              ▲
                        │              └─> native_internal.h (bridge, hidden)
                        ▼
                   veltis::native Engine, algorithms.cpp
                        │
                        ▼
                   veltis_native.h  (the contract; depends on nothing)
```

`veltis_native.h` is the root of the graph. It includes standard C headers and
nothing else, and no file in this module may add a dependency beneath it.

## 2. Include rules per file

| File | May include | Must never include / reference |
| --- | --- | --- |
| `include/veltis/native/veltis_native.h` | `<stdint.h>`, `<stddef.h>`, `<stdbool.h>` if ever needed | any internal header, any C++ header, any Minecraft type |
| `include/veltis/native/veltis_native.hpp` | `veltis_native.h`, C++ standard library | a platform SDK header |
| `src/c/native_utils.c` | `veltis_native.h`, `native_internal.h`, C standard library | `veltis_native.hpp`, any C++ header |
| `src/c/native_internal.h` | nothing but `<stdint.h>` when required | any C++ type; must stay valid C |
| `src/cpp/algorithms.cpp` | `veltis_native.hpp`, C++ standard library | `native_internal.h` (algorithms must not touch engine state or the error slot) |
| `src/cpp/native_engine.cpp` | both public headers, `native_internal.h`, C++ standard library | a platform SDK header outside a guarded primitive |
| `NativeLoader.java` | JDK (`java.lang.foreign`, `java.util`, NIO) | `NativeEngine`, Minecraft/server types |
| `NativeEngine.java` | `NativeLoader`, `NativeConfig`, `NativeStatus`, `NativeCapabilities`, `FailureLogThrottle` | Minecraft/server types |
| `NativeConfig`, `NativeStatus`, `NativeCapabilities`, `FailureLogThrottle` | JDK only | `NativeLoader`, `NativeEngine` |
| `VeltisBootstrap.java` | `NativeEngine`, `NativeConfig`, `NativeStatus` | `NativeLoader` directly |

`NativeConfig` is read by `NativeEngine`, not by `NativeLoader`: the loader takes
a path and a config, and returns a status, but policy (enabled/autoload/fallback)
belongs one layer up. `NativeLoader` must stay usable by a test that wants to
load a fixture library without any policy at all.

## 3. The process boundary, stated exactly

Crossing L0→L2 is the only boundary in this module that cannot be fixed by a
recompile on both sides, so it has the most rules.

**What may cross:**
- `int32_t`, `uint32_t`, `int64_t`, `size_t`, pointers to those, `const char*`
- nothing else

**What may not cross:**
- a C++ type, a reference, a template instantiation
- a `bool` / `_Bool` (booleans are `int32_t`: 0 or non-zero)
- an exception — every `extern "C"` body catches, including `catch (...)`
- an allocation the other side must free
- a pointer that outlives the call, except a pointer to static storage returned
  by `version_string`, `status_string` and `last_error`

**Buffer rules:**
- Input buffers: valid, readable for `count * sizeof(element)`, non-null when
  `count > 0`.
- Output buffers: valid, writable for the same extent, non-null.
- `count == 0` is a defined success case, not an error: with both lengths zero
  the call returns `OK` without touching either pointer, so a null buffer pair is
  legal there and nothing is written. This is checked before the null rules.
- Overlapping input and output ranges are rejected (`byte_ranges_overlap`).
- `count * sizeof(element)` is computed with overflow detection
  (`checked_byte_extent`); `SIZE_MAX` length returns invalid-argument, not a
  wrap.
- Returned `const char*` strings point at thread-local or static storage. A
  caller must never free them. `last_error` describes the *calling thread's*
  last call and is valid until that thread's next call.

**Error rules:**
- Return value is always a documented `VELTIS_NATIVE_*` status code.
- `OK` is 0 and is pinned by `_Static_assert`
  ([native_utils.c:27-32](../src/c/native_utils.c#L27-L32)). An unknown code
  string is `"unknown status"`, never a crash.
- A successful call clears the error slot, so a stale message cannot be read as
  a fresh failure.

## 4. Ownership

| Resource | Owner | Released by | Enforced by |
| --- | --- | --- | --- |
| Transform input/output buffers | Java caller | `Arena.ofConfined()` close at end of call, including on failure | `NativeEndToEndTest` |
| The library handle and symbol lookup | `NativeLoader.LoadedLibrary` | `close()`, under the write lock | `closeWaitsForInFlightCallsAndRejectsLaterOnes` |
| Shared benchmark arena | `NativeBenchmark.NativeReusable` | explicit close | **review** |
| Native thread-local error slot | the library | overwritten by the next call on that thread | CTest thread-local case |
| Native engine init flag | the process | `veltis_native_shutdown()` | CTest lifecycle cases |
| Minecraft server state | Java | Java | architecture rule |

No row may be added to this table with "both sides" or "unclear" in the Owner
column. Ambiguous ownership across L0 is the bug class this module exists to
avoid.

## 5. Concurrency ownership

- **Java owns all serialization that matters.** `NativeEngine.lifecycle`
  (read/write) guards initialize vs shutdown; `LoadedLibrary`'s read/write lock
  guards calls vs `close()`.
- **Native provides thread safety for its own state only**: one
  `std::atomic<bool>` and a thread-local buffer. It does not, and today cannot,
  wait for in-flight calls on `shutdown`. Any capability that stores mutable
  native state must first add a native barrier — that is a boundary rule, not a
  nice-to-have.
- **No native thread may be created** by this module without a profiled
  workload. Currently zero threads are created; **review**, plus the fact that a
  grep for thread creation over `src/` has exactly one hit and it is a
  `static_assert` about lock-freedom.
- A caller that logs a native failure must read `last_error()` on the same
  thread that made the failing call. `NativeEngine` does; **review** for future
  consumers.

## 6. Build-system boundaries

| Boundary | Rule | Enforcement |
| --- | --- | --- |
| Gradle → CMake | only `buildNative`/`testNative`/`configureNative` may invoke CMake | task graph |
| Java-only path | `compileJava`, `test`, `runServer` must not need CMake, Ninja, GCC, Clang or MSVC | tool resolution deferred to `doFirst`; verified by running with no toolchain on `PATH` |
| Cross-compile | a cross preset may configure and build, never execute | `testNative` refuses to run a cross-compiled build |
| Windows runtime policy | static CRT by default per compiler family, recorded in the manifest | `tools/check-pe-imports.ps1` |
| Export hygiene | the built binary's exports equal the header's list, and the two bridge symbols are absent | `tests/export_check.c`, CI diff against the header |
| Manifest honesty | every claim in a manifest is recomputed or re-read from the artifact | `verifyNativeArtifact` |
| Archive hygiene | the source archive is built from `git ls-files` and rebuilt from scratch | `packageNativeSourceArchive` + `verifyNativeSourceArchive` |

## 7. Test placement

| Test belongs in | when | currently lives in |
| --- | --- | --- |
| `tests/native_tests.cpp` | it needs no JVM | 18 cases — Executed, CTest 2/2 |
| `tests/export_check.c` | it checks the binary's shape, not its behavior | Executed |
| `tests/fixtures/*.c` | it must be a library that *should fail* to load | 5 fixtures + minimal |
| `NativeLoaderTest` | it checks Java-side discovery, probing, binding | ~30 cases — Executed |
| `NativeEngineTest` | it checks policy: fallback, throttle, stats | Executed |
| `NativeEndToEndTest` | it crosses the boundary for real | 11 cases — Executed |
| `FailureLogThrottleTest` | pure Java unit logic | 7 cases — Executed |
| `NativeBenchmark` | it measures, never asserts performance | 4 implementations × 11 sizes |

A test that mixes layers (a Java test asserting a native constant's value, or a
C++ test asserting Java behavior) is a boundary smell. Fix the test's placement
before fixing its content.

## 8. Adding a native capability — the boundary checklist

The README's procedure and this list are the same gate; this version is ordered
by boundary rather than by file.

1. Declare it in `veltis_native.h`, assign a `VELTIS_NATIVE_CAPABILITY_*` bit.
   Scalars and pointers only. If it needs a struct, read §3 again and prefer
   parallel arrays or an explicit flat encoding.
2. Implement the algorithm in `algorithms.cpp` / the `veltis::native` namespace,
   `noexcept`, no state, no allocation.
3. Add the `extern "C"` export in `native_engine.cpp`: validate arguments in the
   established order, call the algorithm, catch everything, map errors to status
   codes plus `set_last_error`, clear the error on success.
4. Mirror it in Java with a bit-for-bit equivalent fallback, before touching the
   loader.
5. Add the downcall descriptor in `NativeLoader` and the enum entry in
   `NativeCapabilities` — one place each.
6. Surface it through `NativeEngine` under the read lock with throttle +
   fallback, and add a native-vs-Java equivalence test.
7. Add CTest cases: happy path, boundary values, invalid arguments, lifecycle,
   concurrency.
8. Extend `tests/export_check.c`'s expected export list. CI compares that list to
   the header, so step 1 and step 8 must be the same commit.
9. Run the benchmark for the new shape, including FFM transition and copy cost.
   No integration without measured evidence.
10. Update the README support table and `PLATFORM_SUPPORT.md` only after a target
    actually executed it.

Bump `VELTIS_NATIVE_VERSION_MINOR` for an additive change. Bump
`VELTIS_NATIVE_ABI_VERSION` only when an existing signature or semantic changes
so that an older caller cannot use it — and write the migration.

## 9. Rules that currently depend on review

Worth knowing, because these are where the foundation silently rots:

- "native code never allocates" — no build-time or test-time check; only grep
  and review.
- "no native thread is created" — same.
- "the shared benchmark arena is closed" — same.
- "a caller reads `last_error()` on the failing thread" — same.
- "no per-element FFM call for bulk work" — enforced only by the benchmark
  comparing shapes, not by a test.
- "an unexecuted platform is not claimed as supported" — enforced by review of
  the support table plus §8 step 10.

Each has a cheap automated check that could exist. Which ones earn it is a
Phase 8 decision, not a guess made here.
