# veltis-native ABI Contract

The rules a native library must satisfy to be loadable by this server, and the
rules the server relies on when it calls one. `include/veltis/native/veltis_native.h`
is the only authority; this document states what the header means, and
`native/CMakeLists.txt` derives the export list, ABI version and export count
from the header itself so the two cannot drift.

Any conflict between this document and the header is a bug in this document.

## 1. Versioning

| Field | Value | Meaning |
| --- | --- | --- |
| `VELTIS_NATIVE_ABI_VERSION` | `1` | Binary interface version. The loader accepts exactly this value ([NativeLoader.java:38](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L38)). |
| `VELTIS_NATIVE_VERSION_MAJOR` | `1` | Engine semantic major. The loader requires exactly 1. |
| `VELTIS_NATIVE_VERSION_MINOR` | `0` | Additive releases. The loader requires at least its own minimum. |
| `VELTIS_NATIVE_VERSION_PATCH` | `0` | Never affects compatibility. |
| `VELTIS_NATIVE_VERSION_STRING` | `"1.0.0"` | Must equal `major.minor.patch` — asserted at runtime by the contract tests. |

**When each changes.** `ABI_VERSION` changes only for a binary-incompatible
change: a parameter, a return type, a calling convention, a documented semantic
an existing caller depends on, or a removed export. A minor bump is for an
additive export plus a new capability bit. A patch bump is for a behavior fix
inside the documented contract.

**A new export is not an ABI change; a changed export is.** The loader never
requires more than the 13 exports it binds, so an older library keeps working
with a newer server and vice versa, provided the ABI version matches and the
capability bits it needs are advertised.

## 2. Export set

Exactly 13 symbols, all declared inside `extern "C"`:

```
veltis_native_abi_version      veltis_native_version_string
veltis_native_version_major    veltis_native_status_string
veltis_native_version_minor    veltis_native_last_error
veltis_native_version_patch    veltis_native_init
veltis_native_capabilities     veltis_native_is_initialized
veltis_native_self_test        veltis_native_shutdown
                               veltis_native_transform_i32
```

