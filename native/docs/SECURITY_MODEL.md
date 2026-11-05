# veltis-native Security Model

What this module defends against, how, and — stated with equal weight — what it
does not defend against and who is trusted.

Companion documents: [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for the checks
that back each claim, [MEMORY_MODEL.md](MEMORY_MODEL.md) for the ownership rule
that removes whole bug classes, [ABI_CONTRACT.md](ABI_CONTRACT.md) for the
boundary contract, [JAVA_FFM_INTEGRATION.md](JAVA_FFM_INTEGRATION.md) for the
Java side of the boundary, [DISTRIBUTION.md](DISTRIBUTION.md) for the network
path §1(d) describes.

## 1. Threat model in one paragraph

The native library runs **in the server's own process**, at the server's
privilege, with no sandbox. The trust boundary is therefore not "hostile code
in a shared library" — if an attacker can put a hostile `veltis_native.dll`
where the loader looks, they already have code execution and nothing here stops
them. What this module actually defends is: (a) **honest-but-broken inputs**
reaching native code — bad lengths, bad pointers, wrong alignment, hostile
integer values, truncated or foreign binaries — and turning them into a status
code instead of undefined behaviour; (b) **supply-chain shape** — a shipped
artifact must be exactly what its manifest and the public header say, or a
release check fails; (c) **availability** — a broken, missing or maliciously
misbehaving library must never take the server down with it, and repeated
failures must never become a log or CPU denial of service; (d) **the one
network-arrival path** — the optional distribution downloader is the only part
of this module that accepts bytes from a remote host, and it treats them as
hostile until the signed manifest, the archive's SHA-256, the archive's shape
and the loader's own binary probe all agree, over HTTPS to an allowlisted host
with every redirect hop re-checked ([DISTRIBUTION.md](DISTRIBUTION.md) §8).
Minecraft gameplay
data (chunk contents, block NBT, player names) is *not* currently passed to
native code: the only export is a bulk integer transform, and nothing in the
server calls it in production. That is the honest scope.

## 2. The properties that remove bug classes (verified, not aspirational)

| Property | Consequence | Where enforced / proven |
| --- | --- | --- |
| **The library allocates nothing.** No `malloc`/`free`/`new`/`mmap`/`VirtualAlloc` anywhere in `src/c` and `src/cpp` | No native heap to overflow, corrupt, double-free, or leak; no allocator state to race; no use-after-free of library-owned memory; no unbounded native memory growth from input size | Grep-verified in MEMORY_MODEL §1; `native_export_check` **fails the build** if an allocator-shaped export name (`alloc`, `free`, `release`, `last_error_free`) appears |
| **No C++ crosses the boundary.** Every export wraps its body in `try { … } catch (...)` and returns an integer status | A C++ exception (including one from the standard library) cannot unwind through Java's FFM frame — UB on every ABI — it becomes `VELTIS_NATIVE_ERROR_INTERNAL` with a thread-local message | `native_engine.cpp` (7 exported entry points, each with `catch (...)`); CTest asserts the status and the populated `last_error` |
| **No STL/C++ types in the public ABI.** Fixed-width integers, `size_t` counts, `const char*` return values from static storage | No compiler-dependent layout, no vtables, no name-mangled C++ symbol in the export table | Header compiles as pure C *and* C++20 (contract units); export check rejects mangled (`_Z…`) names in CI's cross job |
| **Caller owns every buffer; there is no free function in the ABI** | No ownership to get wrong in Java, no double-free, nothing for a JNI-style shim to release | MEMORY_MODEL; enforced by the export-name probe above |
| **Every pointer/length is validated before dereference, in a documented order** | A hostile combination cannot reach an out-of-bounds read/write; there is exactly one order to reason about and to fuzz | `native_buffer_checks.hpp` + `native_tests` argument matrix + the fuzz target (TESTING_STRATEGY §6) |
| **Integer arithmetic on sizes is overflow-checked** | `count * sizeof(T)` that would wrap `size_t` is rejected as `INVALID_ARGUMENT`, never silently truncated into a short allocation or a huge copy | `checked_byte_extent()` (compile-time-testable, unit-tested with hostile counts) |
| **Overlap is rejected, not "handled"** | Aliased input/output cannot produce a torn result or be used to smuggle a partial overwrite | `byte_ranges_overlap()` compares integer addresses (unrelated-pointer `<` is UB in C++), treats a wrapping extent as overlapping, allows merely-touching ranges |
| **The loader validates the binary container before the OS maps it** | A wrong-architecture or wrong-OS file is rejected by reading ≤4 KiB of header, so `LoadLibrary`/`dlopen` is never asked to map a file that would fault or execute foreign code | `NativeLoader.probeBinary`/`checkBinary`; loader tests for PE/ELF/Mach-O, fat headers, wrong machine, corrupted and truncated files |
| **Last error is thread-local, fixed-capacity, never freed** | No shared error buffer to race, no heap string for Java to free, no attacker-controlled lifetime | `VELTIS_THREAD_LOCAL char veltis_last_error[…]` in `native_utils.c`; isolation asserted in CTest |

## 3. Input validation at the ABI boundary

`veltis_native_transform_i32` is the only data-taking export. Its validation
order, which the fuzz target pins as observable behaviour:

1. length equality (`input_length` vs `output_length`) → `LENGTH_MISMATCH`;
2. zero length → `OK` **before any pointer is examined** (a zero-length call may
   legally receive null or garbage pointers);
3. null with non-zero length (either buffer) → `INVALID_ARGUMENT`;
4. byte extent that would overflow `size_t` → `INVALID_ARGUMENT`;
5. natural alignment for `int32_t` (misaligned → `INVALID_ARGUMENT`; a null
   pointer is aligned by definition, so check 3 has already spoken);
6. input/output range overlap → `INVALID_ARGUMENT`.

Rules the order encodes: **decide the buffer is legal before inspecting it**;
**every rejection populates `last_error` and returns before any read or
write**; **the write extent is exactly the declared output extent** — CTest
asserts sentinel bytes immediately before and after the output region are
untouched, which is the out-of-bounds-write test that matters most for a
transform.

On the Java side the same extents are re-checked against the actual
`MemorySegment` sizes before the downcall (`LoadedLibrary.requireExtent`).
That is not redundancy for its own sake: the C side receives raw pointers and
counts and *cannot* see that a segment is shorter than the length Java
declared. Java validating the real segment sizes is what keeps a caller bug
from becoming an out-of-bounds write in native memory.

Untrusted *values* (as opposed to shapes) are harmless by construction: the
transform is `x * 31 + salt + i` in unsigned 32-bit arithmetic, which is
defined for every input, so no hostile element value can cause overflow UB.

## 4. Untrusted input, honestly scoped

| Input | Trusted? | Handling |
| --- | --- | --- |
| Element values, lengths, salts passed to the ABI | **Untrusted** (any Java caller can pass anything) | §3 validation; differential equality with the Java reference |
| Candidate library file the loader is told to open | **Untrusted bytes, trusted origin decision** | ≤4 KiB header probe before mapping; format + machine must match platform and pointer width; ABI/version/capability negotiation after load; every mismatch is a clean rejection with `loaded=true, reason=…` and Java fallback |
| The library file's *provenance* | Trusted if it came from the operator's own install locations or an explicit `veltis.native.path`; **cryptographically verified** if the distribution path installed it | Manual install: no publisher check — see §7. Distribution install: the archive must match the signed release manifest's SHA-256, the manifest must verify against the embedded Ed25519 anchor, and both are checked again inside the archive before the file is moved into place ([DISTRIBUTION.md](DISTRIBUTION.md) §7). Either way, once a file is on the trusted path the loader's own probe is the last gate — see §7 item 1 |
| `server.properties` / `-Dveltis.native.*` | Operator-trusted | Type/parse validation (`NativeConfigTest`); a malformed value is rejected and logged, not thrown |
| Chunk/block/gameplay data | **Untrusted from the network, but never reaches native code today** | The chunk codec is untouched Java; no FFM call exists for it |

The loader's header probe deserves emphasis as the one place where file bytes
are parsed: bounds are explicit (`HEADER_BYTES=4096`, `e_lfanew` must point
past the DOS header and inside the file, `MAX_FAT_ARCHITECTURES=16` — Apple's
own limit, and also the reason a Java class file, which starts with the same
`CA FE BA BE`, cannot be mistaken for a fat binary with a hostile slice count),
reads are bounds-checked before slicing, and an unterminated string or
out-of-range offset is a reported failure rather than an exception escaping.

## 5. Concurrency and availability

- **No background threads exist in native code.** The library creates nothing;
  every call runs on the caller's thread. There is no thread to fail to join at
  shutdown and no work queue to flood (THREADING_MODEL).
- **Lifecycle is process-wide and idempotent**; init does not publish a
  partially initialized engine and shutdown does not invalidate state an
  operation is still using (RUNTIME_LIFECYCLE). Shutdown during in-flight calls
  is serialized on the Java side by a read/write lock: calls hold the read
  lock, `close()` takes the write lock and waits — so the FFM arena cannot be
  released under a thread that is still inside the code.
- **Failure logging cannot become a DoS.** `FailureLogThrottle` admits the
  first `DEFAULT_BURST_LIMIT = 3` occurrences immediately, then at most one
  message per `DEFAULT_INTERVAL_NANOS = 60 s`, with the suppressed count riding
  along on the next message. Suppression only ever *delays* a message — the
  engine is never silenced. Without it, a permanently failing native call on a
  server transforming chunks continuously would turn into an unreadable log
  and a real I/O cost (FailureLogThrottleTest covers burst, interval, counting
  and thread-safety).
- **Reentrancy:** no export takes a callback, so a native call cannot re-enter
  Java; closing from the same thread inside a call is therefore impossible by
  construction, not merely documented as forbidden.
- **Error state is per-thread**, so thread A's failure cannot overwrite or
  leak into thread B's diagnostics.

## 6. Supply-chain and release integrity

A shipped artifact must be exactly what its paperwork says, and every step of
that is a machine check rather than a review habit:

- the manifest's `abiVersion`, `version`, `exports`, `exportCount` are parsed
  from `veltis_native.h` at configure time — they cannot drift from the header;
- `SHA256SUMS` covers the library **and** the manifest, and
  `verifyNativeArtifact` recomputes every hash and fails on any distribution
  file that is unsigned (including a stale library from a previous build);
- the container/architecture read out of the *binary* (through the same
  `NativeLoader.probeBinary` the server uses) must equal the manifest's `os`,
  `architecture`, `platform`;
- the Windows dependency policy is a single authority
  (`tools/check-pe-imports.ps1`) used by both CI and
  `verifyNativeRuntimePolicy`, so a release run and a CI run cannot disagree:
  MinGW/LLVM compiler runtimes (`libc++.dll`, `libgcc_s_seh-1.dll`,
  `libwinpthread-1.dll`, …) are always rejected, MSVC dynamic CRT is rejected
  unless the manifest itself says `staticRuntime=false`, and an import table
  with no Windows system library at all is treated as malformed;
- the source archive is built from `git ls-files` only, so a `CMakeCache.txt`,
  a stray object file or a local toolchain path cannot enter a release, and
  `verifyNativeSourceArchive` then rebuilds the extracted copy from scratch;
- builds are byte-reproducible on Windows and ELF (link timestamps suppressed,
  sorted archive entries) — three clean builds produced identical SHA-256 — so
  "the bytes I tested are the bytes I ship" is checkable rather than assumed;
  macOS keeps its Mach-O `LC_UUID` because Apple's linker requires it in any
  dylib a consumer links against, so macOS bytes are not claimed identical
  between builds;
- `verification-report-<platform>.json` records `commit` and `treeState`
  honestly (`tree dirty` when the checkout is dirty, never a cleaned-up claim);
- the release manifest that the runtime downloads is **signed**: `packageNativeDistribution`
  writes an Ed25519 signature over the exact manifest bytes, the server carries
  one embedded public key (`NativeManifest.TRUSTED_PUBLIC_KEY_BASE64`), and
  verification is over the bytes that were parsed rather than a re-serialisation
  of them. The pairing of the deployed secret to that anchor is itself a CI
  check (`verifyNativeManifestKeyPairing`), and
  `packageNativeDistribution` reads its own output back through the runtime
  parser, so an anchor/key drift fails at packaging time instead of on someone's
  server. Key generation and rotation are documented in
  [DISTRIBUTION.md](DISTRIBUTION.md) §7.

Not present, and stated so nobody assumes them: code-signing of the
DLL/dylib/so itself (the manifest signature covers the *archive*, not an OS
publisher signature), SBOM, provenance attestation of CI artifacts in the
in-toto/SLSA sense, and any publisher verification for a library an operator
copies in by hand — that path is still filesystem-trust, see §7.

## 7. What this model explicitly does **not** protect against

1. **A hostile library file placed where the loader looks.** Loading a
   `veltis_native.dll` from `<server home>/native/…` runs that code with the
   server's full privileges. The manifest signature authenticates the
   **download**, not the file at load time: the install record beside it
   (`veltis-native-install.json`) is unsigned, so anyone who can already write
   into the install directory can write a library and a record that agree, and
   the loader will then judge that pair only by its own ABI/architecture probe.
   The operator's filesystem is therefore still the trust boundary. This is the
   standard position for a self-hosted server, and it is a *choice*, not an
   oversight.
2. **Denial of service by legitimate calls.** A caller may pass a
   100-million-element batch and the library will happily process it; there is
   no quota. Java-side callers own that decision. (The library allocates
   nothing, so the native footprint of a large batch is the caller's own
   segments plus stack.)
3. **Anything about gameplay data.** No chunk, NBT, packet or entity bytes
   reach native code today. When a future capability receives them, §3's
   discipline (validate shape, reject before dereference, differential test,
   fuzz) is the pattern to follow — but no existing test covers hostile
   *serialized* input, because there is no such path.
4. **Side channels.** The transform's timing is data-independent enough in
   practice, but no constant-time claim is made or needed: it is not operating
   on secrets.
5. **Memory safety under a sanitizer-free build on unsupported platforms.**
   ASan+UBSan have run on the host toolchain (TESTING_STRATEGY §3); TSan and
   MSan cannot run here at all, so races in the Java lock hierarchy and
   uninitialized reads visible only to MSan are *unmeasured*, not proven
   absent.
6. **Correctness of the compiler/toolchain itself**, and of the JVM's FFM
   implementation beneath it.
7. **Old Windows installs without the Universal CRT.** What remains in the
   import table after the static-runtime link is a Windows system component;
   a down-level host that lacks it is outside everything this repository has
   executed.

## 8. Security-relevant tests, mapped to claims

| Claim | Test(s), all executed on the host unless noted |
| --- | --- |
| No OOB write | CTest sentinel-region assertion around the output buffer; fuzz target's constrained lengths |
| No OOB read | Fuzz target (huge lengths only ever paired with a rejection the order guarantees happens first) |
| Integer overflow rejected | `native_tests` extent-overflow cases + unit tests of `checked_byte_extent` |
| Misalignment/overlap rejected | `native_tests` argument matrix + fuzz interaction cases |
| Exceptions never cross the ABI | `catch (...)` at every export; CTest asserts `ERROR_INTERNAL` path and populated `last_error` |
| Export surface is exactly 13, no internals | `veltis_native_exports` (runtime load), CI cross job (symbol diff against the header) |
| No allocator appears | `veltis_native_exports` name probe |
| Foreign/truncated/corrupt binary rejected before mapping | `NativeLoaderTest` (fixtures + corrupted/truncated files) |
| ABI/version mismatch refuses to load | Fixtures with ABI 999 / newer major / missing export; `NativeEndToEndTest` |
| Java-only server starts with no library | `:veltis-native:test` with `expectJavaOnly` (25 skips, 0 failures) and `NativeBootstrapTest` with empty path |
| Native failure never blocks startup | `NativeBootstrapTest` (160/0 suite) both ways; fallback-on-failure tests in `NativeEngineTest` |
| Failure logging is bounded | `FailureLogThrottleTest` (7 tests) |
| Lifecycle cannot race an in-flight call | `NativeEngineTest` close-during-calls + CTest concurrent/shutdown-while-running stress |
| Release bytes match the paperwork | `verifyNativeArtifact`, `verifyNativeRuntimePolicy`, `verifyNativeSourceArchive` |
| A downloaded manifest is authentic, not merely well-formed | `NativeManifestTest` (`oneChangedByteInvalidatesTheSignature`, `aSignatureFromAnotherKeyIsRefused`, `anEmptyOrMalformedSignatureFileIsRefused`, `theTrustedKeyOfTheRepositoryWouldRefuseAnUnsignedAttempt`), `NativeDistributionManagerTest` (`aTamperedManifestIsRefusedBeforeAnyDownload`, `aManifestSignedByAnotherKeyIsRefused`) |
| The archive that reaches disk is the archive the signature described | `anArchiveWhoseDigestDisagreesWithTheManifestIsRefused`, `anArchiveOfTheWrongSizeIsRefusedDuringDownload`, `aMissingArchiveIsAFailedDownloadNotAnInstall` |
| A hostile archive path cannot write outside the install directory | `aZipSlipEntryIsRefusedByTheAllowlistBeforeAnythingIsWritten` (refused by the three-entry allowlist, not by path normalisation) |
| The downloader cannot be steered to a new host | `anInvalidRepositoryIsRefusedBeforeAnyRequest`, `aRedirectToANonOfficialHostIsRefusedMidFlight`, `anArtifactThatWasNotVerifiedOnItsHostIsNeverInstalled` |
| Unbounded bodies are refused while streaming | `anOversizedManifestIsRefusedWhileItIsBeingRead`, `anEmptyOrOversizedManifestIsRefusedBeforeParsing`, `anArchiveOfTheWrongSizeIsRefusedDuringDownload` |
| Selective download is the only download | `eachOfTheSixPlatformsDownloadsExactlyItsOwnArchive` (per-platform request capture) and `NativeDistributionRealArtifactTest`, which publishes a **two-platform** signed manifest over loopback HTTP and asserts the runtime makes exactly three requests — manifest, signature, its own archive — then installs a library byte-identical to the artifact and runs it through FFM |
| Opting out means zero packets | `everyDisabledSwitchMakesZeroRequests`, `neverAndOfflinePerformZeroRequestsInEveryCombination`, `aPlatformOutsideTheSixIsNeverGivenARequest`, `aReleaseThatSkippedThisPlatformIsReportedNotDownloaded` |
| Two servers cannot install over each other | `aHeldLockIsReportedInsteadOfDownloadingAlongsideTheHolder` |
| An existing installation is never silently degraded | `anInstalledNewerVersionIsNeverDowngraded`, `aCorruptedInstallationIsReplacedByThePublishedArchive`, `aPublishedIdenticalLibraryIsAdoptedAndRecordedWithoutDownloading` |
| The signing secret matches the embedded anchor | `verifyNativeManifestKeyPairing` (CI step, executes only when the secret is configured) and `packageNativeDistribution`'s read-back through `NativeManifest` |

## 9. Rules for a future capability

1. Validate the whole input shape **before** the first dereference, in an
   order you can state in one sentence, and make that order observable (status
   codes) so the fuzzer can test it.
2. Use `checked_byte_extent`/`naturally_aligned`/`byte_ranges_overlap` from
   `native_buffer_checks.hpp`; do not write fresh pointer arithmetic.
3. Allocate nothing, or — if profiling ever justifies it — design ownership
   explicitly and update MEMORY_MODEL and the export-name probe before writing
   the code.
4. Catch everything at the export; return a status; set `last_error` with a
   fixed-capacity, non-allocating message.
5. Re-check extents in Java against the real `MemorySegment`s.
6. If the input can be hostile by construction (serialized game data), add a
   fuzz target for *that parser* before shipping, with seeds and constraints
   documented here.
7. Never log one line per failure; route through the throttle.
8. Do not add a signature scheme, sandbox or quota speculatively — record the
   gap in §7 instead, and add it when a real threat requires it.
