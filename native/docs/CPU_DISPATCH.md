# veltis-native CPU Dispatch and Feature Detection

Why this module has no runtime CPU-feature detection, what was measured to
establish that, and the exact conditions under which it should be added.

Companion documents: [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) §6 for the
platform-side view, [ABI_CONTRACT.md](ABI_CONTRACT.md) for what the exports
promise, [ARCHITECTURE.md](ARCHITECTURE.md) §5 for the directory trigger rules,
`BENCHMARKING.md` (Phase 9) for the evidence a specialization has to clear.

## 1. The decision

**There is one implementation, it is the generic one, and it is correct on every
supported target.** No `cpuid`, no `getauxval`, no `__builtin_cpu_supports`, no
dispatch table, no `architecture/` directory exists in this module.

That is a decision, not an omission, and it rests on two measured facts:

1. The artifact a user gets contains no instruction outside its platform ABI's
   mandatory baseline (§3). There is therefore no unsupported instruction to
   detect the presence of. "Could this execute an illegal instruction on some CPU
   that runs this operating system?" answers *no* without a runtime probe.
2. The one compute export is a single integer loop that both host compilers
   already auto-vectorize to that baseline width (§4). A hand-written SIMD kernel
   would be competing with code the compiler produces for free, and the order
   forbids adding a native optimization without profiling evidence.

Adding detection now would be code with no consumer: a probe whose only possible
result is "take the generic path" cannot be tested in its non-trivial direction,
and a probe that is *wrong* converts a performance idea into an illegal
instruction. Both are worse than the absence.

## 2. The rules that survive the decision

These bind the next person to touch this file, and they are the reason the
decision is reversible rather than architectural:

- **Never execute an instruction the target may not have.** A specialized kernel
  is reachable only through a probe that ran on the machine executing it.
- **The generic implementation stays correct on every supported target.** It is
  the fallback for a failed or unknown probe, not a legacy path.
