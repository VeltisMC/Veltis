# veltis-native CI Support Matrix

Which CI job runs which native check, on which runner, what each job would
prove if it went green — and, since 2026-11-05, which of them actually did:
[run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852).

Companion documents: [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) for the
per-platform evidence labels, [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for
what each command actually tests, [BUILD_SYSTEM.md](BUILD_SYSTEM.md) for the
presets and Gradle wiring, [BENCHMARKING.md](BENCHMARKING.md) for the lane CI
deliberately does not run.

## 1. The status of this document's own subject

There is exactly one workflow: [`.github/workflows/gradle.yml`](../../.github/workflows/gradle.yml)
(`VeltisMC CI`, 1 391 lines at time of writing). It triggers on push and pull
request to `main`, plus `workflow_dispatch` with a release-channel input; a
concurrency group per ref keeps one release pipeline running at a time, and the
workflow holds only `contents: read` until the publish job.

**What has been executed regarding CI:**

| Check | Result |
| --- | --- |
| `actionlint` 1.7.12 over `gradle.yml` | **exit 0** on 2026-11-04 and **exit 0** on 2026-11-05 (§10 records the expression error the second run found and fixed) |
| Runner labels checked against GitHub's hosted-runner reference (docs.github.com) | All labels used are current GA labels (§10) |
| Every Gradle/CMake command the jobs run, executed locally where a toolchain exists | See §11 — most lanes executed locally, mapped step by step |
| An actual run on GitHub-hosted runners | **Yes — [run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852)**, `pull_request`, commit `873028c`, 19 jobs, started 2026-11-05T09:59:16Z |

Run 90, job by job: **fifteen native jobs green** — all nine `native-host`
lanes (Windows x86-64 on MSVC, clang-cl and LLVM-MinGW; Windows ARM64 on
LLVM-MinGW; Linux x86-64 on GCC and Clang; Linux AArch64 on GCC; macOS Apple
Silicon; macOS Intel), all three `native-cross` lanes, both `native-debug` lanes,
and the FreeBSD investigation; plus `Package native distribution` green.
**`Build & verify` failed** on `SchedulerTest.cancelledJobDoesNotRunBody`, a
pre-existing race in the Java world scheduler that had nothing to do with the
native work; that test is now deterministic (see §11.1). **`Publish durable
release & notify website`** and **`Publish native release`** were skipped —
both are push/dispatch-only, and a pull request is not a release.

Two consequences for how this file should be read. Every Tier 1 platform now has
execution evidence of the *runner's* kind, which is why PLATFORM_SUPPORT's table
carries the lane name next to each such row; and the packaging job's two skipped
steps are the designed behaviour, not a gap. The reason for the skip is
specific: `VELTIS_NATIVE_MANIFEST_KEY` is **not configured in the repository
yet** — the private half is still only on the host that generated it. The
key-pairing step skipped instead of failing, the manifest was assembled unsigned
with `signed=false` reported and a warning recorded, and the signature upload was
skipped rather than faked. That is the first real execution of the `env`-gated
`if:` — the gate held, and it did not silently disable the steps after it. Note
what will change when the secret is added: a same-repository pull request *will*
then run the pairing check, because GitHub passes repository secrets to those; a
fork's pull request still will not. The skip in run 90 is an unconfigured secret,
not a rule that pull requests are forever unsigned. What run 90 is **not**
evidence for is the release gate: `native-release` is push/dispatch-only, its
conclusion there is *skipped*, so the step that refuses to publish an unsigned
manifest (line 1138) has still never executed anywhere. Nothing has therefore
been published, and the runtime download path still has no real release to talk
to.

## 2. Job inventory

Line ranges are for the current workflow file and are the kind of claim that
drifts; they were recomputed on 2026-11-05 by matching each number to the text
beside it, and `§12` rule 6 asks whoever edits the YAML to redo this table in the
same change.

| Job | Lines | Runners | Blocking | Native relevance |
| --- | --- | --- | --- | --- |
| `build` — Build & verify | 47–185 | `ubuntu-latest` | yes | Java-only build of every module including `:veltis-native` (fresh checkout has no native library, so its suite runs the fallback path), `buildVeltisMC`, metadata validation, distribution upload |
| `native-host` | 186–686 | 9 matrix runners (§3) | yes | The full native release checklist per host: `verifyNativeRelease`, dependency policy, Java-only suite, server bootstrap both ways |
| `native-cross` | 687–924 | 3 matrix runners (§4) | yes for 2, `continue-on-error` for RISC-V | Cross-compile + artifact inspection + the two "cannot execute" guards |
| `native-debug` | 925–969 | `windows-latest`, `ubuntu-latest` | yes | Debug-configuration `check` with CTest |
| `native-freebsd-investigation` | 970–1014 | `ubuntu-latest` + FreeBSD VM | **non-blocking** (`continue-on-error: true`, line 973) | Raw `cmake` + `ctest` inside a FreeBSD VM; portability signal only, never a support claim |
| `native-package` | 1015–1101 | `ubuntu-latest` | yes | Collects the release lanes' artifacts, optionally pairs the signing key, assembles (and signs) the distribution manifest, reports the signing state |
| `native-release` | 1102–1219 | `ubuntu-latest` | push/dispatch only | Refuses an unsigned manifest, publishes zips + manifest + `.sig` as release assets, reconciles the tag against the releases API. **Never executed** — see §1 |
| `publish` | 1220–1391 | `ubuntu-latest` | yes | Durable GitHub Release asset + website dispatch; consumes `build`'s output, never the native artifacts |

Native jobs run `fail-fast: false`, so one platform failing leaves the other
lanes reporting — a Windows-only regression must not hide whether Linux passed.

## 3. `native-host` — the nine host lanes

Each entry runs the identical core command (step at line 456, command at 475):

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

| # | Lane (line) | Runner | Platform | Toolchain | Static runtime | Where it has executed |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Windows x86-64 MSVC (192) | `windows-latest` | `windows-x86_64` | MSVC (CMake's own default generator — this lane names none, on purpose) | forced `true` (a DLL beside a JAR must not import `vcruntime140.dll`) | **Runner only** (run 90): no Visual Studio on this host |
| 2 | Windows x86-64 clang-cl (208) | `windows-latest` | `windows-x86_64` | clang-cl + Ninja (MSVC env via `ilammy/msvc-dev-cmd`) | forced `true` | **Runner only** (run 90): no clang-cl here |
| 3 | Windows x86-64 LLVM-MinGW (220) | `windows-latest` | `windows-x86_64` | LLVM-MinGW (latest release zip, downloaded at run time) + Ninja | left empty **on purpose**: proves the Windows static-by-default rather than a flag | **Both**: this host (LLVM-MinGW Clang 23.1.4, 2026-11-05) and run 90 |
| 4 | Windows ARM64 LLVM-MinGW (234) | `windows-11-arm` | `windows-aarch64` | Native ARM64-hosted LLVM-MinGW + Ninja; JDK **Zulu 25** (no vendor ships a win-aarch64 Temurin 26/25, so this lane overrides the distribution and the Gradle toolchain version) | forced `true` | **Runner only** (run 90), on a real ARM64 Windows runner — the only lane anywhere that *executes* an ARM64 artifact; not reproducible here |
| 5 | Linux x86-64 GCC (259) | `ubuntu-latest` | `linux-x86_64` | apt `gcc`/`g++` + Ninja | — | **Runner only** (run 90): no Linux host, no WSL, no container runtime |
| 6 | Linux x86-64 Clang (272) | `ubuntu-latest` | `linux-x86_64` | apt `clang` + Ninja | — | **Runner only** (run 90) |
| 7 | Linux AArch64 GCC (283) | `ubuntu-24.04-arm` | `linux-aarch64` | native apt `gcc` + Ninja | — | **Runner only** (run 90) — native ARM64 Linux lane, not a cross build |
| 8 | macOS Apple Silicon (294) | `macos-15` | `macos-aarch64` | Apple clang + Ninja, `arm64` slice | — | **Runner only** (run 90): no macOS host. Label changed from `macos-14`, see §10 |
| 9 | macOS Intel x86-64 (309) | `macos-15-intel` | `macos-x86_64` | Apple clang + Ninja, `x86_64` slice | — | **Runner only** (run 90): no macOS host |

"Runner only" is not a weaker label than *Executed* — the job ran the same
`verifyNativeRelease` checklist including CTest and the Java FFM suite, on real
hardware for that platform. It is a different kind of executed: nobody sitting at
this machine can reproduce it, re-run it against a local edit, or read its
artifacts back off disk. That is why the column names both places rather than
collapsing them into one status.

Steps after the core build, per OS:

| Step (line) | Proves |
| --- | --- |
| Dependencies, Windows (496) | The packaged DLL passes `tools/check-pe-imports.ps1` — the **same script** `verifyNativeRuntimePolicy` calls, so CI and release cannot disagree. The import check follows the *manifest's* `staticRuntime`, not the job label: a static claim with a CRT import fails, a dynamic claim is allowed and announced in the step summary |
| Dependencies, Linux (518) | `file` format; `NEEDED` allowlist (`libc`, `libm`, `libstdc++`, `libgcc_s`, `libpthread`, `libdl`, `librt`, plus glibc's own program interpreter `ld-linux*` — recorded for any object using dynamic TLS, which this module does via the thread-local last-error slot); **no `RPATH`/`RUNPATH`** (Java passes an absolute path, so an embedded build-machine path would only break installs); oldest `GLIBC`/`GLIBCXX`/`CXXABI` symbols *recorded* in the step summary as the runtime floor, not asserted |
| Dependencies, macOS (558) | `lipo -archs` is exactly one slice (no universal binary is ever published); `otool -L` allowlist (`/usr/lib/`, `/System/Library/`); `vtool` `minos` exists and **equals the manifest's `minimumOsVersion`**; the value is written to the step summary as a claim about that build only |
| Java-only fallback suite (623) | `:veltis-native:test` with `-Dveltis.native.test.expectJavaOnly=true` — every fixture detached, and the suite asserts nothing leaked into the JVM |
| Native-enabled server bootstrap (633) | `NativeBootstrapTest` with `-Dveltis.native.path=<library>`: server starts with the native engine and releases it on stop |
| Java-only server bootstrap (646) | `NativeBootstrapTest` with an empty path: server starts on the fallback — the "native never blocks startup" claim, executed |
| Upload (655) | `dist/<platform>/` plus `verification-report-<platform>.json` as a build artifact (not a release asset) |
| Upload native release artifact (669) | `if: matrix.release` — only the six release-flagged lanes feed `native-package`; a lane without the flag is verification evidence, not distribution material |

All seven OS-specific rows above ran on their matching runners in run 90; the
per-OS `if: runner.os == …` guards mean exactly one of the three dependency steps
executes per lane, which is why every `native-host` job shows several *skipped*
steps and why those skips are correct rather than a coverage gap.

## 4. `native-cross` — three cross-compile lanes

Configure and build from presets (step at line 778), then **inspect** the
artifact (line 784) — never execute it:

| Lane (line) | Runner | Preset / toolchain | Status |
| --- | --- | --- | --- |
| Windows ARM64 LLVM-MinGW (693) | `windows-latest` (x86-64 host) | `windows-arm64-mingw-cross` / `cmake/toolchains/windows-arm64-mingw.cmake` | blocking; **Executed on a runner** (run 90) — compiled + inspected there, never executed; not reproducible here (no LLVM-MinGW aarch64 cross attempt was made on this host) |
| Linux AArch64 GNU (701) | `ubuntu-latest` | `linux-aarch64-cross` / `cmake/toolchains/linux-aarch64.cmake` | blocking; **Executed on a runner** (run 90). **Cannot configure locally** — no `aarch64-linux-gnu-gcc` on this host (infrastructure absence, not a source failure) |
| Linux RISC-V 64 GNU (709) | `ubuntu-latest` | `linux-riscv64-cross` / `cmake/toolchains/linux-riscv64.cmake` | `optional: true` (line 718) → `continue-on-error` (line 720): the lane is a portability probe, not a support claim. Green in run 90, and rule 5 (§12) keeps its label **Not tested** |

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

The two "cannot execute" guards (line 883) are the lane's real subject; run 90 is
the first time either ran anywhere:

1. no `CTestTestfile.cmake` may exist in the cross build directory — CTest
   must be configured off in every cross preset;
2. `./gradlew :veltis-native:testNative …` **must fail**, and its output must
   contain `cannot run a cross-compiled build` — if it succeeds, or fails for
   any other reason, the job fails.

The artifact is uploaded (line 912) as `veltis-native-cross-<platform>`,
explicitly **inspection only**: a cross artifact is never released, never
executed, and never quoted as runtime evidence (PLATFORM_SUPPORT's
"Cross-compiled and artifact verified" label exists for exactly this).

## 5. `native-debug` — two Debug lanes

`windows-latest`/`windows-x86_64` MSVC and `ubuntu-latest`/`linux-x86_64` GCC
(lines 931–936) run the Debug `check` step at line 956:

```bash
./gradlew :veltis-native:check -PveltisNativeTest=true \
  -PveltisNativeBuildType=Debug -PveltisNativeGenerator=Ninja ...
```

Debug exists to catch assertion-sensitive defects that Release compiles away.
The build type is recorded in the packaged manifest, so a Debug artifact can
never masquerade as a release one. Both lanes are **green in run 90** — the
Windows MSVC half only there, since this host has no Visual Studio — and the
Debug *configuration* itself is **Executed locally** too (MinGW GCC lane,
CTest 5/5, 2026-11-04), which makes this the one lane where the same claim has
both kinds of evidence.

## 6. `native-freebsd-investigation` — non-blocking by construction

`ubuntu-latest` runs a raw `cmake -S . -B … -G Ninja` + `ctest` inside a
FreeBSD VM (`vmactions/freebsd-vm@v1`), with `continue-on-error: true` and a
job name that says *investigation*. FreeBSD is **Unsupported** as a release
platform (NativeLoader recognises six platform keys and none is a BSD); this
job exists to surface portability defects early. A red run is information, a
green run is **not** a support claim — the support table is the only place
that decides. Run 90's FreeBSD step is green, and PLATFORM_SUPPORT still labels
FreeBSD **Unsupported**: that green is precisely the case rule 5 (§12) was
written for.

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
guard doing its job — and passed (64 files, rebuilt from scratch) once they
were staged. Run 90 performed it on all nine host lanes, which is nine
independent from-scratch rebuilds on three OSes and two architectures.

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

Run 90 is the first instance of the left column rather than a forecast of it:
fifteen native jobs green means every row above was true of a real commit, on
nine host configurations, with the right column's caveats intact — the release
path beyond packaging did not run, and nothing in the right column became
evidence because the left column did.

## 9. Runner labels and their currency

All labels in use, verified against GitHub's hosted-runner reference on
2026-11-04 — and then confirmed the only way that settles the question, by
scheduling: every label below allocated a runner in run 90 on 2026-11-05, so
none of them is a retired or typo label. A label that does not exist fails the
lane before any step runs (§12 rule 2), which is a failure no linter can
guarantee against and a green job proves outright.

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
  **exit 0** on 2026-11-04, **exit 0 again on 2026-11-05** after the
  distribution steps and the `env`-gated key-pairing fix, and a third **exit 0**
  on 2026-11-05 after the later comment-only correction to this file's §1 (the
  packaging job's header comment claimed pull requests never see the signing
  secret; same-repository pull requests do, once the secret exists). A
  comment-only edit still gets re-linted, because the claim this section makes is
  "the file in the tree lints clean", not "the logic was touched". The 2026-11-05 run
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

## 11. Local equivalence — what actually ran here

Each CI step's closest local equivalent, executed on 2026-11-04 unless noted.
This table was written as the honest substitute for a CI run: it says which parts
of the YAML have been exercised by a human-writable command on this host. Since
[run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852) it is no
longer the only evidence available, so the rows now say which side of the
division each line falls on — this Windows x86-64 host, or a GitHub runner.
Those are different kinds of claim and §11.1 keeps them apart: a runner proves
the platform; this host proves the command is real and re-runnable.

| CI step | Local command | Status here |
| --- | --- | --- |
| `build` job, `./gradlew build` | `./gradlew build` (Java-only, no compiler needed) | **Executed** — BUILD SUCCESSFUL, 2026-11-04 (36 tasks) |
| `build` job, `buildVeltisMC` | `./gradlew buildVeltisMC` | **Executed** — BUILD SUCCESSFUL |
| `native-host` core (`verifyNativeRelease`) | `./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true -PveltisNativeGenerator=Ninja …` | **Executed end to end** (2026-11-04, repeated again 2026-11-05): configure, Release build, CTest 5/5, `verifyNativeArtifact`, `verifyNativeRuntimePolicy`, source archive of 64 tracked files rebuilt from scratch, Java suite 131 tests / 1 tolerated skip / 0 failures, report written |
| Dependencies, Windows | `./native/tools/check-pe-imports.ps1` via `verifyNativeRuntimePolicy` | **Executed** — KERNEL32.dll + msvcrt.dll, no compiler runtime |
| Dependencies, Linux / macOS | `readelf`/`lipo`/`otool`/`vtool` steps | **Not executed here** (no such host) — executed **on runners** in run 90: "Verify the native library dependencies (Linux)" green on both Linux lanes tested, "(macOS)" green on both macOS lanes |
| Java-only fallback suite | `./gradlew :veltis-native:test -Dveltis.native.test.expectJavaOnly=true` | **Executed** (2026-11-05) — 131 tests, 0 failures, 0 errors, 27 skipped |
| Server bootstrap (both ways) | `:server:test` (full suite incl. `NativeBootstrapTest`) with and without `-Dveltis.native.path` | **Executed** (2026-11-05, after the fix in §11.1) — 161 tests, 0 failures, 0 errors, 8 skipped across 25 classes; run 90's `native-host` lanes ran both server startup steps green on all nine runners |
| `native-host` step "Build, test and package" in Debug | `./gradlew :veltis-native:check -PveltisNativeTest=true -PveltisNativeBuildType=Debug …` | **Executed** — CTest 5/5 |
| `native-cross` configure/build | `cmake --preset …` | **Not executed here** — no cross compilers on this host (infrastructure absence). Run 90 executed "Configure and compile" on all three cross lanes |
| `native-cross` guards | `./gradlew :veltis-native:testNative -PveltisNativePlatform=…` must refuse with `cannot run a cross-compiled build` | **Not executed here** — a local attempt (2026-11-04) dies earlier, at `configureNative`, for lack of `aarch64-linux-gnu-gcc`, so the guard's `doFirst` is never reached. Only a runner with the cross toolchain installed gets past configure to the guard — which is exactly what run 90 was: "Verify the cross build cannot execute target binaries" is green on Linux AArch64, Windows ARM64 and the RISC-V lane, and "Verify format, machine, exports and dependencies" is green alongside it |
| `native-debug` | Debug `check` | **Executed** (MinGW lane) |
| FreeBSD investigation | FreeBSD VM | **Not executed here** — no VM tooling. Run 90's "Build and test inside a FreeBSD VM" step is green |
| `actionlint` (not a CI step — run by hand) | `actionlint -no-color .github/workflows/gradle.yml` | **Executed** — exit 0 on 2026-11-04 and again on 2026-11-05 (§10) |

### 11.1 Run 90's one red job, and what it says about this matrix

Run 90 was the first execution of this workflow on GitHub-hosted runners.
Eighteen of its nineteen jobs resolved as expected; one did not, and it was not
a native job. `Build & verify` — the Java-only `./gradlew build` row above —
failed in `:server:test` on `SchedulerTest.cancelledJobDoesNotRunBody`:

```
SchedulerTest > cancelledJobDoesNotRunBody() FAILED
    org.opentest4j.AssertionFailedError at SchedulerTest.java:113
161 tests completed, 1 failed, 8 skipped
```

The diagnosis matters for how much weight to put on the local column of the
table: the failure is a pre-existing race in the *Java* world scheduler test,
unrelated to native code, and it reproduced on a runner while the identical
suite passed here. `WorldEngine` pins two workers that begin a queued job as
soon as it is offered, so "schedule, then cancel, then assert the body never
ran" races `Worker.execute()`'s `tryStart()` gate and can only ever pass by
timing. Four earlier commits had patched production code around this symptom
without removing the race — `207563d`, `e81b7c6`, `c2785ec` (which added the
atomic start gate) and `6771cf9` — and the flake survived all four. The fix is
in the test: [SchedulerTest.java:78](../../server/src/test/java/org/veltismc/world/scheduler/SchedulerTest.java)
parks both workers on blocking jobs first, so "cancelled before start"
happens-before any possible dequeue, and releases the latch in a `finally`. The
gate was then proven load-bearing by deleting `!cancelled.get()` from
`JobHandleImpl.tryStart()` — the new test fails at once — after which the
production code was reverted byte-exactly (`git diff` empty). Six reruns of the
test and a full `:server:test` are green locally.

Two lessons recorded here, because they are the kind this matrix exists to
catch. First: a green local suite does not prove a *concurrency* test is
deterministic, so the local column can never fully substitute for the runner
column for that class of check. Second: `Build & verify` is blocking, so this
failure stopped `native-package`'s downstream release path even though all
fifteen native jobs were green — which is the intended behaviour, not a gap.


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
