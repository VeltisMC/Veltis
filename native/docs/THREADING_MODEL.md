# veltis-native Threading Model

Which threads exist, who is allowed to call the library, what a native
implementation may assume about concurrency, and what would have to be true
before this module is allowed to create a thread or take a lock.

Companion documents: [RUNTIME_LIFECYCLE.md](RUNTIME_LIFECYCLE.md) for init and
shutdown ordering, [MEMORY_MODEL.md](MEMORY_MODEL.md) for buffer ownership,
[ABI_CONTRACT.md](ABI_CONTRACT.md) §9 for the thread-safety guarantee a caller
of the C ABI receives.

## 1. The one rule

**Java decides who calls the native library and when. The native library never
decides for itself.**

The library creates no threads, holds no mutexes, and runs no work that is not
directly caused by a call. Everything about concurrency is therefore a property
of the Java side plus one rule imposed on the C++ side: a native implementation
must tolerate several threads calling it at the same instant.

## 2. What the native library actually contains

Verified by grep over `native/src/c`, `native/src/cpp` and `native/include`, not
assumed from reading one file:

| Query | Result |
| --- | --- |
| `std::thread`, `std::jthread`, `pthread_create`, `CreateThread`, `_beginthread`, `std::async`, `detach`, `thread_pool` | **zero matches** |
| `std::mutex`, `lock_guard`, `scoped_lock`, `condition_variable`, `semaphore` | **zero matches** |
| `std::atomic` | two matches: the declaration at [veltis_native.hpp:53](../include/veltis/native/veltis_native.hpp#L53) and the lock-freedom `static_assert` at [native_engine.cpp:13](../src/cpp/native_engine.cpp#L13) |

So the entire native state is:

```
std::atomic<bool> initialized_{false};   // process-wide, one per library
VELTIS_THREAD_LOCAL char veltis_last_error[256];  // one per calling thread
```

`std::thread` appears in `native/tests/native_tests.cpp` only — those are test
threads, and the library itself never spawns one. That distinction is the whole
content of the "no uncontrolled background threads" requirement, and it is worth
stating plainly because the test file is the only place a reader would conclude
otherwise.

Three consequences follow from having no mutable state beyond a flag:

- **Concurrent calls cannot corrupt each other.** `transform_i32`
  ([algorithms.cpp:7-30](../src/cpp/algorithms.cpp#L7-L30)) reads only its
  `input` pointer and writes only its `output` pointer; both are caller-owned
  (see [MEMORY_MODEL.md](MEMORY_MODEL.md) §1). Two calls with distinct buffers
  touch no common byte, and overlap between *one call's* buffers is rejected
  before the loop runs.
- **No lock ordering can deadlock**, because there are no native locks. The only
  contention point in the whole library is the one-time construction of the
  static-local `Engine` ([native_engine.cpp:18-21](../src/cpp/native_engine.cpp#L18-L21)),
  which C++11 magic-statics make thread-safe and which happens at most once per
  process.
- **The error slot is per-thread by design, not by accident.** A failure
  recorded on thread A is not readable from thread B
  (`test_thread_local_last_error`). This is the one native behaviour that is
  *not* process-wide, and it creates the affinity rule in §7.

## 3. Memory ordering of the lifecycle flag

`initialize()` and `shutdown()` store with `memory_order_release`; `initialized()`
loads with `memory_order_acquire`
([native_engine.cpp:23-33](../src/cpp/native_engine.cpp#L23-L33)).

Stated honestly: **today the acquire/release pair publishes nothing.** The flag
is the only object, there is no associated data whose visibility needs to be
ordered, and `is_always_lock_free` is asserted so the flag itself cannot drag in
a runtime lock table. The reason to keep the orderings rather than use relaxed
is that they are the correct spelling for the day a capability adds state —
a cache, a descriptor, a table — and that state must be visible to a thread that
observes `initialized() == true`. A relaxed load would silently become a bug at
exactly the moment the library gains its first shared structure.

The rule for a capability that adds native state is therefore: **write the state,
then release-store the flag; read the flag with acquire, then read the state.**
Do not add a second flag.

## 4. The Java lock hierarchy

Two `ReentrantReadWriteLock` instances exist, and they are always taken in the
same direction:

```
NativeEngine.lifecycle          (NativeEngine.java:25)
        │  held while
        ▼
LoadedLibrary.lifecycle         (NativeLoader.java:171)
```

| Caller | Lock held | Reason |
| --- | --- | --- |
| `NativeEngine.initialize` / `load` / `shutdown` | `NativeEngine` **write** | changes whether a library is published |
| `NativeEngine.transform` / `selfTest` | `NativeEngine` **read** | many server threads call concurrently |
| `NativeEngine.status` / `nativeAvailable` / `capabilities` / `stats` | none | volatile / `LongAdder` reads, by design |
| `LoadedLibrary.transform` / `initialize` / `selfTest` / `lastError` / `capabilitiesMask` | `LoadedLibrary` **read** | one downcall each |
| `LoadedLibrary.close` | `LoadedLibrary` **write** | must wait for in-flight downcalls |

The order is never reversed: `LoadedLibrary` has no reference to any
`NativeEngine`, so no code path can hold the inner lock and then ask for the
outer one. That is what makes the hierarchy safe rather than merely short.

**Read is not exclusive.** Multiple threads run `transform` at the same time,
under a shared lock, into the same `LoadedLibrary`, and therefore into the same
native code. This is the load-bearing fact for §8: the Java read lock is a
*publication guard*, not a mutual-exclusion guard around the native call.

## 5. Why operations take the read lock and close takes the write lock

The requirement is "do not close shared native resources while operations remain
active". The resource is the `Arena.ofShared()` that backs
`SymbolLookup.libraryLookup` ([NativeLoader.java:322-327](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L322-L327)),
and closing it un-references the mappings the method handles point into.

`underReadLock` ([NativeLoader.java:241-252](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L241-L252))
holds the read lock across the whole downcall; `close` takes the write lock, sets
`closed`, calls `veltis_native_shutdown()` and only then `arena.close()`. A close
therefore cannot start until every in-flight call has returned, and a call
started after the close observes `closed == true` and throws
`IllegalStateException` instead of dereferencing a released segment.
`closeWaitsForInFlightCallsAndRejectsLaterOnes` in `NativeEndToEndTest` is the
executable version of that sentence.

Re-entrancy is safe here for a specific structural reason, not luck: **none of
the 13 exports takes a callback**, so a native call cannot call `close()` on the
same thread and deadlock against its own read lock. If a future capability adds a
callback, this paragraph becomes false and must be re-examined before that
capability ships — the callback would run on a native thread while the read lock
is held, which is also where the "no native thread" rule in §1 would break.

The same nesting one level up: `NativeEngine.shutdown()` holds the engine write
lock while it calls `loaded.close()`, which acquires the library write lock.
`transform()` holds the engine read lock and then the library read lock. Both
acquisitions go downward in the graph, so there is no cycle.

## 6. What this model deliberately does not guarantee

These are not bugs; they are the parts a caller must not rely on.

| Not guaranteed | Why it is acceptable |
| --- | --- |
| `status()` reflects a concurrent `shutdown()` | it is an unsynchronized volatile read; a status can be one instant stale. Status is for logging and diagnostics, never for synchronization. |
| `stats()` totals are exact at a sampled instant | `LongAdder` is eventually consistent by design; it trades precision for not contending on a hot path. |
| Transform calls are ordered with respect to each other | the transform is a pure function of its arguments; order carries no meaning. |
| A native error message survives to another thread | §2: the slot is thread-local. Read it on the thread that got the failure — which `nativeTransform` does. |
| Two `NativeEngine` instances have independent native state | they do not: `Engine::instance()` is process-wide. See §9. |
| `shutdown()` waits for a native thread | there are no native threads to wait for. The Java locks provide all the waiting that exists. |

## 7. The one thread-affinity rule

**A failed native call must have its error text read on the same Java thread that
made the call.**

`nativeTransform` ([NativeEngine.java:304-321](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L304-L321))
satisfies this: the `rc != 0` branch calls `loaded.lastError()` on the frame that
made the downcall, before anything else can run on this thread. The rule exists
because `veltis_native_last_error` returns a pointer to
`VELTIS_THREAD_LOCAL` storage — the failing thread's own slot. Handing a
failure to another thread to describe would return an empty string, or worse, a
message belonging to a different fault.

No other affinity exists. No export cares which thread calls it, the loader and
the bootstrap do not pin a thread, and `Platform.onNativeThread` is never used —
the downcalls are plain `Linker.downcallHandle` calls with no
`CustomLinkerOptions.CALL_ARRAY_IN_MULTIPLE_BUFFERS` or thread confinement option,
so they run on whichever JVM thread invokes them.

## 8. Thread pools, and why there is none

The order's rule is "do not introduce a native thread pool unless a real workload
requires it". It does not require one, and a pool would be actively harmful here:

- The server already parallelizes across its own worker threads, and each native
  call is one pass over a caller-owned buffer. A pool adds a queue between a
  caller and work it was going to do itself.
- A pool changes the concurrency contract: callers would move from "N threads
  each do their own transform" to "N threads contend for M workers plus a barrier",
  which serializes exactly the calls that are already correct in parallel.
- A pool must be created, sized, drained and destroyed — which means lifecycle
  states this library does not have and a shutdown barrier §5 shows it does not
  need.
- Thread count would become an ABI-visible policy decision, invisible to the Java
  side that owns configuration.

**Re-open trigger.** A native thread pool becomes worth measuring only when a
single call is large enough to dominate its caller's latency budget *and* that
call cannot be split by the caller. Both conditions must be shown with the
benchmark harness (`BENCHMARKING.md`, Phase 9), not argued. Until
then, the correct answer to "can we make this parallel inside native code" is
that the caller already is parallel.

## 9. Process-wide native state, per-instance Java state

`NativeEngine` has a `SHARED` instance that `VeltisBootstrap` initializes and
stops ([VeltisBootstrap.java:96](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L96),
[:112](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L112)),
and a public constructor so tests and diagnostics can build independent engines.
Those two facts do not compose perfectly:

- Java lifecycle state is per instance: `library`, `status`, `config`,
  `initialized` and the two `FailureLogThrottle`s.
- Native lifecycle state is per process: one `atomic<bool>` shared by every
  library handle that maps the same binary.

So `engineB.shutdown()` calls `veltis_native_shutdown()` and clears the flag that
`engineA` believes is set. A subsequent `engineA.transform()` gets
`VELTIS_NATIVE_ERROR_NOT_INITIALIZED`, and the Java side does the only thing it
can with an unrequested native failure: log (budgeted) and use
`javaTransform`. The result stays correct; only the source changes. This is
recorded as risk **R6** in [FOUNDATION_AUDIT.md](FOUNDATION_AUDIT.md).

Two facts keep this a documentation item rather than a defect:

1. The server uses `NativeEngine.shared()` and nothing else. No production code
   path creates a second engine.
2. The Java test task runs one forked JVM with JUnit's default sequential
   execution — `build.gradle.kts:367-369` configures `useJUnitPlatform()` and no
   `maxParallelForks` — so test classes never share the process-wide flag
   concurrently. **Enabling parallel test forks would make these tests
   order-dependent through native state, and would need the native lifecycle
   split or the fork isolation fixed first.** That is the practical constraint
   worth remembering.

## 10. Rules for the next capability

1. It must not create a thread. If it seems to need one, re-read §8.
2. If it adds mutable process-wide state, guard it inside the C++ layer with a
   named primitive, document the ordering, and do not rely on the Java read lock —
   §4 says plainly that the read lock admits concurrent callers.
3. If it needs a caller-supplied buffer, reuse the predicates in
   `native_buffer_checks.hpp`; the thread-local error slot is the only way to
   report why it refused.
4. It must not take a Java callback unless §5's no-re-entrancy argument is
   re-proved, because a callback runs while both read locks are held.
5. Its thread-safety claim must be added to the verification table in §12 with a
   test that races it, or it must be documented as "single-caller only".

## 11. What is deliberately absent

| Absent | Reason |
| --- | --- |
| Any native lock | no shared mutable state to protect |
| Background/timer/worker thread | no deferred work exists |
| Thread pool | §8 |
| Affinity or pinning API | no export needs it (§7) |
| `java.lang.foreign` `ConfinedLinker` / native-thread handoff | no callback, no thread handoff to model |
| Per-thread engine state | one flag is enough; per-thread state would make shutdown semantics depend on which thread asks |
| Lock-free data structures | there is no data structure |

## 12. Verification

Each row is a test that exists in this repository and was executed, not a
scenario proposed for later. The order's list of required threading checks maps
onto these; lifecycle ordering detail is in [RUNTIME_LIFECYCLE.md](RUNTIME_LIFECYCLE.md) §6.

| Concurrency question | Test | Layer | Result |
| --- | --- | --- | --- |
| Do concurrent native transforms produce correct results? | `test_concurrent_transforms` | CTest | passed |
| Do concurrent init/shutdown/transform calls corrupt state or results? | `test_concurrent_lifecycle` (8 threads × 300 rounds) | CTest | passed |
| Is a shutdown during active transforms safe? | `test_shutdown_while_transforms_run` (4 threads × 32 KiB, 250 cycles) | CTest | passed |
| Is the error slot really per-thread? | `test_thread_local_last_error` | CTest | passed |
| Do concurrent Java `initialize` calls load at most one library? | `concurrentInitializationLoadsAtMostOneLibrary` | Java | passed |
| Are concurrent fallback transforms independent? | `concurrentTransformsOnTheJavaFallbackAreIndependent` | Java | passed |
| Do concurrent lifecycle calls publish one consistent status? | `concurrentLifecycleCallsPublishOneConsistentState` (8 threads × 60 rounds; odd threads read without the lock) | Java | passed |
| Are concurrent engine transforms correct through the real library? | `concurrentTransformsThroughTheEngineAreCorrect` | Java integration | passed |
| Does shutdown during active calls ever yield a wrong result? | `shutdownWhileCallsAreActiveNeverProducesWrongResults` | Java integration | passed |
| Does close wait for in-flight calls and reject later ones? | `closeWaitsForInFlightCallsAndRejectsLaterOnes` | Java integration | passed |

Two verification gaps are recorded rather than papered over:

- **ThreadSanitizer has not been run on this set.** The TSan lane is Linux-only
  in the current CI, and the local host is Windows, where TSan and ASan cannot be
  combined. §12 is therefore correctness evidence, not race-detector evidence.
  Phase 8 tracks the sanitizer matrix; the honest label until it runs is
  "concurrency-tested, not TSan-verified".
- **No test proves the absence of thread creation** the way
  `export_check.c` proves the absence of forbidden exports. §2's grep is the
  evidence; a grep is not a build gate. If a future change to the library must be
  blocked from adding a thread, the place to enforce it is
  `native/tests/export_check.c` alongside the symbol rules.
