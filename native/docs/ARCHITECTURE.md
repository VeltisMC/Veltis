# veltis-native Architecture

Status: describes the module as it exists on 2026-11-03 and the layout new code
must follow. Read [FOUNDATION_AUDIT.md](FOUNDATION_AUDIT.md) first for what is
verified versus assumed.

## 1. What this module is for

`veltis-native` is an **optional** native accelerator behind a frozen C ABI,
plus the Java FFM integration that loads it, and the build and packaging system
that produces it. Java remains authoritative for every piece of server logic.
The server starts, runs and stops correctly when the library is missing,
incompatible, or fails mid-call.

The module is a *foundation*, not an optimization program. It accelerates one
bulk integer primitive today, and no server code path calls it. That is a
deliberate state, not a placeholder waiting to be filled: a capability enters
native only after profiling shows it is a hotspot and after the integration
rules (one bulk call, no per-element downcall) are satisfiable.

## 2. Layers as built

Six layers, each with exactly one reason to change.

```
L6  consumer          VeltisBootstrap, NativeEngine callers
L5  Java facade       NativeEngine        (policy: fallback, throttle, lifecycle lock)
L4  Java binding      NativeLoader        (mechanics: lookup, probe, downcalls, close)
L3  Java support      NativeConfig, NativeStatus, NativeCapabilities, FailureLogThrottle
    ───────────────────────── process/ABI boundary ─────────────────────────
L2  C ABI surface     extern "C" exports in native_engine.cpp + native_utils.c
L1  C++ internals     Engine, algorithms.cpp, veltis_native.hpp
L0  Contract          include/veltis/native/veltis_native.h
```

L0 is the only ABI authority. L1 never appears in an exported signature. L2 is
where every C++ type stops existing. L4 is the only place that knows FFM. L5 is
the only place that knows policy. L6 does not know whether native ran.

### Files per layer

| Layer | Files |
| --- | --- |
| L0 | `include/veltis/native/veltis_native.h` |
| L1 | `include/veltis/native/veltis_native.hpp`, `src/cpp/algorithms.cpp` |
| L2 | `src/c/native_internal.h`, `src/c/native_utils.c`, `src/cpp/native_engine.cpp` |
| L3 | `nativeengine/NativeConfig.java`, `NativeStatus.java`, `NativeCapabilities.java`, `FailureLogThrottle.java` |
| L4 | `nativeengine/NativeLoader.java` |
| L5 | `nativeengine/NativeEngine.java` |
| L6 | `server/.../runtime/VeltisBootstrap.java` |

### The distribution subsystem is a feeder, not a seventh layer

The module also ships an optional installer that can fetch the published
artifact for the detected platform and place it where L4 already looks. It is
deliberately outside the stack above, because it never participates in a
transform call and adds no ABI surface — its only job is to produce a file:

| File | Role |
| --- | --- |
| `nativeengine/NativeDistributionManager.java` | Decide, fetch, verify, install, hand off |
| `nativeengine/NativeDistributionTransport.java` | The only code allowed to open a connection |
| `nativeengine/NativeDistributionInstaller.java` | The only code allowed to write `<home>/native/<platform>/` |
| `nativeengine/NativeManifest.java` | Signed manifest parsing and Ed25519 verification |
| `nativeengine/NativeDistributionConfig.java` | The five `veltis.native.*` distribution knobs |

The hand-off is the L4 gate itself, not a parallel one: `NativeLoader.checkBinary`
runs on the extracted library before it is moved into place and again when the
loader opens it, so an installer bug can only cause a failed install, never a
loaded binary the loader would have refused. [DISTRIBUTION.md](DISTRIBUTION.md)
is the authority for that path.

## 3. The two things this layering buys

**A wrong native library cannot be loaded by accident.** `NativeLoader.tryLoad`
refuses in a fixed order — container format and architecture (probed by reading
headers with random access, before any OS load), then ABI version, then engine
major/minor/patch, then a `status_string` probe, then binding resolution. Each
refusal closes the library and returns a reason. Nothing at L5 or L6 has to
repeat any of it.

**A native failure cannot reach a player.** `NativeEngine.transform` takes the
read lock, tries the downcall, and on any throwable falls back to
`javaTransform` while the throttle bounds the warning volume. `fallback=false`
is the one configuration that converts that into an `IllegalStateException`, and
it exists for operators who want proof, not for production.

## 4. Data flow of one transform call

```
NativeEngine.transform(int[] input, int salt)
  lifecycle.readLock
    library readLock
      Arena.ofConfined()
        MemorySegment.copy(input -> inSegment)     // one bulk copy in
        downcall(inSegment, n, salt, outSegment, n)// ONE call, size_t as JAVA_LONG
        MemorySegment.copy(outSegment -> int[])    // one bulk copy out
      arena close (also on failure)
    unlock
  unlock
```

Cost is two copies plus 8 bytes/element of Java-side segment allocation. The
transition itself is cheap (a trivial downcall measured 41.6 ns on the
reference host); the copies are the whole overhead, which is why no native win
appears at small batch sizes.

