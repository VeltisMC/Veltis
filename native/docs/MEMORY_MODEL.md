# veltis-native Memory Model

How memory for a native call is obtained, who owns it, how long it lives, and
what would have to change before this module is allowed to allocate anything.

## 1. The one rule

**The caller owns every buffer. The native library allocates nothing.**

No export returns memory, no export takes ownership of memory it is given, and
there is no free function anywhere in the ABI. `tests/export_check.c` probes for
allocator-shaped export names (`alloc`, `free`, `release`, `last_error_free`) and
fails the build if one appears, so this is an enforced rule rather than a habit.

Verified, not assumed: a grep of `native/src/c` and `native/src/cpp` for
`malloc`, `calloc`, `realloc`, `free`, `operator new`, `new`, `mmap`,
`VirtualAlloc`, `std::allocate` and `aligned_alloc` returns **zero matches**
across `native_utils.c`, `native_internal.h`, `native_engine.cpp`,
`algorithms.cpp` and `native_buffer_checks.hpp`. There is no allocation path in
this library at all.

## 2. Consequences of that rule

| Concern | Answer |
| --- | --- |
| Which CRT's `free` matches this allocation? | There is no allocation, so the question cannot be asked. |
| Can a caller on another libc corrupt the library's heap? | There is no library heap. |
| What happens on an OOM inside a native call? | It cannot originate natively. Java raises before the downcall; see §5. |
| Does the ABI need a size hint or capacity parameter? | No. Both lengths are exact and must be equal. |
| Can a caller leak? | Only its own memory, which is not this module's contract. |

