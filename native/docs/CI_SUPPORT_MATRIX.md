# veltis-native CI Support Matrix

Which CI job runs which native check, on which runner, what each job would
prove if it went green — and, with equal weight, the fact that **no CI run of
this workflow has ever executed**.

Companion documents: [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) for the
per-platform evidence labels, [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for
what each command actually tests, [BUILD_SYSTEM.md](BUILD_SYSTEM.md) for the
presets and Gradle wiring, [BENCHMARKING.md](BENCHMARKING.md) for the lane CI
deliberately does not run.

## 1. The status of this document's own subject

There is exactly one workflow: [`.github/workflows/gradle.yml`](../../.github/workflows/gradle.yml)
(`VeltisMC CI`, 974 lines at time of writing). It triggers on push and pull
request to `main`, plus `workflow_dispatch` with a release-channel input; a
concurrency group per ref keeps one release pipeline running at a time, and the
workflow holds only `contents: read` until the publish job.

**What has been executed regarding CI (all on 2026-11-04, Windows 11 x86-64):**

| Check | Result |
| --- | --- |
| `actionlint` 1.7.12 over `gradle.yml` | **exit 0** on 2026-11-04 and **exit 0** on 2026-11-05 (§10 records the expression error the second run found and fixed) |
| Runner labels checked against GitHub's hosted-runner reference (docs.github.com) | All labels used are current GA labels (§10) |
| Every Gradle/CMake command the jobs run, executed locally where a toolchain exists | See §12 — most lanes executed locally, mapped step by step |
| Any actual run on GitHub-hosted runners | **Never.** No push or PR was made from this work, and no runner is reachable from this host |

So every row in this file reads *defined and lint-clean*, not *green*. The
first real run on `main` is what converts these lanes from configuration into
evidence, and until then the per-platform labels in PLATFORM_SUPPORT stay
**Not tested** no matter how convincing the YAML looks.

## 2. Job inventory

| Job | Lines | Runners | Blocking | Native relevance |
| --- | --- | --- | --- | --- |
| `build` — Build & verify | 47–169 | `ubuntu-latest` | yes | Java-only build of every module including `:veltis-native` (fresh checkout has no native library, so its suite runs the fallback path), `buildVeltisMC`, metadata validation, distribution upload |
| `native-host` | 171–497 | 9 matrix runners (§3) | yes | The full native release checklist per host: `verifyNativeRelease`, dependency policy, Java-only suite, server bootstrap both ways |
| `native-cross` | 500–731 | 3 matrix runners (§4) | yes for 2, `continue-on-error` for RISC-V | Cross-compile + artifact inspection + the two "cannot execute" guards |
| `native-debug` | 735–776 | `windows-latest`, `ubuntu-latest` | yes | Debug-configuration `check` with CTest |
| `native-freebsd-investigation` | 780–799 | `ubuntu-latest` + FreeBSD VM | **non-blocking** (`continue-on-error: true`) | Raw `cmake` + `ctest` inside a FreeBSD VM; portability signal only, never a support claim |
| `publish` | 803–974 | `ubuntu-latest` | yes | Durable GitHub Release asset + website dispatch; consumes `build`'s output, never the native artifacts |

Native jobs run `fail-fast: false`, so one platform failing leaves the other
lanes reporting — a Windows-only regression must not hide whether Linux passed.

## 3. `native-host` — the nine host lanes

Each entry runs the identical core command (line 338–355):

```bash
./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true \
  -PveltisNativePlatform=<platform> [...] --stacktrace --no-daemon
```

`verifyNativeRelease` is the whole checklist in one task: configure, build,
CTest (5 tests), `packageNativeArchive`, `verifyNativeArtifact`,
`verifyNativeRuntimePolicy`, `verifyNativeSourceArchive` (extract the source
archive and rebuild it from scratch), and the Java integration suite — plus a
`doFirst` audit that **fails on any unexplained skipped test** (only
`javaOnlySuiteHasNoNativeFixtureConfigured` is tolerated).

| # | Lane (line) | Runner | Platform | Toolchain | Static runtime | Local status of the same lane |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Windows x86-64 MSVC (177) | `windows-latest` | `windows-x86_64` | MSVC (NMake default generator) | forced `true` (a DLL beside a JAR must not import `vcruntime140.dll`) | **Not tested** — no Visual Studio on this host |
| 2 | Windows x86-64 clang-cl (188) | `windows-latest` | `windows-x86_64` | clang-cl + Ninja (MSVC env via `ilammy/msvc-dev-cmd`) | forced `true` | **Not tested** — no clang-cl here |
| 3 | Windows x86-64 LLVM-MinGW (197) | `windows-latest` | `windows-x86_64` | LLVM-MinGW (latest release zip, downloaded at run time) + Ninja | left empty **on purpose**: proves the Windows static-by-default rather than a flag | **Executed locally** (same toolchain family: LLVM-MinGW Clang 23, 2026-11-03 round) |
| 4 | Windows ARM64 LLVM-MinGW (208) | `windows-11-arm` | `windows-aarch64` | Native ARM64-hosted LLVM-MinGW + Ninja; JDK **Zulu 25** (no vendor ships a win-aarch64 Temurin 26/25, so this lane overrides the distribution and the Gradle toolchain version) | forced `true` | **Not tested locally** — the blocking CI lane runs native build, CTest and Java FFM integration tests |
| 5 | Linux x86-64 GCC (226) | `ubuntu-latest` | `linux-x86_64` | apt `gcc`/`g++` + Ninja | — | **Not tested** — no Linux host, no WSL, no container runtime |
| 6 | Linux x86-64 Clang (234) | `ubuntu-latest` | `linux-x86_64` | apt `clang` + Ninja | — | **Not tested** |
| 7 | Linux AArch64 GCC (242) | `ubuntu-24.04-arm` | `linux-aarch64` | native apt `gcc` + Ninja | — | **Not tested** — native ARM64 Linux lane, not a cross build |
| 8 | macOS Apple Silicon (250) | `macos-15` | `macos-aarch64` | Apple clang + Ninja, `arm64` slice | — | **Not tested** — no macOS host. Label changed from `macos-14`, see §10 |
| 9 | macOS Intel x86-64 (262) | `macos-15-intel` | `macos-x86_64` | Apple clang + Ninja, `x86_64` slice | — | **Not tested** — no macOS host |

Steps after the core build, per OS:

| Step (line) | Proves |
| --- | --- |
| Dependencies, Windows (381) | The packaged DLL passes `tools/check-pe-imports.ps1` — the **same script** `verifyNativeRuntimePolicy` calls, so CI and release cannot disagree. The import check follows the *manifest's* `staticRuntime`, not the job label: a static claim with a CRT import fails, a dynamic claim is allowed and announced in the step summary |
| Dependencies, Linux (403) | `file` format; `NEEDED` allowlist (`libc`, `libm`, `libstdc++`, `libgcc_s`, `libpthread`, `libdl`, `librt`, plus glibc's own program interpreter `ld-linux*` — recorded for any object using dynamic TLS, which this module does via the thread-local last-error slot); **no `RPATH`/`RUNPATH`** (Java passes an absolute path, so an embedded build-machine path would only break installs); oldest `GLIBC`/`GLIBCXX`/`CXXABI` symbols *recorded* in the step summary as the runtime floor, not asserted |
| Dependencies, macOS (443) | `lipo -archs` is exactly one slice (no universal binary is ever published); `otool -L` allowlist (`/usr/lib/`, `/System/Library/`); `vtool` `minos` exists and **equals the manifest's `minimumOsVersion`**; the value is written to the step summary as a claim about that build only |
| Java-only fallback suite (481) | `:veltis-native:test` with `-Dveltis.native.test.expectJavaOnly=true` — every fixture detached, and the suite asserts nothing leaked into the JVM |
| Native-enabled server bootstrap (491) | `NativeBootstrapTest` with `-Dveltis.native.path=<library>`: server starts with the native engine and releases it on stop |
| Java-only server bootstrap (504) | `NativeBootstrapTest` with an empty path: server starts on the fallback — the "native never blocks startup" claim, executed |
| Upload (513) | `dist/<platform>/` plus `verification-report-<platform>.json` as a build artifact (not a release asset) |

## 4. `native-cross` — three cross-compile lanes

Configure and build from presets (line 591), then **inspect** the artifact
(line 597) — never execute it:

| Lane (line) | Runner | Preset / toolchain | Status |
| --- | --- | --- | --- |
| Windows ARM64 LLVM-MinGW (534) | `windows-latest` (x86-64 host) | `windows-arm64-mingw-cross` / `cmake/toolchains/windows-arm64-mingw.cmake` | blocking; **Not executed** locally (no LLVM-MinGW aarch64 cross attempt was made in this round) |
| Linux AArch64 GNU (542) | `ubuntu-latest` | `linux-aarch64-cross` / `cmake/toolchains/linux-aarch64.cmake` | blocking; **cannot configure locally** — no `aarch64-linux-gnu-gcc` on this host (infrastructure absence, not a source failure) |
| Linux RISC-V 64 GNU (550) | `ubuntu-latest` | `linux-riscv64-cross` / `cmake/toolchains/linux-riscv64.cmake` | `optional: true` → `continue-on-error` (line 561): the lane is a portability probe, not a support claim |

What the inspection step asserts (all machine-checked):

- the artifact exists and has the target's machine field (`llvm-objdump`/
  `readelf`), so "built for ARM64" is read out of the bytes;
- the public header declares **exactly 13** `VELTIS_NATIVE_API` exports, the
  build-info JSON's export list is a **byte-for-byte match** to them, and the
  export table contains **no mangled C++ symbols** (`_Z…`/`?`);
- `pointerBits` is 64 (a 32-bit artifact would corrupt `size_t`-mapped Java
  lengths) and `abiVersion` is 1;
- `os`/`architecture`/`platform` in the build info equal the matrix target;
- dependency allowlist (`linux-*`) or `check-pe-imports.ps1 -ExpectMachine
  aarch64` (Windows ARM64).

The two "cannot execute" guards (line 693) are the lane's real subject:

1. no `CTestTestfile.cmake` may exist in the cross build directory — CTest
   must be configured off in every cross preset;
2. `./gradlew :veltis-native:testNative …` **must fail**, and its output must
   contain `cannot run a cross-compiled build` — if it succeeds, or fails for
   any other reason, the job fails.

The artifact is uploaded (line 722) as `veltis-native-cross-<platform>`,
explicitly **inspection only**: a cross artifact is never released, never
executed, and never quoted as runtime evidence (PLATFORM_SUPPORT's
"Cross-compiled and artifact verified" label exists for exactly this).

## 5. `native-debug` — two Debug lanes

`windows-latest`/`windows-x86_64` MSVC and `ubuntu-latest`/`linux-x86_64` GCC
(lines 741–746) run:

```bash
./gradlew :veltis-native:check -PveltisNativeTest=true \
  -PveltisNativeBuildType=Debug -PveltisNativeGenerator=Ninja ...
```

Debug exists to catch assertion-sensitive defects that Release compiles away.
The build type is recorded in the packaged manifest, so a Debug artifact can
never masquerade as a release one. The Windows MSVC half is
**Not tested** locally; the Debug *configuration* itself is
**Executed locally** (MinGW GCC lane, CTest 5/5, 2026-11-04).

## 6. `native-freebsd-investigation` — non-blocking by construction

`ubuntu-latest` runs a raw `cmake -S . -B … -G Ninja` + `ctest` inside a
FreeBSD VM (`vmactions/freebsd-vm@v1`), with `continue-on-error: true` and a
job name that says *investigation*. FreeBSD is **Unsupported** as a release
platform (NativeLoader recognises six platform keys and none is a BSD); this
job exists to surface portability defects early. A red run is information, a
green run is **not** a support claim — the support table is the only place
that decides. Never executed (like everything else here).

## 7. What CI deliberately does not run

| Lane | Why it is absent from CI | Local status |
| --- | --- | --- |
| Sanitizers (`-PveltisNativeSanitizers=…`) | No sanitizer job exists. Instrumented builds need a sanitizer runtime the runner matrix does not provision, and a lane that can silently go uninstrumented is worse than none (CMake's compile-and-link probe exists precisely to prevent that) | **ASan+UBSan Executed** locally: CTest 5/5 under instrumentation (2026-11-04). TSan/MSan **refused at configure** on this toolchain, by design |
| `analyzeNativeStatic` (cppcheck + `gcc -fanalyzer`) | It is a review tool, not a release gate; attaching a third-party checker's opinion to `build` would let it decide whether an artifact exists | `gcc -fanalyzer`: **Executed clean** on `native_utils.c` (GCC 16.2.0, 2026-11-04). `cppcheck`: **not executed** — absent from PATH, task fails loudly by design |
| `benchmarkNative` | Benchmarks on shared runners produce noise that looks like evidence; numbers belong to a quiet, named host | **Executed** twice locally (2026-11-04); results and verdict in BENCHMARKING.md |
| `-PveltisNativeWerror=true` lane | The CI matrix is about platforms; adding a `-Werror` axis would triple the lanes for a property that changes only with the compiler | **Executed clean** locally (2026-11-04) |
| Long-running fuzz campaigns | The bounded fuzz target (`VELTIS_FUZZ_ROUNDS=4000`) already runs inside CTest on every lane; a multi-million-round run is a developer action, not a CI cost | Bounded run **Executed** in all lanes; long campaign **Not executed** |
| Coverage measurement | No coverage tooling provisioned; there is no coverage number anywhere in this repository to report | Not executed |

Not on that list because CI *does* run it: the source-archive check
(`verifyNativeSourceArchive`, extract + rebuild from scratch) is a dependency
of `verifyNativeRelease`, so every `native-host` lane performs it nine times a
push. Locally it is **Executed**: it failed on the untracked docs — the archive
guard doing its job — and passed (54 files, rebuilt from scratch) once they
were staged.

## 8. What a green CI run would and would not prove

| A green `native-host` run proves | It does **not** prove |
| --- | --- |
| The commit configures, compiles, links and passes CTest **on that runner's OS/arch/compiler** | Anything about a different OS, arch or compiler |
| The Java FFM suite ran against that artifact with at most the one tolerated skip | Behaviour on platforms whose lanes are red or absent |
| The artifact's manifest, hashes, container, machine and export list match its bytes | That anyone executed the artifact on a *target* machine (cross lanes) |
| The Windows DLL imports only allowed system libraries under the manifest's own runtime claim | Down-level Windows hosts without the recorded CRT floor |
| The source archive rebuilds from scratch outside the working tree | Reproducibility across different toolchain *versions* (same-version byte reproducibility is a local property) |
| The server starts and stops with **and** without the native library | That native code is faster (no benchmark runs — by design) |

And a green workflow as a whole still says nothing about: sanitizers, static
analysis, benchmark numbers, FreeBSD support, or any platform whose lane is
marked `optional` or `continue-on-error`.

## 9. Runner labels and their currency

All labels in use, verified against GitHub's hosted-runner reference on
2026-11-04:

| Label | Used by | Status |
| --- | --- | --- |
| `ubuntu-latest` | `build`, Linux x86-64 lanes, `native-debug`, FreeBSD VM host, `publish` | GA |
| `ubuntu-24.04-arm` | Linux AArch64 native lane | GA (ARM64 Linux hosted runners) |
| `windows-latest` | three Windows x86-64 lanes, two cross lanes, `native-debug` | GA |
| `windows-11-arm` | Windows ARM64 LLVM-MinGW lane | GA (hosted ARM64 Windows) |
| `macos-15` | macOS Apple Silicon lane | GA arm64 label |
| `macos-15-intel` | macOS Intel lane | GA Intel label |
| `macos-14` | *(removed)* | **Retired platform-wide on 2026-11-02** — every macOS 14 image was removed from GitHub-hosted Actions two days before this document's date, so the label no longer schedules. The lane was changed to `macos-15` with an in-file comment recording why, and `PLATFORM_SUPPORT.md` was updated to match |

Two deliberate choices in that change: `macos-26`/`macos-latest` were **not**
used, because they would silently move the lane to a newer OS than the
deployment target the job records; and the retirement was found by reading
GitHub's documentation rather than by the linter that was on the host at the time.
That distinction is now narrower than it was: `actionlint` 1.7.7, run on this host
on 2026-11-05, **does** report `runner-label` findings for labels outside its
bundled image list — it flagged `windows-11-arm` and `macos-15-intel`, both of
which are current GA images. A label finding is therefore evidence about the
linter's age as much as about the workflow, and 1.7.12 accepts both labels (§10).

## 10. Lint status, and what lint cannot catch

- `actionlint` 1.7.12, `-no-color` over `.github/workflows/gradle.yml`:
  **exit 0** on 2026-11-04, and **exit 0 again on 2026-11-05** after the
  distribution steps and the `env`-gated key-pairing fix. The 2026-11-05 run
  earned its keep: before the fix it reported, at the key-pairing step,
  `context "secrets" is not allowed here. available contexts are "env", "github",
  "inputs", "job", "matrix", "needs", "runner", "steps", "strategy", "vars"` —
  i.e. the `secrets` context is unavailable in a step `if:` (and in a job `if:`),
  which is a runtime workflow-creation error, not a style opinion.
  actionlint checks syntax, shell fragments, expressions **and** action inputs;
  its runner-label check exists but validates against the image list bundled with
  the version in hand, so an unknown-label finding must be read against the
  linter's release date (§9).
- **What no linter here catches:** the build script itself. During this
  foundation round a KDoc comment containing a slash-then-star glob opened a
  *nested* Kotlin block comment (Kotlin block comments nest), which silently
  dropped every task registered below it — `tasks --all` showed ten missing
  tasks while `help` and even a deliberately broken line at end-of-file
  compiled "clean". The detector is `:veltis-native:tasks --all` (or
  `--dry-run` on a native task), which the BUILD_SYSTEM troubleshooting table
  records. No YAML linter can substitute for it.
- Workflow shell fragments are `shell: bash` with `set -euo pipefail` on the
  POSIX steps and `shell: pwsh` on Windows steps, so shellcheck (run by
  actionlint where enabled) sees them in their real shells.

## 11. Local equivalence — what actually ran

Each CI step's closest local equivalent, executed on 2026-11-04 unless noted.
This table is the honest substitute for a CI run: it says which parts of the
YAML have been exercised by a human-writable command on this host.

| CI step | Local command | Status here |
| --- | --- | --- |
| `build` job, `./gradlew build` | `./gradlew build` (Java-only, no compiler needed) | **Executed** — BUILD SUCCESSFUL, 2026-11-04 (36 tasks) |
| `build` job, `buildVeltisMC` | `./gradlew buildVeltisMC` | **Executed** — BUILD SUCCESSFUL |
| `native-host` core (`verifyNativeRelease`) | `./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true -PveltisNativeGenerator=Ninja …` | **Executed end to end** (2026-11-04, repeated runs): configure, Release build, CTest 5/5, `verifyNativeArtifact`, `verifyNativeRuntimePolicy`, source archive of 54 tracked files rebuilt from scratch, Java suite 81 tests / 1 tolerated skip / 0 failures, report written |
| Dependencies, Windows | `./native/tools/check-pe-imports.ps1` via `verifyNativeRuntimePolicy` | **Executed** — KERNEL32.dll + msvcrt.dll, no compiler runtime |
| Dependencies, Linux / macOS | `readelf`/`lipo`/`otool`/`vtool` steps | **Not tested** — no such host |
| Java-only fallback suite | `./gradlew :veltis-native:test -Dveltis.native.test.expectJavaOnly=true` | **Executed** — 81 tests, 0 failures, 25 skipped |
| Server bootstrap (both ways) | `:server:test` (full suite incl. `NativeBootstrapTest`) with and without `-Dveltis.native.path` | **Executed** — 160 tests, 0 failures, 8 skipped |
| `native-host` step "Build, test and package" in Debug | `./gradlew :veltis-native:check -PveltisNativeTest=true -PveltisNativeBuildType=Debug …` | **Executed** — CTest 5/5 |
| `native-cross` configure/build | `cmake --preset …` | **Not tested** — no cross compilers on this host (infrastructure absence) |
| `native-cross` guards | `./gradlew :veltis-native:testNative -PveltisNativePlatform=…` must refuse with `cannot run a cross-compiled build` | **Not tested** — a local attempt (2026-11-04) dies earlier, at `configureNative`, for lack of `aarch64-linux-gnu-gcc`, so the guard's `doFirst` is never reached. Only a runner that has the cross toolchain installed gets past configure to the guard, which is exactly what CI's cross lanes are |
| `native-debug` | Debug `check` | **Executed** (MinGW lane) |
| FreeBSD investigation | FreeBSD VM | **Not tested** — no VM tooling here |
| `actionlint` (not a CI step — run by hand) | `actionlint -no-color .github/workflows/gradle.yml` | **Executed** — exit 0 |

## 12. Rules for changing this matrix

1. **A new lane starts with a question**, not a platform: which defect class
   is it meant to catch that no current lane catches? If the answer is "none",
   the lane is cost without evidence.
2. **Runner labels are dated claims.** Record the label's source (GitHub's
   runner reference) and re-check it whenever a lane fails to *schedule*
   rather than to *run* — a retired label fails before any step executes, and
   no linter will say so.
3. **Never add a lane whose result can be silently absent.** That is why
   sanitizer configuration refuses at configure time, `analyzeNativeStatic`
   fails when `cppcheck` is missing, and `verifyNativeRelease` audits the JUnit
   XML for unexplained skips: an absent check must read as a failure.
4. **Cross lanes never gain an execution step.** If a runner capable of
   executing a cross artifact ever exists, the artifact stops being cross
   and the lane moves to `native-host` with tests enabled; until then the two
   guards (§4) stay.
5. **Optional means optional in both directions.** `optional: true` buys
   `continue-on-error`, and it must never be quoted as support: the RISC-V lane
   is a probe, and PLATFORM_SUPPORT's label for it stays **Not tested** even
   after a green run.
6. **Update this file in the same change as the workflow**, and update
   PLATFORM_SUPPORT when a lane's *label* changes (as with `macos-15`). The
   three files — workflow, this matrix, the support table — are one claim
   written three times; drift between them is the failure mode.
