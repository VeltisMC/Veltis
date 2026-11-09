# CI support matrix

The checked-in workflow at `.github/workflows/gradle.yml` is authoritative for
the current host, cross-compilation, Java-only, packaging, and release jobs.
This document intentionally does not duplicate runner names or claim results:
workflow configuration is not evidence that a job has completed successfully.

## Lane categories and the evidence each produces

| Lane | What runs | Evidence kind | Failure policy | Publishes |
| --- | --- | --- | --- | --- |
| Native host, mandatory (`windows-x86_64` MSVC / clang-cl / LLVM-MinGW, `linux-x86_64` GCC / Clang, `linux-aarch64` GCC) | CMake build, CTest, Java integration suite, dependency and ABI checks on the runner | Runtime-tested on that host | Failure fails the workflow | LLVM-MinGW and GCC lanes marked `release: true` |
| Native host, optional (`windows-aarch64` LLVM-MinGW, `macos-aarch64`, `macos-x86_64`) | Same host build and tests on the runner, when the runner image is available | Runtime-tested on that host | Failure does not fail the workflow; the platform is recorded as skipped | Marked `release: true` |
| Native cross (`windows-aarch64` MinGW cross, `linux-aarch64` cross, `linux-riscv64` cross) | Compile plus artifact inspection; CTest is not registered and execution is explicitly refused | Compilation only — never runtime testing | Failure fails the workflow | No |
| Native Debug (`windows-x86_64` MSVC, `linux-x86_64` GCC) | Debug build and tests | Runtime-tested on that host | Failure fails the workflow | No |
| Java-only fallback | Java suite with every native fixture deliberately unset | Proves the fallback path, not the C code | Failure fails the workflow | No |
| FreeBSD investigation | Build and CTest inside a FreeBSD VM | Investigation only | Non-blocking (`continue-on-error`) | No; not a supported release platform |
| Distribution verification | Downloads the release-flagged host archives, pins the commit, verifies archive contents, metadata, ABI, checksums, and the signed manifest | Packaging and verification | A missing mandatory platform fails the gate | Hands the verified distribution to the release job |
| Native release | Uploads and re-reads the release assets | Publication | Skipped unless signing produced a signature and verification succeeded | Native release |
| Server release / website | The Java distributable, independent of native availability | Publication | Server release may proceed on Java fallback | Server release |

The three required release platforms are `windows-x86_64`, `linux-x86_64`, and
`linux-aarch64`. `macos-x86_64`, `macos-aarch64`, and `windows-aarch64` are
optional: their absence is recorded, and it neither blocks the other verified
artifacts nor turns a cross-compiled binary into a runtime-tested one.

## Interpreting results

- A successful host build and CTest run verifies that runner's native build.
- A successful cross-build verifies compilation and configured artifact checks,
  not execution on the target.
- A signed manifest records the artifacts that were packaged and verified.
- Native release publication is skipped unless the signing step produced a
  signature.

Platforms the loader does not recognize at all are listed in
[PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md): they are unsupported at runtime and
keep the Java implementation.

No CI run or release publication was performed as part of the current code
changes.
