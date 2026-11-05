# veltis-native Platform Support

Which target this library is claimed to run on, what evidence backs each claim,
and — the part that matters most — which claims are only *configured*. Since
2026-11-05 the file has two kinds of executed evidence to keep apart: what ran on
the host described in §2, and what ran on a GitHub runner, and a runner result is
labelled as the runner's.

Companion documents: [CPU_DISPATCH.md](CPU_DISPATCH.md) for the ISA question,
`BUILD_SYSTEM.md` (Phase 6) for the presets and Gradle wiring,
`CI_SUPPORT_MATRIX.md` (Phase 10) for the runner matrix.

## 1. The five labels, and the one rule that orders them

Every row in this document carries exactly one of these:

| Label | What it means | What it does **not** mean |
| --- | --- | --- |
| **Native build and runtime tested** | Compiled, linked, then executed on that platform: CTest and the Java FFM suite ran against the artifact | The published bytes were shipped to a customer |
| **Cross-compiled and artifact verified** | Built for a foreign target on a host toolchain; the container, machine field, exports and import list were inspected | The code ever ran. A cross artifact is never executed here |
| **Build tested only** | Compiled and linked on that platform; nothing above the link step was run | Runtime behaviour of any kind |
| **Not tested** | A configuration exists in source; no build has been executed for it on any reachable machine | "Broken" — it is simply unmeasured |
| **Unsupported** | Deliberately out of scope; the Java fallback is the answer, and the loader says so | Silence |

**A label may only move upward because of a command that ran.** The date and the
command are recorded in §9. Nothing in this file is inferred from a workflow
definition, a preset, or the fact that a compiler accepts the source.

## 2. Status of the six primary targets

"Host" means the machine this repository was last verified on: Windows 11 x86-64,
GCC 16.2 (MinGW-w64 winlibs) and Clang 23.1.4 (LLVM-MinGW), no Visual Studio, no
WSL, no containers. Everything else is CI-only — and since [run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852)
that is a source of evidence rather than a placeholder, because the workflows
have now executed. They are tracked in
[CI_SUPPORT_MATRIX.md](./CI_SUPPORT_MATRIX.md); a workflow *definition* alone does
not count as evidence that a platform works, so every runner-backed row below
names the job and the run.

