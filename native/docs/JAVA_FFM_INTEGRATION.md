# Java FFM integration

Scope: how the JVM side of `veltis-native` finds, validates, binds, calls and
releases the optional native library; which of the review points the Phase 7
audit could confirm from code and an executed test, and which it could not.
Line references are to the files as they stand after that audit. Host: Windows
11 x86-64, JDK 25.0.5 (Oracle), MinGW-w64 GCC 16.2.0 lane, CMake 3.31.6, Ninja
1.12.1, executed 2026-11-04. Anything not executed here is labelled **not
tested**, per the rule in `PLATFORM_SUPPORT.md` §4, and the distinction between
"the code says X" and "X was observed to happen" is kept throughout.

The five classes involved:

| File | Lines | Owns |
| --- | --- | --- |
| [NativeLoader.java](../src/main/java/org/veltismc/nativeengine/NativeLoader.java) | 875 | platform naming, file search, container/architecture validation, symbol resolution, ABI acceptance, the bound handles, `LoadedLibrary` |
| [NativeEngine.java](../src/main/java/org/veltismc/nativeengine/NativeEngine.java) | 348 | lifecycle (init/load/shutdown), publication of the live library, operation dispatch, Java fallback, counters |
| [NativeConfig.java](../src/main/java/org/veltismc/nativeengine/NativeConfig.java) | 118 | `veltis.native.*` reading and precedence |
| [NativeStatus.java](../src/main/java/org/veltismc/nativeengine/NativeStatus.java) | 66 | the immutable outcome value shared with the server |
| [NativeCapabilities.java](../src/main/java/org/veltismc/nativeengine/NativeCapabilities.java) | 60 | the ABI capability bits, decoded |
| [FailureLogThrottle.java](../src/main/java/org/veltismc/nativeengine/FailureLogThrottle.java) | 94 | the bound on repeated failure diagnostics |

Server contact is three lines: `VeltisBootstrap.initializeNative`
([VeltisBootstrap.java:93-108](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L93-L108))
and `stopNative`
([VeltisBootstrap.java:110-116](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L110-L116)),
each wholly inside `catch (Throwable)`.

---

## 1. The fourteen review points

Every row was checked against the code and then against a test that was executed
on this host today. "Verified" below means both, not the first alone.