The static-local engine object is the only native storage with a lifetime longer
than a call, and it holds one `std::atomic<bool>`. The error slot is thread-local
storage of 256 bytes
([native_utils.c:22-24](../src/c/native_utils.c#L22-L24)), owned by the runtime's
TLS implementation, never freed by anyone.

## 3. Where the memory for one call actually comes from

`NativeEngine.nativeTransform`
([NativeEngine.java:304-307](../src/main/java/org/veltismc/nativeengine/NativeEngine.java#L304-L307)):

```java
try (var arena = Arena.ofConfined()) {
    var inputSegment  = arena.allocateFrom(ValueLayout.JAVA_INT, input);  // copy in
    var outputSegment = arena.allocate(ValueLayout.JAVA_INT, input.length);
    ...downcall...                                                        // copy out
}
```

- **Off-heap, confined, per call.** The arena is closed by the try-with-resources
  even when the downcall throws, so a failing native call cannot leak the two
  segments.
- **Cost: about 8 bytes per element**, plus the Java `int[]` result the caller
  already needed. That is also why the production native path never beat the
  allocation-free `java-optimized` baseline in `NativeBenchmark` at any batch
  size — see [BENCHMARKING.md](BENCHMARKING.md) (Phase 9).
- **Alignment is automatic.** `ValueLayout.JAVA_INT` has 4-byte alignment, which
  satisfies the ABI's natural-alignment requirement, so a Java caller can never
  trip the alignment rejection by accident. A C or C++ consumer must align its
  own buffers.
- **Bulk, never per element.** One `MemorySegment.copy` in, one downcall, one
  copy out. A per-element downcall is forbidden by the integration rules; if a
  future shape needs it, the shape is wrong.

## 4. Buffer validation infrastructure

Three predicates decide whether a pointer-and-length pair is safe to touch. They
were anonymous functions inside one translation unit; they are now header-level
and reusable, because the second export that needs them must not re-derive the
arithmetic:

[src/cpp/native_buffer_checks.hpp](../src/cpp/native_buffer_checks.hpp),
namespace `veltis::native::detail`

| Predicate | Contract |
| --- | --- |
| `checked_byte_extent<T>(count, bytes)` | false when `count * sizeof(T)` would wrap `size_t`; only writes `bytes` on success |
| `naturally_aligned<T>(pointer)` | false for every address that is not a multiple of `alignof(T)`; a null pointer is *aligned* — its validity is a separate check, so the two rules cannot be confused for one |
| `byte_ranges_overlap(a, n, b, m)` | integer-address comparison (unrelated pointers are not comparable in C++); an extent that would wrap the address space is reported as overlapping, i.e. invalid, never as safe; ranges that merely touch do **not** overlap |

These are pure functions: no allocation, no state, no locking, `constexpr` where
the language allows. They are tested **directly** in
`native_tests.cpp:test_buffer_predicates_directly`, which covers what an export
call cannot reach:

- the exact extent boundary — `SIZE_MAX / sizeof(T)` accepted, one past it
  refused, and the caller's output variable untouched on refusal;
- per-element-type behavior (the predicate is a template, so a one-byte type must
  accept the full range and an eight-byte type must refuse earlier);
- every non-multiple alignment offset, 1 through 3;
- touching ranges, a one-byte gap, a zero-length range at the same address,
  containment from either side, and a wrapping extent.

Rejecting merely-touching ranges would have refused a legal call, and accepting a
wrapping extent would have reported a nonsense buffer as safe. Neither is
reachable through `veltis_native_transform_i32`'s status code alone, which is the
reason this test exists.

`veltis_native_transform_i32` uses exactly these three, in the documented order
([native_engine.cpp:129-193](../src/cpp/native_engine.cpp#L129-L193)). The Java
side has its own equivalent: `NativeLoader.LoadedLibrary.requireExtent` uses
`Math.multiplyExact`, so the overflow rule is enforced before a segment is even
created, and a Java caller never sees a native rejection it could have predicted.

## 5. Allocation-failure reporting

Allocation can fail in exactly two places, and both are already handled:

1. **Java, before the downcall.** `Arena.ofConfined()` or `allocate` throwing
   `OutOfMemoryError`, or `requireExtent` throwing `ArithmeticException`. The
   arena's try-with-resources releases whatever was allocated; `NativeEngine`
   catches `Throwable`, throttles the warning and falls back to
   `javaTransform` — or throws `IllegalStateException` when
   `veltis.native.fallback=false`, which is the operator's "prove it" setting.
2. **Native, inside an export.** Not possible today. If a capability ever
   allocates, the shape is fixed by this document: the allocation is internal to
   the call, freed before the call returns, and a failure returns
   `VELTIS_NATIVE_ERROR_INTERNAL` with a thread-local message. It must never
   return a null pointer through an out-parameter that a caller has to check, and
   it must never hand the caller something to free.

There is deliberately no `ERROR_OUT_OF_MEMORY` code: adding one to the frozen
enum is an ABI change, and `ERROR_INTERNAL` plus `last_error()` already carries
the information a documented code would carry.

## 6. Debug diagnostics and statistics

- **Counters, opt-in.** `veltis.native.stats=true` enables two `LongAdder`
  counters — `nativeInvocations` and `fallbackInvocations` — exposed as
  `NativeEngine.stats()`. They are incremented only when configured, so the
  default path pays nothing, and `LongAdder` keeps a contended increment cheap
  without adding a lock.
- **Suppression accounting rides in the log line.** `FailureLogThrottle` reports
  how many identical failures it held back, so a bounded warning stream is still
  an honest one.
- **No allocation tracing exists**, because no allocation exists. A debug
  allocator with tag/owner/freed-once bookkeeping is the first thing that must
  ship the day native allocates, not a later cleanup.

## 7. What this module refuses to build, and why

| Temptation | Decision |
| --- | --- |
| A custom allocator | No. Nothing to allocate, and the JVM already owns the only hot path's memory. |
| A pool or arena for native buffers | No. The Java confined arena per call is the pool; a native one would reintroduce the ownership problem §1 removes. |
| Lock-free allocation structures | No. No allocation to make lock-free, and no measured contention. |
| Reusing one segment across calls | Not in the production path. It is the only shape that ever won in the benchmark (256–65,536 elements), and it is a *Java-side* integration change to be judged with Phase 9 evidence, not a native memory system. |
| A public allocator API | No, explicitly forbidden. An out-of-the-box free function is an ABI promise across CRTs. |

The rule these follow: profiling evidence first. "This might be faster" is not an
architectural requirement, and this module has one measured hotspot (chunk codec
serialization) that is a Java problem.

## 8. Lifecycle table

| Memory | Created | Owner | Released | On failure |
| --- | --- | --- | --- | --- |
| Transform input segment | per call, Java | the confined arena | arena close | arena close |
| Transform output segment | per call, Java | the confined arena | arena close | arena close |
| `int[]` result | per call, Java heap | the caller | GC | n/a |
| Library handle + `Arena.ofShared()` | at load | `LoadedLibrary` | `close()`, under the write lock | closed by `tryLoad` on every rejection |
| Error slot | lazily per thread, 256 B static TLS | the C runtime | process exit | n/a |
| Engine object | first `instance()` call | static local | process exit | n/a |

Nothing in the table is freed by a native export, and nothing in the table
outlives the Java object that owns it.

## 9. Rules for a capability that needs memory

1. First prove the buffer cannot stay the caller's. Every v1 export kept it
   caller's, and that is why shipping works.
2. If it genuinely must allocate: internal only, freed before returning, no
   pointer escapes, `ERROR_INTERNAL` on failure, and a test that fails the call
   at allocation and asserts nothing leaked.
3. Then, and only then, does `src/memory/` exist — with an aligned
   allocate/free pair, the overflow-safe sizing already in §4, allocation-failure
   reporting, debug bookkeeping (tag, owner, freed-once) and an opt-in counter, all
   internal to the library and never exported.
4. Update this document first. A memory rule that lives only in a source file is
   a rule the next contributor will break.