| Target | Toolchain | Label | Evidence |
| --- | --- | --- | --- |
| `windows-x86_64` | MinGW-w64 GCC 16.2 | **Native build and runtime tested** | CTest 5/5, `:veltis-native:test -PveltisNativeTest=true` 131 tests / 0 failures, `verifyNativeRuntimePolicy` passed (§9) — host, 2026-11-05 |
| `windows-x86_64` | LLVM-MinGW Clang 23.1.4 | **Native build and runtime tested** | same suite re-run with the Clang-built DLL, plus its import table inspected (§9); green as CI lane `Native host (Windows x86-64 LLVM-MinGW)` in run 90 |
| `windows-x86_64` | MSVC (`cl`) | **Executed and integration-tested in CI** (run 90) | `Native host (Windows x86-64 MSVC)` and `Native debug (Windows x86-64 MSVC)` both green; still not testable on this host, which has no Visual Studio |
| `windows-x86_64` | clang-cl | **Executed and integration-tested in CI** (run 90) | `Native host (Windows x86-64 clang-cl)` green |
| `windows-aarch64` | LLVM-MinGW on native Windows ARM64 | **Executed and integration-tested in CI** (run 90) | `Native host (Windows ARM64 LLVM-MinGW)` green on a `windows-11-arm` runner with Zulu 25 — the DLL was built **and run** there; no local ARM64 host |
| `windows-aarch64` | LLVM-MinGW Clang, cross from x86-64 | **Cross-compiled and artifact verified** | `Native cross-compile (Windows ARM64 LLVM-MinGW)` green; locally: machine `0xAA64`, no compiler runtime imported, 13 exports, never executed on this host |
| `linux-x86_64` | GCC, Clang | **Executed and integration-tested in CI** (run 90) | `Native host (Linux x86-64 GCC)` and `(Linux x86-64 Clang)` green — CTest, the Java suite, ELF dependency inspection, and both native-enabled and Java-only server startup |
| `linux-aarch64` | GCC | **Executed and integration-tested in CI** (run 90) | `Native host (Linux AArch64 GCC)` green on `ubuntu-24.04-arm`; `Native cross-compile (Linux AArch64 GNU)` green from x86-64 |
| `macos-aarch64` | Apple clang | **Executed and integration-tested in CI** (run 90) | `Native host (macOS Apple Silicon)` green on `macos-15` (the lane's original `macos-14` label was retired platform-wide on 2026-11-02) |
| `macos-x86_64` | Apple clang | **Executed and integration-tested in CI** (run 90) | `Native host (macOS Intel x86-64)` green on `macos-15-intel` |
| `linux-riscv64` | GNU cross | **Cross-compiled and artifact verified** | `Native cross-compile (Linux RISC-V 64 GNU)` green in run 90; still a compile-only target, never executed anywhere |
| FreeBSD, other BSDs, 32-bit x86, any other arch | — | **Unsupported** | [NativeLoader.java:57-63](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L57-L63) recognises six keys; anything else gets an explicit "unsupported platform" diagnostic and the Java fallback. `Native engine (FreeBSD investigation, non-blocking)` ran green in run 90 and reported, it does not change this row |

**Run 90** is [`.github/workflows/gradle.yml` on commit `873028c`](https://github.com/VeltisMC/Veltis/actions/runs/42006900852), a `pull_request` run started 2026-11-05T09:59:16Z: 15 native jobs green, `Package native distribution` green, `Publish native release` skipped (push/dispatch only), and one red job — `Build & verify`, which failed on the pre-existing `SchedulerTest.cancelledJobDoesNotRunBody` race and is unrelated to native code. CI evidence is a runner's word, not this host's: the rows above say which lane produced them so a reviewer can re-read the job. The host rows are the ones this machine reproduced itself.


### 2.1 Platform-conditional code, exhaustively

The isolation rule is that platform differences stop at attribute selection, not
at behaviour. Grepping `include/`, `src/c/` and `src/cpp/` for `#if`/`#ifdef`
returns **three** platform conditionals, and that is the complete list:

| Where | Condition | What it changes |
| --- | --- | --- |
| [veltis_native.h:81-89](../include/veltis/native/veltis_native.h#L81-L89) | `_WIN32`/`__CYGWIN__` else `__GNUC__`/`__clang__` | the export attribute: `__declspec(dllexport)` or `__attribute__((visibility("default")))` |
| [veltis_native.h:101](../include/veltis/native/veltis_native.h#L101) | `_WIN32 && !_WIN64 && !__CYGWIN__` | `VELTIS_NATIVE_CALL` = `__cdecl`, and only for a target that is not a release platform |
| [native_utils.c:14-17](../src/c/native_utils.c#L14-L17) | `_MSC_VER` | the thread-local storage spelling: `__declspec(thread)` or `_Thread_local` |

Everything else is `__cplusplus` / `extern "C"` guards, which select a language,
not an operating system. No source file calls an OS API, includes an OS header, or
branches on a platform for behaviour; the remaining platform knowledge lives in
[CMakeLists.txt](../CMakeLists.txt) where it belongs — link rules, symbol
visibility, reproducibility flags and the macOS deployment target. A fourth
conditional that changes what an export *does* would violate
[MODULE_BOUNDARIES.md](MODULE_BOUNDARIES.md), and this table is how that can be
checked in one grep rather than argued.

## 3. Windows

### 3.1 The runtime policy, in one sentence

A shipped `veltis_native.dll` may import **only files that ship with Windows**;
every compiler runtime is linked in statically. [CMakeLists.txt:60-67](../CMakeLists.txt#L60-L67)
sets `VELTIS_NATIVE_STATIC_RUNTIME` ON by default for MinGW and MSVC, and
[CMakeLists.txt:149-175](../CMakeLists.txt#L149-L175) carries the per-toolchain
link rules that make the promise. This is safe only because the ABI crosses no
CRT state at all — no allocator, no `FILE*`, no exception — which
[ABI_CONTRACT.md](ABI_CONTRACT.md) records as a rule; if that ever stops being
true, the static link stops being free.

### 3.2 One checker, three callers

[native/tools/check-pe-imports.ps1](../tools/check-pe-imports.ps1) is the single
authority for "what does this DLL import". It parses the PE headers itself, so it
needs no `dumpbin`, no LLVM and no Visual Studio, which is why it works on every
Windows lane including the MinGW ones. It checks four things: the COFF machine
field, MinGW/LLVM runtime imports (always rejected), MSVC runtime imports
(rejected unless `-AllowMsvcruntime` says the build chose them), and the presence
of at least one real Windows system import so a garbage file cannot pass by
importing nothing.

It is called from:

1. `:veltis-native:verifyNativeRuntimePolicy` — reads the runtime choice from
   `CMakeCache.txt`, not from the Gradle property, because the check describes
   the artifact that was linked and a property only says what was requested. It
   fails when no PowerShell is on PATH instead of skipping: a gate that passes
   quietly because its inspector is missing is not a gate.
2. The CI Windows dependency step ([gradle.yml:377-397](../../.github/workflows/gradle.yml#L377-L397))
   — same script, same flag rule, but it reads `runtime.staticRuntime` from the
   **packaged manifest** and inspects the DLL inside `dist/`. That is not
   duplication of policy; it is the same policy applied to two different files,
   one of which is what a release actually ships.
3. The CI cross-compile step ([gradle.yml:621+](../../.github/workflows/gradle.yml#L621))
   for `windows-aarch64`, where nothing can be executed.

`verifyNativeRuntimePolicy` is wired into `verifyNativeRelease`, so a release run
cannot reach the report without the import table having been checked.

### 3.3 Measured import tables

Measured on 2026-11-03 with the checker above, at `Release`, ABI 1, 13 exports.

| Build | `VELTIS_NATIVE_STATIC_RUNTIME` | Imports found | Verdict |
| --- | --- | --- | --- |
| MinGW-w64 GCC 16.2 | `ON` (`-static`) | `KERNEL32.dll`, `msvcrt.dll` | passes; no `libwinpthread`, no `libgcc_s_seh-1`, no `libstdc++-6` |
| MinGW-w64 GCC 16.2 | `OFF` | `KERNEL32.dll`, `msvcrt.dll`, `libgcc_s_seh-1.dll`, `libstdc++-6.dll` | **rejected** |
| LLVM-MinGW Clang 23.1.4 | `ON` (`-static-libgcc -static-libstdc++`) | `KERNEL32.dll`, `msvcrt.dll` | passes |
| LLVM-MinGW Clang 23.1.4 | `OFF` | `KERNEL32.dll`, `msvcrt.dll`, `libc++.dll`, `libunwind.dll` | **rejected** |
| LLVM-MinGW Clang, cross to aarch64 | `ON` (default) | `KERNEL32.dll`, `msvcrt.dll` | passes; machine `0xAA64` |

Two things that table settles rather than discusses:

- **`libwinpthread-1.dll` is gone by exactly one rule.** MinGW-w64 GCC uses the
  POSIX thread model and its static `libstdc++` asks `ld` for winpthreads from
  inside the archive, so `-static-libstdc++` alone leaves the import behind. Only
  the driver's whole-library `-static` removes it. The `OFF` row above is the
  counterfactual, kept as a measurement, not a story.
- **The Clang lane's LLVM-MinGW runtime is libc++/libunwind, not libstdc++.**
  `-static-libstdc++` does bind them statically in this toolchain (row 3 proves
  it), and the dynamic configuration imports both and is rejected. An earlier
  revision of this project's policy list knew only the GCC names; that is why
  `libatomic` is in the checker now — an aarch64 build can pull it in for 128-bit
  atomics.
- **These numbers are from the `msvcrt` flavour of both local toolchains.** They
  therefore import `msvcrt.dll`, which is why `KERNEL32.dll, msvcrt.dll` appears
  four times above. CI installs the `ucrt` LLVM-MinGW build, whose artifacts
  import `ucrtbase.dll` and the `api-ms-win-crt-*` apiset instead. The policy —
  "no compiler runtime" — is identical for both flavours; the literal import
  list is not, so a future run should record its own table rather than paste
  this one.

Nothing here establishes a **Windows version** floor. The libraries imported are
part of every supported Windows, but no run on Windows 10 LTSC or a Server Core
image has ever happened, so the manifest leaves `runtime.minimumOsVersion` empty
for Windows and `BUILD_SYSTEM.md` (Phase 6) can say why. UCRT
compatibility in particular is **not claimed**: it has not been established, and
a MinGW artifact does not even use the UCRT API set on these lanes.

### 3.4 Calling convention and exports

`VELTIS_NATIVE_CALL` expands to `__cdecl` only on 32-bit Windows and to nothing
elsewhere ([veltis_native.h](../include/veltis/native/veltis_native.h)), because
the x64 ABIs (Microsoft x64, SysV AMD64, AArch64) have one calling convention for
this kind of call. 32-bit Windows is the one place where a wrong annotation is
silent corruption, and 32-bit is not a release target anyway.

How that is verified: the FFM layer binds with
`Linker.nativeLinker().downcallHandle(address, descriptor)` and no convention
override ([NativeLoader.java:327-329](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L327-L329),
[:763](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L763)), so
every call uses the host platform's own ABI and a mismatch is a crash, not a
wrong number. All 13 exports are bound at load time and the ones the suite calls
are exercised on both verified host lanes (81 tests, 0 failures). That is a
real but partial check: it says nothing about a platform this repository has not
run, and it cannot check a 32-bit convention on a 64-bit JVM.

Exports are verified from the binary, not from the header: hidden visibility is
the default ([CMakeLists.txt:130-136](../CMakeLists.txt#L130-L136)) and
`native_export_check.exe` reads the export table of the built library, so an
accidentally public symbol fails the build rather than becoming an undocumented
support promise. Windows also needs the `PREFIX ""` normalisation
([CMakeLists.txt:220-240](../CMakeLists.txt#L220-L240)) so MinGW and MSVC both
produce `veltis_native.dll`, the one name the loader and the install docs use.

### 3.5 ARM64

Two distinct configurations, never to be conflated:

- **Cross-compile from x86-64 with LLVM-MinGW** (`windows-aarch64-mingw-cross`,
  toolchain [cmake/toolchains/windows-arm64-mingw.cmake](../cmake/toolchains/windows-arm64-mingw.cmake)).
  This is the one verified on this host. It sets `CMAKE_SYSTEM_NAME=Windows`, so
  CMake's own default generator becomes NMake — a program a MinGW lane does not
  have. `configureNative` now picks Ninja itself when no generator was named and
  Ninja is on PATH (see `effectiveNativeGenerator` in
  [build.gradle.kts](../build.gradle.kts)); CI and the presets name Ninja
  explicitly, and the failure mode of a bare `cmake` invocation with no Ninja
  visible is `'nmake' '-?' ... no such file or directory` inside `project()`,
  which names no compiler and reads like a toolchain bug.
  `VELTIS_NATIVE_BUILD_TESTS` is forced OFF for a cross build, so nothing tries to
  run the target binary.
- **Native ARM64 Windows** (CI `Windows ARM64 LLVM-MinGW` on `windows-11-arm`,
  Ninja generator, `staticRuntime: "true"`). Not tested locally. It is not
  `continue-on-error`: an ARM64 regression is expected to fail CI, per the
  comment in the matrix.

## 4. Linux

- **Runtime:** the system `libc`/`libstdc++` are imported dynamically. That is
  the normal convention on ELF and it is deliberate —
  `VELTIS_NATIVE_STATIC_RUNTIME` defaults OFF outside MinGW/MSVC
  ([CMakeLists.txt:60-67](../CMakeLists.txt#L60-L67)) — so the Windows-only
  `verifyNativeRuntimePolicy` prints "not applicable" on Linux instead of
  pretending to check something.
- **Unresolved symbols fail at link, not at `dlopen`:**
  `LINKER:--no-undefined` ([CMakeLists.txt:329-331](../CMakeLists.txt#L329-L331)).
- **Dependencies:** the CI Linux step ([gradle.yml:518-556](../../.github/workflows/gradle.yml#L518-L556))
  reads `NEEDED` with `readelf -d`, rejects anything outside
  `libc|libm|libstdc++|libgcc_s|libpthread|libdl|librt|ld-linux*`, and rejects an embedded
  `RPATH`/`RUNPATH` because Java passes an absolute path to the OS loader and an
  RPATH could only ever be a build-machine path. **Executed green on both Linux
  host lanes and the cross lanes' own inspection step in run 90** — the first time
  any of these assertions ran against a real `.so`.
- **GLIBC / GLIBCXX floors are measured, never invented.** The same step records
  `readelf --version-info` output (`GLIBC_*`, `GLIBCXX_*`, `CXXABI_*`) into the
  job summary, and that is the only legitimate source for a Linux floor. This
  host cannot produce ELF artifacts at all, so **no Linux floor is claimed
  anywhere in this repository**, the manifest keeps `minimumOsVersion` empty for
  Linux, and `verifyNativeArtifact` treats a non-empty value there as an error
  ([build.gradle.kts](../build.gradle.kts), "runtime.minimumOsVersion must stay
  empty"). Run 90 is the first real CI run, so the measuring half of that policy
  is now demonstrated; the claiming half stays closed by design — a floor is
  recorded in a job summary, and no document here transcribes a number it cannot
  read without authenticated access to those logs.

## 5. macOS

- **Deployment target.** `CMAKE_OSX_DEPLOYMENT_TARGET` is decided *before*
  `project()` ([CMakeLists.txt:19-35](../CMakeLists.txt#L19-L35)) because the
  Darwin platform modules turn it into `-mmacosx-version-min` while `project()`
  runs; assigned afterwards it reaches neither compiler nor linker. The default
  floor is 11.0, `MACOSX_DEPLOYMENT_TARGET` is honoured rather than shadowed, and
  a second check adds the flag explicitly if the compiler flags somehow lack it
  ([CMakeLists.txt:196-214](../CMakeLists.txt#L196-L214)).
- **The floor is a build-time claim, not evidence.** `verifyNativeArtifact`
  requires `minimumOsVersion` to be a real version on macOS, and the CI step
  compares it against `vtool -show-build` → `LC_BUILD_VERSION minos`
  ([gradle.yml:463-475](../../.github/workflows/gradle.yml#L463-L475)) so the
  manifest cannot drift from the linker's record. Neither check runs an older
  macOS, so this file does not claim macOS 11 execution.
- **Architectures, install names, visibility.** CI asserts `lipo -archs` equals
  the single expected slice, that every dependency lives under `/usr/lib/` or
  `/System/Library/`, and that hidden visibility is applied. The Mach-O
  `LC_UUID` is deliberately *kept*: `-no_uuid` made Apple's current linker
  refuse every test executable linking the dylib ("missing LC_UUID load
  command"), so macOS is excluded from byte reproducibility instead.
- **Universal binaries are investigated, not published.** The loader accepts a
  Mach-O file carrying the expected slice ([NativeLoader.java:603-622](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L603-L622)
  reads a fat header), `-PveltisNativeOsxArchitectures` exists, and no artifact is
  built that way in CI. One slice per platform key is the shipped model; the
  machinery to add the second is present and unused.

## 6. CPU features

See `CPU_DISPATCH.md`. Short version: no runtime detection exists, and none is
needed for correctness today, because the shipped x86-64 artifact uses SSE2 only
and the shipped aarch64 artifact uses baseline ASIMD only — measured, zero AVX
encodings and zero SVE registers. Both are mandatory in their ABI's baseline, so
"could this execute an illegal instruction on this platform's CPUs?" has the
answer *no* without a runtime probe.

## 7. Native loading

The chain a library has to pass before Java calls it, with the check that owns
each step:

| Step | Check | Where |
| --- | --- | --- |
| 1 | The platform key is one of the six recognized | [NativeLoader.java:57-63](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L57-L63) |
| 2 | The file's container and architecture match the host before `dlopen` | `probeBinary`, [NativeLoader.java:430-467](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L430-L467) |
| 3 | PE `machine`, ELF `e_machine`, Mach-O cputype/fat table parsed from the header | same, with `RandomAccessFile` so a PE's variable header position is sought, not assumed |
| 4 | `SymbolLookup.libraryLookup` succeeds and ABI 1 is answered **before** any other symbol is queried | [NativeLoader.java:322-368](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L322-L368) |
| 5 | Every one of the 13 bindings resolves; a missing symbol fails the load, not the first call | `Bindings.bind`, [NativeLoader.java:778-874](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L778-L874) |
| 6 | Version window (`major == 1`, `minor >= 0`) and capability bits | [NativeLoader.java:40-44](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L40-L44) |
| 7 | Failure at any step ⇒ Java fallback, never a failed server start | `RUNTIME_LIFECYCLE.md` §4, tested by `NativeBootstrapTest` / `NativeEndToEndTest` |

Step 2-3 exist because a wrong-architecture file otherwise fails deep inside the
OS loader with a message that names the operating system but not the reason.

## 8. Known gaps

| Gap | Status |
| --- | --- |
| No Linux, macOS, MSVC or clang-cl execution on **this host** | Host limitation, stated rather than worked around. Those lanes have since run on GitHub runners (run 90, 2026-11-05), which is a different kind of evidence: real, but not reproducible from this machine |
| No glibc/libstdc++ floor recorded **here** | The CI step records it from the built artifact (`readelf --version-info`, written to the job summary, not asserted), and that step ran green on both Linux lanes in run 90. The values are in [the run's summaries](https://github.com/VeltisMC/Veltis/actions/runs/42006900852), not copied here: this host has no authenticated access to action logs, and a floor quoted from memory is a claim, not a measurement |
| No Windows version floor / UCRT claim | Correct state; nothing was verified (§3.3) |
| No macOS < 11 execution | The floor is a build-time claim only (§5). Run 90 ran the macOS lanes on current runner images, i.e. systems *newer* than 11.0, which cannot test the floor — it can only fail to contradict it |
| Cross artifacts never executed | By design; `testNative` and `verifyNativeRelease` refuse a cross build instead of reporting a false failure. Run 90 is the first execution of the two CI guards that assert this (§4) |
| FreeBSD | Investigation job only, `continue-on-error: true`, and the loader treats it as unsupported. Green in run 90; still **Unsupported** — see `CI_SUPPORT_MATRIX.md` §12 rule 5 |
| 32-bit Windows | Not a release target; the FFM layer maps `size_t` to `JAVA_LONG`, so a 32-bit artifact would corrupt lengths instead of failing cleanly — `verifyNativeArtifact` rejects `pointerBits != 64` |

## 9. Verification index

What actually ran, on 2026-11-03, on the Windows x86-64 host described in §2:

| Command | Result |
| --- | --- |
| `./gradlew :veltis-native:verifyNativeRuntimePolicy -PveltisNativeStaticRuntime=ON` | passes; `KERNEL32.dll`, `msvcrt.dll`; machine `x86_64`; "no compiler runtime" |
| same, on a `VELTIS_NATIVE_STATIC_RUNTIME=OFF` GCC build | **fails** naming `libgcc_s_seh-1.dll`, `libstdc++-6.dll` — the winpthread-family regression guard, proven in the failing direction |
| `check-pe-imports.ps1` on a scratch LLVM-MinGW `ON` build | passes: `KERNEL32.dll`, `msvcrt.dll` only |
| same on the `OFF` LLVM-MinGW build | **fails**: `libc++.dll`, `libunwind.dll` (exit 1) |
| `check-pe-imports.ps1 -ExpectMachine aarch64` on `build/native/windows-aarch64/veltis_native.dll` | passes; machine `0xAA64`, no compiler runtime |
| `cmake` + `llvm-objdump`/`objdump` disassembly of both host artifacts and the ARM64 cross artifact | x86-64: `%xmm`, legacy SSE2 mnemonics, zero AVX/VEX/zmm; aarch64: `d`/`v` ASIMD, zero `z`/SVE (`CPU_DISPATCH.md` §4) |
| LLVM-MinGW Clang host lane: `CC=clang CXX=clang++ ./gradlew --no-daemon :veltis-native:testNative :veltis-native:verifyNativeRuntimePolicy :veltis-native:test -PveltisNativeTest=true` | CTest 4/4 passed; runtime policy passed; **81 tests, 0 failures, 0 errors, 1 skipped**. No `-PveltisNativeGenerator` was needed — the Windows/Ninja default resolved it |
| the same command with `CC=gcc CXX=g++` and the MinGW-w64 toolchain in `PATH`, after deleting `build/native/{cmake/,}windows-x86_64` | CTest 4/4 passed; runtime policy passed; **81 tests, 0 failures, 0 errors, 1 skipped**. This is the lane the working tree now holds |

The `--no-daemon` and the deleted cache are part of the method, not decoration: a
Gradle daemon keeps the environment of whoever started it, so a `CC`/`CXX` change
only reaches CMake in a fresh JVM, and a populated `CMakeCache.txt` pins the
compiler for that build directory.

Those rows are 2026-11-03 history and their counts are of that date; the current
host numbers are CTest 5/5 and 131 Java tests (§1, and `TESTING_STRATEGY.md` §2).
The platform evidence that supersedes "no runner has ever run this" is
[CI run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852)
(`873028c`, 2026-11-05): fifteen native jobs green — every Tier 1 platform built,
tested and packaged on its own runner — plus `Package native distribution` green,
`Publish native release` skipped because the run was a pull request, and
`Build & verify` red on an unrelated Java scheduler race.

`FOUNDATION_AUDIT.md` §11 remains the log of phase-by-phase executed verification;
this table is the platform-specific slice of it.