| # | Point | Where | Evidence |
| --- | --- | --- | --- |
| 1 | Correct library selection | `locate` ([NativeLoader.java:386-407](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L386-L407)), `candidatePaths` ([:414-427](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L414-L427)) | explicit path (file *or* directory) wins; otherwise `<veltismc.home>/native/<platform>`, `<cwd>/native/<platform>`, `<cwd>/build/native/<platform>`; verified by `locateAcceptsAConfiguredDirectoryOrFile`, `locateDoesNotAcceptADirectoryWithoutThePlatformLibrary`, `missingLibraryIsAReportedFailureNotAnException` |
| 2 | Correct OS detection | `normalizeOs` ([:675-690](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L675-L690)) | macOS spellings are tested before Windows ones; unknown systems get a named "unsupported platform" failure rather than a loader error — `platformKeysNormalizeOsAndArchitectureIndependently` |
| 3 | Correct CPU architecture detection | `normalizeArch` ([:692-699](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L692-L699)) + `checkBinary`/`probeBinary` ([:438-465](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L438-L465)) | the file's own machine type is read before the OS loader is invoked; `probesPeElfAndMachOHeaders`, `peArm64AndX86AreToldApartFromX8664`, `aLibraryForAnotherArchitectureIsRejectedBeforeTheSystemLoaderRuns`, `builtLibraryMatchesTheHostFormatAndArchitecture` |
| 4 | ABI version validation | `tryLoad` ([:335-357](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L335-L357)), `checkCompatibility` ([:723-737](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L723-L737)) | ABI is exact, engine major must match, newer minor accepted; ABI is read *before* the version symbols so an old library is reported as incompatible, not as missing symbols — `abiMismatchIsRejectedBeforeUse`, `wrongAbiIsReportedBeforeMissingVersionSymbols`, `realLibraryWithWrongAbiIsRejected` |
| 5 | All 13 exports resolve | `Bindings.bind` ([:804-813](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L804-L813)) = 7, handshake in `tryLoad` = 6 | `veltis_native_exports` (CTest) pins the artifact's export list to the header's 13; `compatibleLibraryWithAMissingSymbolIsRejected` proves a dropped symbol is a reported failure; `realLibraryLoadsAndReportsItsAbiAndCapabilities` exercises the real DLL |
| 6 | Correct FFM method descriptors | [:126-142](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L126-L142) | §2 of this document, plus `abi_pin_all_signatures()` in `tests/abi_contract_checks.h`, which restates each signature as a C function-pointer type — a descriptor that disagrees with the header is a compile error, and CTest `veltis_native_abi_contract_c` passed |
| 7 | Correct calling conventions | header `VELTIS_NATIVE_CALL` ([veltis_native.h:97-101](../include/veltis/native/veltis_native.h#L97-L101)) | no `LinkerOptions` are set, so `Linker.nativeLinker()` uses the platform default C convention — the only convention on x86-64/AArch64. The `__cdecl` spelling exists for 32-bit Windows, and a 32-bit file can never reach a downcall because `checkBinary` rejects an `x86` machine type before `libraryLookup` |
| 8 | Correct memory layouts | `TRANSFORM` ([:136-142](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L136-L142)), `requireExtent` ([:259-278](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L259-L278)) | `int32_t`↔`JAVA_INT`, `size_t`↔`JAVA_LONG`, pointers↔`ADDRESS`; segment extents and the `multiplyExact` overflow guard are checked in Java because the C side only receives a pointer and a count — `transformRejectsSegmentsShorterThanTheDeclaredExtent`, `nativeTransformWritesExactlyTheDeclaredOutputRange` |
| 9 | Correct pointer handling | `readString` ([:770-777](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L770-L777)), status-text check ([:361-366](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L361-L366)) | every returned pointer is `reinterpret`ed before it is read, NULL is a defined case (empty string for `version_string`/`last_error`, load failure for `status_string`), and buffers handed *to* the library are arena-allocated, hence naturally aligned as the header requires |
| 10 | Correct error handling | `transform` ([NativeEngine.java:246-290](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L246-L290)), `nativeTransform` ([:304-321](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L304-L321)) | a non-zero return becomes an exception carrying the native message, which the engine either logs (throttled) and answers in Java, or rethrows when fallback is disabled — `transformFailureIsReportedByTheLoadedLibrary`, `nativeTransformFailureFallsBackToJava`, `nativeTransformFailureWithFallbackDisabledThrows` |
| 11 | Correct resource ownership | `LoadedLibrary.close` ([:222-239](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L222-L239)), `Arena.ofConfined` per call ([NativeEngine.java:305](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L305)) | Java owns the library arena (shared, closed on shutdown) and the per-call arena (confined, closed by try-with-resources); the library owns its strings and nothing is freed by Java — `repeatedInitializeAndShutdownCyclesReleaseAndReloadTheLibrary`, `closeWaitsForInFlightCallsAndRejectsLaterOnes`, `closedLibraryRejectsFurtherOperations` |
| 12 | Correct thread-local error retrieval | [NativeEngine.java:125](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L125), [NativeEngine.java:310](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L310) | those are the only two production reads of `last_error()` and both run on the thread that made the failing call; the four test reads (`NativeEndToEndTest:111`, `NativeLoaderTest:424,440`, `NativeBenchmark:266,399`) do the same. §3 explains why this is a rule, not a detail |
| 13 | Correct initialization and shutdown | `loadLocked` ([NativeEngine.java:105-153](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L105-L153)), `shutdown` ([:165-185](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L165-L185)) | the library becomes visible only after init succeeded, the capability query is answered before publication, and every rollback closes an unpublished handle — `repeatedInitializationAndShutdownAreSafe`, `concurrentInitializationLoadsAtMostOneLibrary`, `nativeInitializationFailureIsReportedAndFallsBack`, `transformAfterShutdownUsesTheJavaFallback`; ordering detail in `RUNTIME_LIFECYCLE.md` §4 |
| 14 | Correct fallback behavior | `transform`/`javaTransform` ([:246-335](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L246-L335)) | identical bits from both paths, on empty and large inputs, and under concurrent calls with a shutdown in the middle — `javaTransformMatchesTheNativeContract`, `nativeTransformMatchesTheJavaFallbackBitForBit`, `largeBatchesMatchTheJavaFallbackThroughTheRealLibrary`, `concurrentTransformsThroughTheEngineAreCorrect`, `shutdownWhileCallsAreActiveNeverProducesWrongResults` |

---

## 2. The symbol map

The header declares 13 exports
([veltis_native.h:162-233](../include/veltis/native/veltis_native.h#L162-L233)).
Each is bound once, eagerly, at load time — there is no lazy resolution, so a
library that dropped a symbol fails at startup rather than inside a running
server.

| Export | C signature | FFM descriptor | Resolved in |
| --- | --- | --- | --- |
| `veltis_native_abi_version` | `int32_t (void)` | `INT_RESULT` | `tryLoad` handshake |
| `veltis_native_version_major/minor/patch` | `int32_t (void)` | `INT_RESULT` | `tryLoad` handshake |
| `veltis_native_version_string` | `const char* (void)` | `POINTER_RESULT` | `tryLoad` handshake |
| `veltis_native_status_string` | `const char* (int32_t)` | `STATUS_TEXT` | `tryLoad`, and *invoked* there |
| `veltis_native_last_error` | `const char* (void)` | `POINTER_RESULT` | `Bindings` |
| `veltis_native_init` | `int32_t (void)` | `INT_RESULT` | `Bindings` |
| `veltis_native_is_initialized` | `int32_t (void)` | `INT_RESULT` | `Bindings` |
| `veltis_native_shutdown` | `void (void)` | `VOID_RESULT` | `Bindings` |
| `veltis_native_capabilities` | `uint32_t (void)` | `INT_RESULT` | `Bindings` |
| `veltis_native_self_test` | `int32_t (void)` | `INT_RESULT` | `Bindings` |
| `veltis_native_transform_i32` | `int32_t (const int32_t*, size_t, int32_t, int32_t*, size_t)` | `TRANSFORM` | `Bindings` |

`status_string` is the one export no operation calls, and `tryLoad` still
invokes it and rejects the library if it returns NULL or blank
([NativeLoader.java:355-366](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L355-L366)).
That is deliberate and is explained in the source: resolving a handle proves the
symbol exists, but only a call proves the library answers.

`uint32_t` bound as `JAVA_INT` is **not** a defect and was not changed. FFM has
no unsigned 32-bit layout; `JAVA_INT` is the correct width, the bit pattern is
identical, and the only consumer is `NativeCapabilities.fromMask`, which tests
`(mask & bit) != 0` — bit-exact for every one of the 32 bits, including a future
bit 31
([NativeCapabilities.java:43-51](../src/main/java/org/veltismc/nativeengine/NativeCapabilities.java#L43-L51)).
What would be wrong is comparing the mask as a magnitude or printing it as a
signed number; neither happens.

---

## 3. Two rules that only look like details

**The error slot is thread-local.** `veltis_native_last_error()` describes the
*calling thread*'s last call
([veltis_native.h:31-33](../include/veltis/native/veltis_native.h#L31-L33)).
A failure logged from a different thread would report someone else's error.
Today the rule is honoured structurally: the message is read inside the same
`try` block as the call that failed, on the same stack, before anything else runs
on that thread. There is no queue, no shared holder, no async reporter. **A new
native call must keep this shape** — if a future design needs to report a failure
from another thread, the text must be copied out on the calling thread first.

**Strings need a read window.** FFM hands back a zero-length `MemorySegment` at
the pointer's value; reading C text therefore requires `reinterpret(n)` before
`getString(0)`, and `getString` throws if no NUL appears inside that window. The
window is `STRING_READ_LIMIT = 4096`
([NativeLoader.java:71-78](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L71-L78)).
Before this audit, that number was an undocumented guess. It is now backed by a
promise and a test:

- the header states that every returned string is NUL-terminated, library-owned
  and at most 255 bytes
  ([veltis_native.h:24-29](../include/veltis/native/veltis_native.h#L24-L29)),
  which follows from `VELTIS_LAST_ERROR_CAPACITY 256` and `snprintf`
  ([native_utils.c:22-43](../src/c/native_utils.c#L22-L43)) plus literal returns;
- `tests/abi_contract_c.c` asserts the bound for `version_string`, every
  `status_string` name and the message actually recorded by a failing call
  ([abi_contract_c.c:46](../tests/abi_contract_c.c#L46),
  [:92](../tests/abi_contract_c.c#L92),
  [:116](../tests/abi_contract_c.c#L116),
  [:260](../tests/abi_contract_c.c#L260)).

That guard was proven to be able to fail, not just to pass: setting
`MAX_STRING_BYTES` to 3 produced eight `FAIL:` lines and a red CTest run, and the
value was restored afterwards.

---

## 4. Initialization contract: what may be called before init

`tryLoad` reads the capability mask before it initializes the engine
([NativeLoader.java:368-371](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L368-L371)).
The header previously said only that "every operation other than the
version/status queries" requires init, which left that call looking like a
contract violation carried by luck — the implementation returns a
`constexpr`-style value
([native_engine.cpp:35-37](../src/cpp/native_engine.cpp#L35-L37),
[:102-109](../src/cpp/native_engine.cpp#L102-L109)), so it worked.

The audit resolved the ambiguity in the direction the code and the design both
need, and then made it testable:

- the header now enumerates exactly the two exports that require init
  (`transform_i32`, `self_test`) and states that everything else answers before
  it, including `is_initialized` (which answers 0) and `capabilities`;
- the header states that the mask is a property of the *compiled library*, not
  of the engine state, so pre-init and post-init reads are identical;
- `test_lifecycle_contract` captures the mask before init, compares after init
  and compares again after shutdown
  ([abi_contract_c.c:144-161](../tests/abi_contract_c.c#L144-L161)).
  Inverting that comparison was confirmed to produce a `FAIL:` line before the
  file was restored.

Why the mask is read twice at all: `NativeEngine.loadLocked` re-reads it after a
successful init and publishes *that* value
([NativeEngine.java:137-149](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L137-L149)),
so the answer that governs operation dispatch is always the post-init one. The
loader's pre-init read is what lets `LoadResult.status()` describe the library to
a caller that never initializes it — a diagnostic convenience, not the authority.

---

## 5. Fallback, strict mode, and what is counted

`veltis.native.fallback` decides what a failing native operation *does*, and the
decision is local to the call site, never global state:

| Situation | `fallback=true` (default) | `fallback=false` |
| --- | --- | --- |
| no library at all | Java result, `status.summary()` explains why | `IllegalStateException` naming the status |
| library active, native call fails | warning (throttled), Java result | `IllegalStateException` with the native cause |
| library active, capability not advertised | Java result | `IllegalStateException` |
| after `shutdown()` | Java result | `IllegalStateException` |

Disabling fallback is an operator's explicit strictness, so the exception is the
correct signal and it is deliberately *not* throttled — a throttled strict mode
would hide the failures it exists to surface. The default configuration
(`NativeConfig.defaults()` = enabled, autoload, fallback, stats off, no path)
means a stock server never sees either an exception or a per-chunk warning
([NativeConfig.java:54-56](../src/main/java/org/veltismc/nativeengine/NativeConfig.java#L54-L56)).

Counters increment only when `stats=true`
([NativeEngine.java:260-262,283-285](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L260-L285));
`LongAdder` keeps them off the hot lock. Verified by `statsOnlyCountWhenEnabled`.

Repetition is bounded by `FailureLogThrottle`: the first three occurrences of one
fault are logged, later ones are counted and reported with the next message
([FailureLogThrottle.java:53-72](../src/main/java/org/veltismc/nativeengine/FailureLogThrottle.java#L53-L72)),
a success resets the budget, and a clock that moves backwards does not open the
window. The counters are separate atomics updated without a transaction — two
threads in the same burst can each write `windowStart`. That is an approximation
of a *timing* window, never of a count that decides whether work happens: no
message is ever lost, the burst limit is exact, and the only consequence is that
the next window may open a few nanoseconds early or late. `concurrentFailuresShareOneWindow`
and the throttle suite cover it; the audit found no reason to add a lock to a log
policy.

---

## 6. Bulk calls only

One `transform` call is one downcall over the whole array: input copied with
`arena.allocateFrom(ValueLayout.JAVA_INT, input)`, output copied back with
`MemorySegment.copy`
([NativeEngine.java:304-321](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L304-L321)),
and `Arena.ofConfined()` so the scratch memory is released when the call returns.
There is no per-element crossing anywhere in the module, and the empty array
returns before the lifecycle lock is even taken
([NativeEngine.java:248-250](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L248-L250)).
This is the shape every future native operation must keep; `BENCHMARKING.md`
(Phase 9) is where the overhead of the two copies gets measured rather than
argued.

---

## 7. Operational facts an operator cannot read off a log line

- **The JVM needs `--enable-native-access=ALL-UNNAMED`.** `SymbolLookup.libraryLookup`
  and `Linker` are restricted methods. The launcher supplies it in
  `REQUIRED_JVM_OPTIONS`
  ([VeltisLauncher.java:455-458](../../launcher/src/main/java/org/veltismc/launcher/VeltisLauncher.java#L455-L458)),
  and the Gradle test tasks pass it
  ([native/build.gradle.kts:604](../build.gradle.kts#L604),
  [native/build.gradle.kts:695](../build.gradle.kts#L695),
  [server/build.gradle.kts:70](../../server/build.gradle.kts#L70)). Running the
  server some other way still loads the library, but the JVM prints a warning it
  cannot suppress — the reason the launcher passes the flag rather than hiding
  output.
- **Install layout.** `candidatePaths` looks for `native/<platform>/` under
  `veltismc.home`, then the working directory's `native/` and `build/native/`
  subdirectories. `:veltis-native:packageNativeArchive` puts the library, its
  manifest and `SHA256SUMS` at the *root* of the zip
  ([native/build.gradle.kts:793-801](../build.gradle.kts#L793-L801)), so the
  archive is extracted *into* `<home>/native/<platform>/` — the zip is not
  self-nesting. Nothing in `native/docs` said this before this audit; the README
  still does not (Phase 11 item).
- **Where the five knobs come from, and which one wins.** `NativeConfig.load`
  reads `<home>/server.properties` and then overlays *every* system property
  whose name starts with `veltis.native.`
  ([NativeConfig.java:69-86](../src/main/java/org/veltismc/nativeengine/NativeConfig.java#L69-L86)),
  so a `-D` on the command line overrides the file for one run without a second
  configuration framework. Three consequences an operator needs to know:
  an unreadable or malformed properties file is logged and ignored, because a
  native setting must never be a reason the server does not start; a boolean
  whose value is neither `true` nor `false` (case-insensitive, surrounding
  whitespace allowed) is logged, and the **default** is used rather than the
  file's value — so a typo in `veltis.native.fallbak`-style noise silently keeps
  `fallback=true`, and a typo in a *value* warns
  ([NativeConfig.java:99-117](../src/main/java/org/veltismc/nativeengine/NativeConfig.java#L99-L117));
  and `veltis.native.path` is a *replacement*, not a preference —
  `locate` returns empty if the configured file (or directory + platform
  filename) is not a regular file, and the
  `veltismc.home`/working-directory search never runs
  ([NativeLoader.java:386-407](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L386-L407)).
  A stale `-Dveltis.native.path` is therefore indistinguishable from "no library
  exists" unless you read the status summary, which names the path it tried
  ([NativeLoader.java:302-311](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L302-L311)).
  `veltis.native.enabled=false` skips the lookup entirely.
- **`NativeEngine.load()` has no production caller.** It is the manual
  post-startup retry, reachable through `VeltisBootstrap.nativeEngine()`
  ([VeltisBootstrap.java:85-87](../../server/src/main/java/org/veltismc/runtime/VeltisBootstrap.java#L85-L87));
  the only callers today are `NativeEngineTest.manualLoadIsIdempotentAndFallsBackWhenTheLibraryIsAbsent`
  and the engine's own disabled-autoload path. `initialize()` sets `initialized`
  before it attempts the load, so a failed startup load is *not* retried by a
  second `initialize()` — that is the documented one-question semantics of
  `initialize`, and `load()` is the retry
  ([RUNTIME_LIFECYCLE.md §4](RUNTIME_LIFECYCLE.md)).

---

## 8. What the audit changed, and what it left alone

Changed, all four justified by a specific finding rather than by taste:

1. `veltis_native.h` — the pre-init export set and the string-length promise are
   now written down instead of inferred; no signature, macro or export changed,
   so the ABI stays at 1 and the export count stays at 13.
2. `tests/abi_contract_c.c` — the promise is enforced: string bounds, and
   capability-mask invariance across init and shutdown. Both new guards were
   shown to fail when the condition they check is broken.
3. `NativeLoader.java` — `STRING_READ_LIMIT` now carries the reason for 4096.
4. `RUNTIME_LIFECYCLE.md` §"Why step 4 precedes step 5" — a claim in that
   section was wrong and is corrected here, because a false explanation of a
   lock is worse than no explanation. It said a lock-free reader in `transform()`
   could observe the `library`/`status` pair torn. It cannot: `transform()` and
   `selfTest()` take the read lock
   ([NativeEngine.java:201-205,252-256](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L201-L256))
   and every write happens inside the write lock, so an operation blocks until
   `loadLocked()` returns and then sees both fields. The real observers are the
   lock-free accessors `status()`/`nativeAvailable()`/`capabilities()`
   ([:187-197](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L187-L197)),
   they can straddle a transition, and the chosen write order is the benign one.
   `nativeAvailable()` has no production caller today.

Left alone, with the reason:

| Temptation | Why nothing changed |
| --- | --- |
| Merge `library` + `status` into one immutable record | the straddle is between two *accessor calls*, not inside one read; a fused field would not remove it and would rewrite the whole lifecycle for a diagnostics-only transient |
| Make `initialize()` retry a failed load | one-question semantics is what `concurrentInitializationLoadsAtMostOneLibrary` asserts (every caller must observe the *same* status instance); `load()` is the retry |
| Bind `capabilities` as unsigned, or split the mask | `JAVA_INT` is the right width and the only consumer is a bit test (§2) |
| Put a lock in `FailureLogThrottle` | its counters are exact; only the window *instant* is approximate, and the consequence is a log line a few nanoseconds early (§5) |
| Reference-count the process-wide C++ engine across `LoadedLibrary` instances | real, already recorded as R6 in `FOUNDATION_AUDIT.md`; with fallback enabled the outcome is a correct Java result, in production there is exactly one `NativeEngine`, and the multiple-instance case is a test artefact. A refcount is a runtime-lifecycle change, not an FFM fix |
| Change `Bindings.lastError()` to distinguish a native message from an exception description | the description path is reachable only if the downcall itself fails, and the text is appended to a message that already names the failing operation and status code |

---

## 9. Executed today (Windows x86-64, MinGW-w64 GCC 16.2.0, Release, JDK 25.0.5)

| Lane | Command | Result |
| --- | --- | --- |
| CTest after a clean rebuild | `:veltis-native:cleanNative :veltis-native:testNative -PveltisNativeTest=true` | 4/4 passed (`veltis_native_tests`, `veltis_native_exports`, `veltis_native_abi_contract_c`, `veltis_native_abi_contract_cxx`); the three binaries also run directly print their own "all checks passed" lines |
| Java integration suite, library present | `:veltis-native:test -PveltisNativeTest=true` | 81 tests, 0 failures, 1 skipped (the Java-only-assumption case) |
| Server bootstrap, real library | `:server:test --tests "*NativeBootstrapTest*" -Dveltis.native.path=…\veltis_native.dll` | 12 tests, 0 failures, 0 skipped |
| Server bootstrap, no library | same without the property | 12 tests, 0 failures, 4 skipped — the native-requiring cases stand down instead of failing |
| Freshness guard | incidental | a hand-restored test file newer than the library made `:veltis-native:test` refuse the fixture and say so (`predates the native sources`); the clean rebuild in the first row is what cleared it. The guard was observed working, which is why the suite is reported from a post-rebuild run |

Negative tests were run and reverted, then the green lane above was re-run:
`MAX_STRING_BYTES=3` → 8 failures; inverted capability comparison → 1 failure.
Both restored before the recorded results.

**Not tested by this audit:** Linux and macOS loader paths (no such host here),
MSVC and clang-cl lanes, Windows ARM64 runtime, a JDK other than 25, and a
`fallback=false` server under real load. The `normalizeOs`/`normalizeArch` and
PE/ELF/Mach-O branches for non-host platforms are covered by synthetic headers in
`NativeLoaderTest` — that verifies the parsing, not a real foreign library
loading. See `PLATFORM_SUPPORT.md` §5 for the honest status list.

## 10. Adding the next native call

1. Declare it in `veltis_native.h` with `VELTIS_NATIVE_API … VELTIS_NATIVE_CALL`,
   fixed-width types, pointer + explicit element count, and say in the header
   whether it needs init.
2. Add the matching function-pointer pin in `tests/abi_contract_checks.h` and a
   lifecycle assertion in the C test if the answer may depend on state.
3. Bind it eagerly in `Bindings.bind` with a descriptor whose widths match, and
   resolve it before the library is accepted — never on first use.
4. Bulk in, bulk out. One downcall per batch; copy with `allocateFrom` /
   `MemorySegment.copy`.
5. Read `last_error()` on the calling thread, inside the same block as the call.
6. Route any new string export through the documented bound and extend the
   `MAX_STRING_BYTES` checks.
7. Keep every failure optional: a new capability must be advertised by the
   library and honoured by `NativeCapabilities`, so an old library keeps working
   through the Java path.