- **Dispatch must be invisible in the ABI.** Same arguments, same return codes,
  and — for `veltis_native_transform_i32` — *the same bits*. The contract in
  [veltis_native.h:207-214](../include/veltis/native/veltis_native.h#L207-L214)
  defines the transform modulo 2^32 so the Java fallback can reproduce it exactly;
  a kernel that differs by one bit breaks the equivalence the fallback exists to
  provide.
- **No `-march`/`-mtune=…`-style global ISA widening.** The library is compiled
  for the baseline on purpose ([CMakeLists.txt:130-136](../CMakeLists.txt#L130-L136)
  controls symbols the same way it must control the instruction set). A specialized
  kernel belongs in its own translation unit or function, with per-target
  attributes or a per-source-file compile flag, so the *rest* of the library keeps
  loading and running on an old CPU.
- **Capability bits stay "implemented by this build", not "this CPU supports X".**
  `veltis_native_capabilities()` returns `TRANSFORM_I32` and `SELF_TEST`
  ([veltis_native.h:148-155](../include/veltis/native/veltis_native.h#L148-L155)).
  A CPU-derived bit would make the value host-dependent while the header, the ABI
  version and the Java version window stayed fixed — the definition of a silent
  ABI change.
- **The manifest records the build's target triple, not the build machine's CPU
  features.** What the runner's `cpuid` said has no bearing on the operator's CPU;
  `compiler.targetTriple` and `runtime` fields are the claims this artifact can
  honestly make.

## 3. What "generic" means per target

The baseline is fixed by the platform ABI, so the compiler may use it without
asking anyone, and a generic build never needs to know which CPU it lands on:

| Target | Mandatory baseline the generic build may use | Detection question today |
| --- | --- | --- |
| `windows-x86_64`, `linux-x86_64`, `macos-x86_64` | x86-64: SSE2 (integer and float vector ops, 16-byte registers) | none — SSE2 is required by the ABI, so every CPU that can run this OS can execute the artifact |
| `windows-aarch64`, `linux-aarch64`, `macos-aarch64` | AArch64 / ARMv8.0 base: Advanced SIMD (NEON/ASIMD) and FP | none — ASIMD is in the ARMv8.0 base profile |
| `linux-riscv64` | RV64I + the mandatory integer/float subsets the toolchain targets; **no** `V` vector extension | none — the vector extension is not assumed, so nothing beyond the baseline is emitted |
| 32-bit x86, 32-bit ARM, anything else | not a release target | not applicable: the loader rejects the platform ([PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) §2) and the Java fallback runs |

Above each baseline sit the features that *would* need detection: AVX2/AVX-512
and AMX on x86-64, SVE/SVE2 and SME on AArch64, `V` on RISC-V. None of them is
emitted today (§4), and none may be emitted by the generic path ever.

## 4. Measured evidence

Everything in this table was executed on 2026-11-03 on the Windows x86-64 host
described in [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) §2. The loop in question
is [algorithms.cpp:13-29](../src/cpp/algorithms.cpp#L13-L29).

| Probe | Command | Result |
| --- | --- | --- |
| GCC 16.2 vectorization report | `g++ -std=c++20 -O3 -Iinclude -c src/cpp/algorithms.cpp -fopt-info-vec` | `loop vectorized using 16 byte vectors and unroll factor 4`, plus `loop versioned for vectorization because of possible aliasing` |
| Clang 23.1.4 vectorization report | `clang++ -std=c++20 -O3 -Iinclude -c src/cpp/algorithms.cpp -Rpass=loop-vectorize` | `vectorized loop (vectorization width: 4, interleaved count: 2)` |
| Shipped GCC artifact, disassembled | `objdump -d build/native/windows-x86_64/veltis_native.dll` | `%xmm` registers, legacy SSE2 mnemonics (`movdqa`, `paddd`, `punpcklqdq`); **0** matches for VEX/AVX forms (`vmovdqu`, `vpmulld`, `zmm`) |
| Shipped LLVM-MinGW artifact, disassembled | `llvm-objdump -d build/native/windows-x86_64/veltis_native.dll` | `%xmm` only (`movaps`, `movdqu`, `paddd`); **0** VEX-prefixed instructions, **0** `%ymm` |
| Cross-built ARM64 artifact, disassembled | `llvm-objdump -d build/native/windows-aarch64/veltis_native.dll` | `d`/`v` ASIMD registers, `ld1`/`st1`, `movi`; **0** SVE/SME forms (`z` registers, `p` predicates, `whilelo`) |

Two readings of that table matter:

- **16-byte vectors is the baseline width, not a tuning choice.** Four `int32`
  lanes is what SSE2 and ASIMD both provide, which is why the same source
  compiles to a vectorized loop on x86-64 and on ARM64 with no per-arch source.
- **Auto-vectorization is a property of this compiler at these flags, not a
  guarantee.** A future toolchain that stops vectorizing the loop is a performance
  regression, never a correctness one — the contract is the scalar arithmetic in
  §2. The benchmark harness (Phase 9) is what notices; nothing here pretends to.

The aliasing note is worth keeping visible: the contract already says `output`
must not overlap `input`, but the C++ signature carries no `restrict`, so the
compiler emits a runtime version check. Adding `__restrict__` would be an
optimization change with no profiling evidence behind it, so it is recorded here
instead of done.

## 5. The gate for a specialized kernel

A kernel beyond the baseline is accepted only when **all** of these exist. They
are ordered so the first two decide before any code is written.

1. **Profiling evidence.** A benchmark on real hardware, in
   `BENCHMARKING.md`, showing the generic path is the bottleneck for a workload
   the server actually performs — with a number, a corpus size, and the CPU it ran
   on. "SIMD would be faster" is not this.
2. **A bit-exact oracle.** The new kernel and the generic one produce identical
   output for every input, including the `count == 0`, unaligned-start and
   `> 2^31` element edges the ABI contract already pins down.
3. **Detection that can fail safe.** Per-OS probe — Windows
   `IsProcessorFeaturePresent`, glibc `getauxval(AT_HWCAP)`/`AT_HWCAP2`, macOS
   `sysctlbyname("hw.optional.arm.*")`, or the compiler's own
   `__builtin_cpu_supports`/`__builtin_cpu_first_supported` where it is
   trustworthy. Probe error, unknown CPU, or unrecognized OS ⇒ generic. Never a
   hard failure, because native unavailability must not stop the server.
4. **One-shot, thread-safe selection.** A function-local static, decided on first
   use, is the only shape consistent with
   [THREADING_MODEL.md](THREADING_MODEL.md) §2-§3: Java holds a read lock, so
   native code must assume concurrent entry, and the initialization must publish
   nothing before `veltis_native_init()` has succeeded. No global constructors:
   they run before the lifecycle exists and their order is unspecified.
5. **No per-call probe.** The selection is read, not recomputed. A `cpuid` inside
   the hot loop is a correctness-free way to lose the performance you added the
   kernel for.
6. **A test that forces each path.** A way to select the generic kernel even on a
   CPU that supports the specialized one — an env-var or property override is the
   usual answer — so the fallback is executed by CI on machines that have AVX2,
   rather than only on the hypothetical machine that does not.
7. **`veltis_native_self_test()` exercises the selected kernel.** It is the
   deterministic check Java trusts before it reports `available`; a self-test that
   only validates the generic path while the dispatch table points elsewhere is a
   lie about the artifact that is loaded.
8. **A CI runner for that ISA**, or an explicit note that the kernel is
   unverified, using the five labels in [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md)
   §1. An AVX-512 kernel built on an AVX2 runner is "Build tested only" and must
   be written that way.
9. **`architecture/x86_64/` or `architecture/arm64/` created with its first real
   file**, per [ARCHITECTURE.md](ARCHITECTURE.md) §5. A detection stub in those
   directories is not a reason to create them.

When a kernel is added, `veltis_native_transform_i32`'s signature, the ABI
version and the capability bits do not change. If a design needs them to change,
it is not a dispatch mechanism — re-read §2.

## 6. What this document deliberately does not contain

| Not here | Why |
| --- | --- |
| Detection code, a dispatch table, an `architecture/` tree | No consumer; the order forbids implementations that exist to populate directories |
| `#ifdef __AVX2__` / `__ARM_FEATURE_SVE` guards in the generic source | Preprocessor selection is compile-time; the artifact is one per platform key and cannot know the operator's CPU at build time |
| A claim that the current build is optimal | Nothing was profiled. The claim on record is "correct, baseline-only, vectorized by the compiler at these flags" |
| Per-architecture performance numbers | No benchmark has been run on this host yet — Phase 9 |
| A CPU-features field in the manifest or build-info JSON | It would describe the build machine, which is not the machine that runs the library |

## 7. Open items

- **Autovectorization coverage is not asserted by the build.** The `-fopt-info-vec`
  / `-Rpass=loop-vectorize` probes above are manual. A gate that fails a build
  because a compiler changed its vectorizer is a policy question for Phase 8
  (testing strategy), not something to add reflexively.
- **RISC-V has no local evidence at all.** The preset and the CI cross lane exist;
  the claim in §3 is the ABI's, not a measurement from this repository.
- **The 16-byte width is not a ceiling.** If a kernel ever needs
  `pmulld`-class instructions that SSE2 lacks (a multiply-heavy mix, for
  instance), it must clear §5 first — that is exactly the boundary where
  detection stops being ceremony.