Two further symbols exist for the C-to-C++ bridge inside the library —
`veltis_native_set_last_error` and `veltis_native_clear_last_error`
([native_internal.h:15-16](../src/c/native_internal.h#L15-L16)). They are
declared with no visibility attribute, CMake builds with hidden visibility, and
`tests/export_check.c` resolves them by name and fails if either is present.

Nothing else may be exported. Do not add or remove an export to improve
organization; an export is a support promise, not a namespace.

## 3. Calling convention

`VELTIS_NATIVE_CALL` expands to `__cdecl` only on 32-bit Windows
([veltis_native.h:101-105](../include/veltis/native/veltis_native.h#L101-L105)) and to
nothing elsewhere. On x86-64 and AArch64 there is exactly one convention per
platform, so the macro is a no-op that still documents intent and still pins the
right answer on the one platform family where a caller could get it wrong.

A consumer must therefore use the header, not hand-write prototypes. The
function-pointer typedefs in `tests/abi_contract_checks.h` carry the macro too,
so a signature edit that changes the decoration fails to compile.

`VELTIS_NATIVE_API` selects `dllexport`/`dllimport` on Windows (and Cygwin),
`visibility("default")` on GCC/Clang, and nothing elsewhere. `VELTIS_NATIVE_STATIC`
opts out for a static build.

## 4. Type rules

**Widths.** The ABI is defined in terms of `int32_t`, `uint32_t`, `int64_t`,
`uint64_t`, `size_t` and pointers, with 8-bit bytes. `CHAR_BIT != 8` is a compile
error, and the widths are pinned by `static_assert`/`_Static_assert` both in the
header and in `native_utils.c:27-32`.

**No `long`, no `int`, no `short`.** Every parameter uses a fixed-width type or
`size_t`, so a Windows LLP64 `long` and a POSIX LP64 `long` cannot silently
change a signature.

**Booleans are `int32_t`.** `veltis_native_is_initialized` returns 1 or 0; the
`input`/`output`/`salt` parameters are `int32_t`. `bool`/`_Bool` never appears:
its size and its representation of true are not something two independently
built sides should trust.

**`size_t`, not a fixed width, for lengths.** A length is a count in the caller's
address space. The Java FFM descriptor maps it to `JAVA_LONG` because every
supported release target is 64-bit
([NativeLoader.java:131-142](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L131-L142)).
The C ABI keeps `size_t` so a 32-bit C consumer remains possible; the Java loader
rejects any platform outside its six recognized keys before loading.

**No aggregates cross the boundary.** There is no struct, no union, no array
parameter and no by-value aggregate return anywhere in the 13 exports. Consequences:

- field order, packing and padding are not ABI questions today;
- the "struct size and alignment" test is expressed as the alignment test of the
  primitive set that *is* exposed: `_Alignof(int32_t) == 4`,
  `alignof(uint32_t) == 4`, `alignof(int32_t) <= alignof(void*)`, and
  `sizeof(size_t) == sizeof(void*) || sizeof(size_t) == 4`;
- **if a future capability introduces a struct**, it must come with an explicit
  field order, a documented padding rule, a size and alignment assertion in
  `tests/abi_contract_checks.h`, and an ABI version bump if it changes an
  existing signature.

## 5. Alignment and pointer validity

A buffer pointer passed to `veltis_native_transform_i32` must be:

1. non-null whenever its length is non-zero;
2. naturally aligned for `int32_t` (4 bytes) — checked at runtime by
   `naturally_aligned` and rejected with `ERROR_INVALID_ARGUMENT`;
3. readable (input) or writable (output) for exactly `length * sizeof(int32_t)`
   bytes;
4. non-overlapping with the other buffer — checked by `byte_ranges_overlap`.

The alignment rule exists because the implementation may vectorize. Rejecting a
misaligned pointer is cheaper and safer than an unaligned access that happens to
work on one architecture and fault on another.

`ERROR_INVALID_ARGUMENT` is the answer for all four cases. A rejected call
writes nothing, which the contract tests verify by leaving the output buffer
zeroed and asserting it stays zero.

A `NULL` `const char*` is never returned. `version_string`, `status_string` and
`last_error` always return a readable string, valid until the next call on the
same thread, and a caller must never free them.

Those three strings are also bounded: each is NUL-terminated and never longer
than 255 bytes. The bound comes from the thread's error slot — `VELTIS_LAST_ERROR_CAPACITY`
is 256 in `src/c/native_utils.c`, and the other two are compile-time literals far
inside it. The bound is part of the contract because a consumer that reinterprets
a fixed-size window needs to know how large it must be — a `getString` over a
window that ends before the terminator throws instead of returning text.
`NativeLoader` reads 4096 bytes, and `abi_contract_c.c` asserts
`strlen(...) <= 255` for all three, so a library that lengthened its messages
would fail this test before it broke a loader.

## 6. Buffer lengths

`input_length` and `output_length` are **element counts, not bytes**. They must be
equal; `ERROR_LENGTH_MISMATCH` is the answer when they are not, including the
case where the output is larger than the input. There is no capacity model in
this ABI, so `ERROR_BUFFER_TOO_SMALL` is never returned by `transform_i32` — it is
reserved for a future export that takes a capacity rather than an exact length,
and a caller must not assume it can only appear from one operation.

Three length rules:

- **Zero is success.** With both lengths zero the call returns `OK` before any
  pointer is inspected, so a null/null pair is legal there. This is checked in
  that order, deliberately: `NULL, 0` is a defined no-op, not an error.
- **Overflow is rejected, not wrapped.** `length * sizeof(int32_t)` is computed
  by `checked_byte_extent`; a length whose extent exceeds `SIZE_MAX` returns
  `ERROR_INVALID_ARGUMENT`. `SIZE_MAX` and `SIZE_MAX / 2` both fail, and both are
  tested.
- **The check happens before the dereference.** No validation step reads the
  buffer, so a rejected call cannot have touched the caller's memory.

## 7. Ownership

**The caller owns every buffer. No export allocates, and no export hands back
memory that must be freed.** There is no `veltis_native_free`, no release
function, and no ownership transfer anywhere in the ABI — `tests/export_check.c`
probes for allocator-shaped names and fails if one appears.

This single rule removes an entire class of cross-platform defects: no allocator
has to match across the boundary, no CRT has to be shared for a free to be legal,
and a caller on another libc cannot corrupt the library's heap.

The Java side's obligations, mirrored in `NativeEngine.nativeTransform`: allocate
input and output segments in a confined arena, and close that arena even when the
downcall throws.

## 8. Error handling

Every operation except `shutdown` returns a status code from `veltis_native_status`:

| Code | Value | Contract |
| --- | --- | --- |
| `OK` | 0 | The operation completed. |
| `ERROR_INVALID_ARGUMENT` | -1 | A pointer was null with a non-zero length, misaligned, overlapping, or the byte extent overflowed. |
| `ERROR_BUFFER_TOO_SMALL` | -2 | Reserved. Never returned by this build. |
| `ERROR_NOT_INITIALIZED` | -3 | No successful `init` on the current engine state. |
| `ERROR_UNSUPPORTED` | -4 | The capability is not compiled into this library. Returned by the fixtures; the production library reports it only for a genuinely absent operation. |
| `ERROR_INTERNAL` | -5 | An unexpected failure, including any C++ exception caught at the boundary. |
| `ERROR_LENGTH_MISMATCH` | -6 | `input_length != output_length`. |

Rules:

- Negative is failure, 0 is success, and `OK == 0` is pinned by `_Static_assert`
  because callers test against zero.
- A failing call records a message in the thread's error slot. The slot is 256
  bytes and the copy is a bounded `snprintf`, so the message is always
  NUL-terminated and at most 255 bytes; an over-long diagnostic is truncated
  rather than allowed to run past the slot. See §5 for why that bound is a
  consumer-visible promise, and §9 for why the slot is per-thread.
- A **successful** call clears the slot. That is not decoration: it is what stops
  a caller from reading an earlier, unrelated failure as its own.
- `status_string` names every documented code and returns `"unknown status"` for
  anything else, including values outside the enum. A code the library does not
  know is never reported as a code it does.
- No export throws across the boundary. Every `extern "C"` body has a
  `catch (...)` that maps to `ERROR_INTERNAL` (or to a safe value for the
  void/no-status queries: `is_initialized` answers 0, `capabilities` answers
  `NONE`). A Java downcall has no stack to unwind into.
- No export calls `exit()`, aborts, or touches a caller's memory outside the
  validated buffer pair.

## 9. Thread safety

- The library is thread-safe for concurrent calls, including concurrent
  `transform_i32` on distinct buffers, and concurrent lifecycle calls. Covered by
  the 8-thread and 8×300-round CTest cases.
- `init` and `shutdown` are process-wide and idempotent.
- `last_error` is **thread-local storage**: it describes the calling thread's
  last call, is valid until that thread's next call, and must never be freed.
  Consequence: a caller that logs a failure must read the error on the same
  thread that made the call. `NativeEngine` does exactly that; a future consumer
  has to as well.
- The native side provides no barrier on `shutdown`: it clears one atomic flag and
  does not wait for in-flight calls. The serialization that protects a running
  call is Java-side (`NativeEngine.lifecycle` and `LoadedLibrary`'s read/write
  locks). A capability that introduces mutable native state must add a native
  barrier *before* it stores anything a concurrent call can read. See
  [THREADING_MODEL.md](THREADING_MODEL.md) (Phase 4).

## 10. Lifecycle contract

```
uninitialized --init()--> initialized --shutdown()--> uninitialized
```

- Two exports require a prior successful `init` and answer
  `ERROR_NOT_INITIALIZED` without it: `veltis_native_transform_i32` and
  `veltis_native_self_test`. *This list is exhaustive, and was added during the
  Phase 7 review, which found the previous wording — "self_test and every
  operation" — ambiguous enough to read as covering the queries too.* Every
  other export answers before initialization, which is what lets a consumer
  identify and validate a library it has not agreed to start: the four version
  queries, `status_string`, `last_error`, `capabilities`, and `is_initialized`
  (which answers 0). `NativeLoader` depends on exactly that — it reads the ABI
  version, engine version and capability mask before calling `init`.
- `init` is idempotent and always returns `OK` (or `ERROR_INTERNAL` after
  catching something that should not have been catchable).
- `shutdown` returns void and has no failure mode; it clears state and the error
  slot.
- Re-initialization after shutdown is supported and tested, including 100 cycles
  and an 8-thread concurrent lifecycle race.
- `is_initialized` is the only observable state, and it is an `int32_t` 1/0.
- The capability mask describes the *compiled library*, not the engine, so it is
  the same value before `init`, after `init`, and after `shutdown`.
  `abi_contract_c.c:test_lifecycle_contract` captures the mask on both sides of
  the lifecycle and asserts the three reads agree; a mask that changed with
  state would make the loader's pre-`init` eligibility check meaningless.

## 11. Byte order

No multibyte value is serialized across this ABI. Buffers are arrays of
`int32_t` in the caller's own address space and results are numeric values, so
the transform yields the same numbers on little- and big-endian hosts. The Java
FFM layer uses the host's native byte order on both sides of the copy.
`test_byte_order_neutral_values` in `native_tests.cpp` covers the numeric
equivalence of the formula.

## 12. The ten ABI compatibility tests

| # | Required test | Where it runs | Status |
| --- | --- | --- | --- |
| 1 | The header compiles as C | `tests/abi_contract_c.c` → CTest `veltis_native_abi_contract_c` | Executed 2026-11-03 (Clang 23.1.4, C11) |
| 2 | The header compiles as C++ | `tests/abi_contract_cxx.cpp` → CTest `veltis_native_abi_contract_cxx` | Executed 2026-11-03 (Clang 23.1.4, C++20) |
| 3 | Export enumeration | `tests/export_check.c` → CTest `veltis_native_exports` | Executed |
| 4 | Unexpected-symbol rejection | same file: 2 internal bridge symbols + 11 forbidden allocator/variant names must not resolve | Executed |
| 5 | ABI mismatch rejection | fixture `veltis_native_fake_abi` (ABI 999) → `NativeLoaderTest` | Executed |
| 6 | Missing-export rejection | fixtures `veltis_native_fake_missing` (mode 3) and `veltis_native_fake_minimal` → `NativeLoaderTest` | Executed |
| 7 | Invalid-argument handling | `native_tests.cpp` + `abi_contract_c.c:test_buffer_size_validation` | Executed |
| 8 | Buffer-size validation | `abi_contract_c.c`: shorter and longer output → `LENGTH_MISMATCH` and nothing written; `SIZE_MAX` and `SIZE_MAX/2` extents rejected | Executed |
| 9 | Struct size and alignment | `abi_contract_checks.h` — **vacuous as a struct test, because the ABI exposes no aggregate.** Expressed as the primitive set's width/alignment pins plus a written rule for what happens when a struct is introduced | Executed (as layout pins) |
| 10 | Error reporting | `abi_contract_c.c:test_error_reporting_is_a_contract` (message recorded, success clears, never null) + status-code domain test + `native_tests.cpp` thread-local case | Executed |

Four more properties are worth having and exist: the version string must equal
the three components, the capability mask must contain only bits the header
defines, each returned string must fit the 255-byte limit §5 promises, and the
capability mask must not change across `init`/`shutdown`. In `abi_contract_c.c`
they live in `test_header_and_library_agree` (version string),
`test_lifecycle_contract` (both mask rules) and
`test_status_string_domain` / `test_error_reporting_is_a_contract` (the string
lengths). The mask-invariance and length checks were added during the Phase 7
review; the C++ contract unit carries the version and mask-content rules.

Test 4 is a probe list, not an enumeration: `LoadLibrary`/`dlopen` cannot list a
binary's exports portably, and the test deliberately avoids an
`objdump`/`readelf`/`dumpbin` dependency. The exhaustive form of the same rule is
the CI job that diffs the built binary's export table against the header, plus
hidden visibility and `--no-undefined` at link time.

## 13. Compatibility rules for consumers

1. Use the header. Never hand-write an export prototype.
2. Check the ABI version before calling anything else, and treat a mismatch as
   "unusable", not "retry".
3. Call `init` once per process and be prepared for every operation to return
   `ERROR_NOT_INITIALIZED` if you did not — today that set is `transform_i32` and
   `self_test`; the queries in §10 answer in any state, which is what makes rule
   2 possible.
4. Read `last_error` on the thread that failed, immediately, and never free it.
5. Supply buffers you own, aligned for `int32_t`, non-overlapping, and sized in
   elements.
6. Do not assume `ERROR_BUFFER_TOO_SMALL` cannot appear: it is reserved, and a
   future export may return it.
7. Do not assume a newer library only adds exports it can advertise. Query
   capability bits; an unknown bit means nothing to you and must be ignored —
   `NativeCapabilities.fromMask` does exactly that.
8. A negative status is the only failure signal. There is no errno, no callback,
   and no exception.
9. Never rely on the library being loaded, initialized, or even present. Java is
   authoritative, and a missing library is a normal install state.

## 14. Migration policy when the ABI must change

Version 1 will not be broken casually. If a genuine incompatibility requires
`VELTIS_NATIVE_ABI_VERSION = 2`:

1. Keep the v1 signatures documented in this file, marked superseded with the
   reason and the release that changed them.
2. Bump `ABI_VERSION`; do not silently reuse an existing export name with new
   semantics — a stale caller would bind successfully and compute wrong results.
3. Add the loader's acceptance of the new major ABI only after the Java fallback
   for the changed operation exists and is tested for equivalence.
4. Ship the header, the manifest, `export_check.c`'s list, and the contract test
   in the same commit. The CI export diff fails otherwise, which is the point.
