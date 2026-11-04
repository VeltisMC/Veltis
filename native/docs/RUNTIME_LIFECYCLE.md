# veltis-native Runtime Lifecycle

What "initialized" means on each side of the ABI, the exact order a load and a
shutdown run in, what a failed step leaves behind, and which of those statements
a test actually proves.

Companion documents: [THREADING_MODEL.md](THREADING_MODEL.md) for who may call
concurrently, [ABI_CONTRACT.md](ABI_CONTRACT.md) §10 for the lifecycle rules the
C ABI promises, `JAVA_FFM_INTEGRATION.md` (Phase 7) for the FFM
binding layer.

## 1. The one rule

**Nothing is published to a caller until every check on it has passed.**

There is no state in which a Java caller can hold a library handle that the
engine has not finished validating, and there is no state in which the native
side is "half initialized". The rest of this document is the evidence for those
two sentences.

## 2. Two lifecycles and the seam between them

| Layer | State that decides "live" | Owner | Where it is set |
| --- | --- | --- | --- |
| Native library | `Engine::initialized_` — one `std::atomic<bool>` | process-wide, per mapped binary | [native_engine.cpp:23-33](../src/cpp/native_engine.cpp#L23-L33) |
| FFM binding | `LoadedLibrary.closed` plus the `Arena` | one `LoadedLibrary` object | [NativeLoader.java:172](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L172), [:223-239](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L223-L239) |
| Engine facade | `NativeEngine.library` (`null` ⇔ not usable) | one `NativeEngine` instance | [NativeEngine.java:32](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L32) |
| Engine facade | `initialized` — "autoload already attempted" | one `NativeEngine` instance | [NativeEngine.java:34](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L34) |
| Reporting | `NativeStatus status` | one `NativeEngine` instance | [NativeEngine.java:33](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L33) |

The seam is three calls: `LoadedLibrary.initialize()` on the way in,
`LoadedLibrary.close()` on the way out, and nothing else touches lifecycle across
the boundary. `veltis_native_is_initialized()` is exported and bound, but `NativeEngine` never
calls it — its only Java callers are `NativeEndToEndTest:79` and
`NativeLoaderTest:423`. The engine tracks liveness by its own reference, because
a process-wide flag cannot answer "is *this* handle usable".

## 3. The native lifecycle is one flag

Four facts, each of which the CTest units assert:

1. **`veltis_native_init()` succeeds.** It constructs the static-local engine,
   release-stores `true`, clears the error slot and returns `VELTIS_NATIVE_OK`.
   There is no allocation and no dependency, so there is no reachable failure
   path. The `catch (...)` at [native_engine.cpp:76-79](../src/cpp/native_engine.cpp#L76-L79)
   exists because the public header promises that no export throws into a caller,
   not because a failure mode is known.
2. **It is idempotent.** `init` on an initialized engine returns `OK` again
   (`test_repeated_lifecycle`, 100 cycles).
3. **`shutdown()` clears the flag and is idempotent**, returns `void`, and can be
   followed by another successful `init` (`test_shutdown`, `test_repeated_lifecycle`).
4. **There is no reference count and no barrier.** The first `shutdown()` clears
   the flag for every caller in the process, and nothing waits for in-flight
   calls inside the library. The waiting is Java-side; see
   [THREADING_MODEL.md](THREADING_MODEL.md) §5.

### Behaviour before `init`

| Export | Before `init` | Why |
| --- | --- | --- |
| `abi_version`, `version_major/minor/patch`, `version_string`, `status_string` | work normally | pure compile-time constants, [native_utils.c:50-89](../src/c/native_utils.c#L50-L89) |
| `capabilities` | returns the full mask | the mask is a compile-time constant too, so gating it would make the loader's first query order-dependent |
| `last_error` | returns the empty string | thread-local slot is zero-initialized |
| `is_initialized` | returns `0` | it is the query itself |
| `self_test` | `VELTIS_NATIVE_ERROR_NOT_INITIALIZED` | it runs the algorithm, so it needs an engine |
| `transform_i32` | `VELTIS_NATIVE_ERROR_NOT_INITIALIZED` | [native_engine.cpp:139-149](../src/cpp/native_engine.cpp#L139-L149) |
| `shutdown` | no-op, clears the error slot | idempotence is a caller convenience |

The guard is the **first** thing `transform_i32` does, ahead of the length,
null, alignment, extent and overlap checks
([native_engine.cpp:129-183](../src/cpp/native_engine.cpp#L129-L183)). That order
is deliberate and load-bearing: a caller that has not initialized gets a lifecycle
answer, not a confusing `INVALID_ARGUMENT` about a buffer it was never going to
use. It also means the "not initialized" result cannot depend on the arguments,
which is what lets `test_shutdown_while_transforms_run` treat
`ERROR_NOT_INITIALIZED` as a legitimate outcome of a race.

## 4. The Java load path, step by step

`initialize(config)` — engine **write lock** for the whole body
([NativeEngine.java:56-80](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L56-L80)):

```
1. Objects.requireNonNull(config)
2. if (initialized) return status;           ← initialize-once
3. config = requested                         (volatile write)
4. initialized = true
5. transformFailures.clearBudget(); selfTestFailures.clearBudget()
6. if (!config.enabled())  → status = disabled, return
7. if (!config.autoload()) → status = disabled("call load()"), return
8. return loadLocked()
```

Step 4 before step 8 is intentional and is the most misread line in the class.
`initialized` does **not** mean "the engine is running"; it means "this instance
has answered its one autoload question". Setting it first is what makes two
threads calling `initialize` at startup perform one load attempt
(`concurrentInitializationLoadsAtMostOneLibrary` asserts every caller gets the
*same* `NativeStatus` instance back). A failed load therefore leaves
`initialized == true` with `library == null`, and the way to retry is `load()`,
not a second `initialize()`.

`load()` is the operator-facing retry
([NativeEngine.java:87-103](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L87-L103)):
it also sets `initialized`, returns the current status when a library is already
live, respects `config.enabled()`, and otherwise runs the same `loadLocked()`.

`loadLocked()` — the only place a library becomes visible
([NativeEngine.java:105-153](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L105-L153)):

```
 1. NativeLoader.tryLoad(config)
      · platform recognized · file located · container/architecture checked
      · library mapped into Arena.ofShared() · ABI + engine version accepted
      · every bound symbol resolved
      failure → status = result.status(); return          [library never existed]
 2. loaded.initialize()                       ← first use of the handle
      throws  → closeQuietly(loaded); status = unavailable; return
      rc != 0 → closeQuietly(loaded); status = unavailable(lastError, rc); return
 3. loaded.capabilitiesMask() → NativeCapabilities.fromMask(...)
      throws  → closeQuietly(loaded); status = unavailable; return
 4. library = loaded                           ← PUBLICATION
 5. status = available
```

Steps 2 and 3 happen on a handle that is **not yet reachable by any caller**, so
"rollback" is simply closing it. `closeQuietly`
([NativeEngine.java:156-162](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L156-L162))
swallows a close failure on purpose: the status being returned is the diagnostic
the operator needs, and a second exception would hide the first.

### Why step 4 precedes step 5

*Corrected during the Phase 7 review.* An earlier version of this section said
"a lock-free reader in `transform()` can observe one write without the other".
It cannot. Both writes happen inside the engine **write** lock and `transform()`
and `selfTest()` take the **read** lock before touching either field
([NativeEngine.java:201-205](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L201-L205),
[NativeEngine.java:252-256](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L252-L256)),
so a running operation blocks until `loadLocked()` returns and then sees both.
The exposed readers are the ones that take no lock at all.

| Order | What a lock-free diagnostic reader may see | Consequence |
| --- | --- | --- |
| `library` then `status` (**current**) | `nativeAvailable() == true`, `status().available() == false` | one log line reports Java fallback for an engine that is in fact native; the next read is consistent |
| `status` then `library` | `status().available() == true`, `nativeAvailable() == false` | reports native support with no library behind it — the shape that invites a caller into an operation path that then falls back |

Why this stays two fields instead of one immutable pair: the straddle is between
two *accessor calls*, not inside one read, so a fused record would not remove it.
The current order is the benign one, and `nativeAvailable()` has no production
caller — the readers are `NativeBootstrapTest`, `NativeEndToEndTest` and
`NativeEngineTest`, and every operation re-reads `library` under the lock.

The invariant `library != null ⇒ status.available()` holds once `loadLocked()`
returns, and `shutdown()` restores it trivially by nulling `library` before it
changes `status` — transiently observable only by the same lock-free readers.

### Reachability of the three rollback branches

Recorded as behaviour-vs-assumption, because two of the three are reachable and
one is not:

| Branch | Reachable how | Test |
| --- | --- | --- |
| `initialize()` throws | a handle whose downcall fails | `compatibleLibraryWithAMissingSymbolIsRejected` covers the earlier binding failure; no fixture makes a *bound* `init` throw |
| `initialize()` returns non-zero | the `fake_init` fixture (mode 1) | `nativeInitializationFailureIsReportedAndFallsBack`, `nativeInitializationFailureWithFallbackDisabledStillProducesAUsableEngine`, `NativeLoaderTest.initializationFailureIsReportedByTheLoadedLibrary` |
| capability query throws | **not reachable today.** `Bindings.bind` resolves all seven handles before `LoadedLibrary` exists, and `veltis_native_capabilities` is a constant return, so the only way to reach the catch is a closed arena — which the write lock prevents. | none; defensive code |

## 5. The Java shutdown path, step by step

`shutdown()` — engine **write lock**
([NativeEngine.java:164-185](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L164-L185)):

```
1. loaded = library
2. library = null                ← un-publish BEFORE closing
3. loaded.close()  (if non-null)  ── library write lock
                                     · if (closed) return
                                     · closed = true
                                     · bindings.shutdown()   [try]
                                     · arena.close()         [finally]
                                   throws → LOG.warning, continue
4. initialized = false
5. status = disabled("native engine stopped")
6. clear both failure-budget throttles
```

Three points worth keeping in mind when changing this method:

- **Nulling first is what makes step 3 safe.** A caller that acquires the engine
  read lock after step 2 sees `library == null` and cannot touch a closing
  handle. A caller already inside the read lock is why step 3's *library* write
  lock exists at all — the two locks protect the same window from two sides, and
  removing either one reopens it.
- **`arena.close()` is in a `finally`.** If `veltis_native_shutdown()` throws,
  the arena is still released. Leaking the mapping would be worse than the
  logged warning, and both paths end in the same Java status.
- **Step 4 resets `initialized`,** which is what allows a start → stop → start
  cycle in the runtime to autoload again. `VeltisBootstrap.stopNative()` calls
  this and swallows nothing but logs everything
  ([VeltisBootstrap.java:110-116](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L110-L116)).
- **Step 6 is a generation boundary.** A throttle carried across a restart would
  suppress the first warnings of the new generation, which is exactly when an
  operator is looking for them. `repeatedNativeTransformFailuresDoNotWriteAWarningPerCall`
  asserts the counter is zero after `shutdown()`.

`shutdown()` with no library, and `shutdown()` twice, are both fine: step 3 is
skipped and steps 4-6 are re-applied (`repeatedInitializationAndShutdownAreSafe`,
`test_shutdown` for the native half).

## 6. Verification

Every required lifecycle item from the order maps onto a test that exists and was
executed on the host described in [FOUNDATION_AUDIT.md](FOUNDATION_AUDIT.md) §10.

| Required check | Native test (CTest) | Java test | Status |
| --- | --- | --- | --- |
| initialization | `test_lifecycle_and_capabilities` | `nativeSelfTestAndLifecycleWorkThroughTheRealLibrary` | passed |
| repeated initialization | `test_repeated_lifecycle` (100 cycles) | `repeatedInitializationAndShutdownAreSafe` | passed |
| repeated init + reload through the real library | — | `repeatedInitializeAndShutdownCyclesReleaseAndReloadTheLibrary` | passed |
| failed initialization | — (no reachable failure, §3) | `nativeInitializationFailureIsReportedAndFallsBack`; `...WithFallbackDisabledStillProducesAUsableEngine`; `initializationFailureIsReportedByTheLoadedLibrary` | passed |
| failed load with no file | — | `missingLibraryUsesTheJavaFallback`, `anUnusableConfiguredPathIsAReportedFailureNotAnException` | passed |
| partial-init rollback | — | §4 branch table: reachable via `fake_init`; the unpublished handle is closed, asserted by `assertFalse(engine.nativeAvailable())` plus `assertTrue(status.loaded())` | passed |
| shutdown | `test_shutdown` | `transformAfterShutdownUsesTheJavaFallback` | passed |
| repeated shutdown | `test_shutdown` ("shutdown is idempotent") | `repeatedInitializationAndShutdownAreSafe` (two calls) | passed |
| concurrent calls | `test_concurrent_transforms`, `test_concurrent_lifecycle` | `concurrentTransformsThroughTheEngineAreCorrect`, `concurrentLifecycleCallsPublishOneConsistentState` | passed — see [THREADING_MODEL.md](THREADING_MODEL.md) §12 |
| calls during shutdown | `test_shutdown_while_transforms_run` | `shutdownWhileCallsAreActiveNeverProducesWrongResults` | passed |
| calls after shutdown | `test_shutdown` ("transform refuses to run after shutdown") | `closedLibraryRejectsFurtherOperations`, `transformAfterShutdownUsesTheJavaFallback` | passed |
| re-initialization after shutdown | `test_shutdown`, `test_concurrent_lifecycle` tail | `engineCanInitializeTransformShutDownAndReloadTheRealLibrary` | passed |

Gap, stated rather than hidden: the **partial-init rollback of a handle whose
`init` throws** (first row of §4's branch table) has no test, because no fixture
produces it — a symbol that cannot be resolved fails earlier, in `Bindings.bind`.
The code path is correct by inspection (`closeQuietly` then `unavailable`), and it
is the same three lines as the `rc != 0` branch that *is* tested. Adding a
`VELTIS_FIXTURE_MODE=4` that aborts inside `init` would crash the test JVM rather
than exercise it, so the honest resolution is to keep the branch and keep the
label "untested by construction", not to invent a passing test for it.

## 7. Lifecycle rules for the next capability

1. A capability that needs setup must do it inside `loadLocked()`'s step 2/3
   region — i.e. before publication — and its failure must produce
   `NativeStatus.unavailable` plus a closed unpublished handle. Do not add a
   publication point.
2. A capability that needs teardown must be added to `LoadedLibrary.close()`
   before `arena.close()`, and it must be exception-safe, because the `finally`
   is what guarantees the arena is released.
3. Do not use `veltis_native_is_initialized()` to decide whether to call the
   library from Java. The process-wide flag cannot distinguish one handle from
   another (§2), and the Java reference already answers the question a caller
   actually has.
4. Do not add a third lifecycle lock. `NativeEngine.lifecycle` →
   `LoadedLibrary.lifecycle` is the whole hierarchy; a new lock must be placed
   inside it and documented in [THREADING_MODEL.md](THREADING_MODEL.md) §4.
5. Any new status code must be added to `veltis_native_status_string`'s switch and
   to the ABI test's documented-code set, or `abi_contract_c.c`'s
   `is_documented_status` check will fail the build — which is the intended
   outcome, not a flake.