## 5. Target layout for new code

This is the layout a capability lands in. It is **not** a instruction to move the
seven existing C/C++ files, and doing so now would be cosmetic: the module has
one compute export, no platform-specific code, no allocator and no CPU dispatch.

```
src/
  abi/           public headers only (today: include/veltis/native/)
  core/          engine state and dispatch           <- from native_engine.cpp, when it grows
  memory/        internal allocation helpers          <- only when native starts allocating
  runtime/       lifecycle, shutdown coordination     <- only when shutdown needs a barrier
  diagnostics/   status strings, last error, stats    <- only when diagnostics exceed native_utils.c
  platform/
    common/      OS-neutral primitives
    windows/     Windows-specific                       <- only when an OS API is actually used
    linux/
    macos/
  architecture/
    generic/     portable scalar implementation         <- only when a SIMD variant exists
    x86_64/
    arm64/
  algorithms/    pure computation                       <- today: src/cpp/algorithms.cpp
  c/             C-only support TU (native_utils.c)
  cpp/           C++ engine TU (native_engine.cpp)

tests/
  unit/          one function or class at a time
  integration/   real library + real FFM
  stress/        concurrency and long runs
  portability/   container, symbol and dependency checks
  fixtures/      deliberately incompatible libraries
  benchmarks/    NativeBenchmark and successors
```

**Trigger rules** — a directory is created when the first real file lands in it,
never before:

| Directory | Created when |
| --- | --- |
| `memory/` | native code allocates for the first time |
| `platform/{windows,linux,macos}/` | an OS-specific API is genuinely required |
| `architecture/x86_64/` or `arm64/` | a CPU-dispatched variant with a generic fallback exists |
| `runtime/` | `shutdown` must wait for in-flight calls natively |
| `diagnostics/` | last-error/stats exceed what `native_utils.c` holds legibly |
| `core/` | a second compute export makes dispatch worth separating from marshalling |

## 6. Decisions and their reasons

- **The public header generates the build.** CMake parses export names, ABI
  version and export count out of `veltis_native.h`. A signature edit and a
  manifest edit cannot drift, because they are the same edit.
- **Hidden visibility and `--no-undefined`.** An unintended symbol is a link
  failure, not a support promise nobody remembers making.
- **Caller owns every buffer.** No export allocates. No free function exists.
  No allocator has to match across the boundary or across CRTs. This single rule
  removes a whole class of cross-platform shipping bugs.
- **Booleans are `int32_t`, never `bool`.** `_Bool` size and representation are
  not something two independently-built sides should trust.
- **`size_t` is `JAVA_LONG` in the FFM descriptor**, because element counts are
  pointer-width. That mapping lives in exactly one place, `NativeLoader`.
- **Thread-local last error.** A shared error slot in a server with region
  threads is a lie waiting to be told. The cost is that a caller must read the
  error on the same thread that failed, which `NativeEngine` does.
- **Process-wide native engine, one shared Java facade.** `Engine::instance()` is
  a static local; Java deliberately uses `NativeEngine.shared()`. Two loader
  instances mapping one DLL share native state — see §7.
- **No native threads, ever, without a workload.** The module starts none today
  and must not gain a background thread as infrastructure.

## 7. Known architectural debt

Recorded so nobody discovers it as a surprise; none of it is worth fixing before
the trigger in §5 arrives.

1. `native_engine.cpp` mixes C marshalling, argument validation and engine state
   in 224 lines. With one compute export that is cohesion, not a problem. It
   becomes a problem at the second compute export.
2. `veltis_native_shutdown()` has no native-side barrier. Safety comes from the
   Java read/write locks. A future stateful capability must add the native
   barrier *before* it stores anything a concurrent call can read.
3. Native state is process-wide; Java models it per `LoadedLibrary`. Correctness
   survives because the second instance falls back to Java.
4. `Engine::initialize()` stores one atomic flag, so "partial-init rollback" is
   not a thing that can happen. Do not document a rollback that does not exist.
5. `VELTIS_NATIVE_ERROR_BUFFER_TOO_SMALL` is declared and never returned by the
   production library. It is contract surface kept for a future sized-output
   export, and tests treat it as such.

## 8. What must never happen here

- No Minecraft or gameplay types, names, or assumptions in this module. It is a
  general native foundation; a game-specific kernel belongs behind the same ABI
  only when it is also a general primitive.
- C++ exceptions must not cross L2. Every `extern "C"` body catches.
- No C++ type in an exported signature. No templates, no references, no
  `std::` in a header below L0's include guard.
- Platform-specific code must not appear in `src/c` or `src/cpp`; the only
  current exception is the `_Thread_local`/`__declspec(thread)` selection in
  `native_utils.c:14-20`, which is a three-branch macro, not a platform layer.
- CPU-specific instructions must not exist without runtime feature detection and
  a generic fallback path.
- No native call per element. Bulk or not at all.
- No new export for tidiness, and no ABI version bump for an additive change.
