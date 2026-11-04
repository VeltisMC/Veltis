# veltis-native Benchmarking

How native performance against Java is measured in this module, what the
current numbers actually say (including the ones that say "native loses"), and
the acceptance bar every future optimization has to clear before it ships.

Companion documents: [CPU_DISPATCH.md](CPU_DISPATCH.md) for when a
specialization is allowed to exist, [JAVA_FFM_INTEGRATION.md](JAVA_FFM_INTEGRATION.md)
for the call shape being measured, [TESTING_STRATEGY.md](TESTING_STRATEGY.md)
for correctness (a benchmark is not a test), [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md)
for which hosts have produced any number at all.

## 1. The rules

1. **A benchmark is a measurement tool, never a gate.** Nothing in `check`,
   `build` or `verifyNativeRelease` runs it, and no test asserts a timing. It
   fails only when a result is *wrong* — every implementation's output is
   verified against `javaTransform` before it is timed, so a broken
   implementation produces a failure, not a fast number.
2. **No speed-up claim from one short run.** Numbers are single-host,
   non-JMH, reproducible in shape and not in the third digit. They are quoted
   with their host, date and configuration or not at all.
3. **FFM overhead and copying are inside the measurement.** The production
   shape (`native-engine`) includes arena open, copy in, downcall, copy out and
   arena close, because that is what a server call would pay. A number that
   excludes the copies is labeled as a different shape (`native-reusable`), not
   presented as "the native path".
4. **Nothing is optimized before it is profiled.** The benchmark exists so the
   *next* capability can be justified with evidence; `transform_i32` is a test
   workload, not a claimed optimization (§6).

## 2. The four implementations

Run with `./gradlew :veltis-native:benchmarkNative` (builds the library first;
refuses to run against a cross-compiled one).

| Name | What it measures |
| --- | --- |
| `java` | `NativeEngine.javaTransform` — allocates the result array, exactly like the server's fallback. This is the honest baseline |
| `java-optimized` | The same arithmetic writing into a caller-supplied array, zero allocation — the fastest shape pure Java can use |
| `native-engine` | The production integration path: confined arena, copy in, FFM downcall, copy out, arena close — 8 bytes/element allocated per call |
| `native-reusable` | One shared arena with pre-allocated segments per batch size: only compute and the two mandatory copies are measured. **This shape is not wired into the server** |

Deterministic inputs (xorshift-seeded `int[]`, salt `-987654321`), batch sizes
1 → 1,048,576 (11 sizes), warmup = max(16, iterations/4), then 3 rounds of
timed calls. Percentiles are over individual calls. Columns: total ms, avg ns,
p50/p95/p99 ns, M elements/s, K calls/s, heap bytes per call (from
`ThreadMXBean.getCurrentThreadAllocatedBytes`).

## 3. Current results (all measured, one host)

**Host:** Windows 11 x86-64, 20 logical processors, JDK 25.0.5.0.3 (HotSpot
64-bit), library built Release by MinGW-w64 GCC 16.2.0 (the
`verifyNativeRelease` lane). **Date: 2026-11-04.** Timer overhead measured in
the same run: **36.2 ns per `System.nanoTime` call**, two per sample — so any
p50 below ~100 ns is timer quantization, not precision (the harness prints 0 or
100 ns there and says so).

M elements/s, higher is better; bold = best in row. One full run is tabled
below; the cross-run comparison against the other run captured this round
follows it:

| Batch | `java` | `java-optimized` | `native-engine` | `native-reusable` |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 30.7 | 17.4 | 4.0 | 12.0 |
| 16 | 469.9 | 644.7 | 74.1 | 189.6 |
| 256 | 1,748.8 | 1,800.2 | 840.1 | **2,077.1** |
| 1,024 | 1,394.6 | 2,043.6 | 1,176.2 | **3,975.7** |
| 4,096 | 871.2 | 2,153.3 | 813.4 | **3,131.7** |
| 65,536 | 1,411.4 | 2,065.8 | 1,273.4 | **3,459.7** |
| 262,144 | 1,323.3 | 1,991.6 | 412.7 | **2,077.6** |
| 1,048,576 | 1,077.7 | **1,956.8** | 377.8 | 1,447.8 |

Cross-run check (a second run on the same host earlier the same day, rows
both runs share): batch 16 — `java` 469.9 vs 469.9, `java-optimized` 640.9 vs
644.7, `native-engine` 72.2 vs 74.1, `native-reusable` 184.7 vs 189.6; batch
64 — `java` 831.0 vs 800.1, `java-optimized` 1,371.8 vs 1,408.1,
`native-engine` 243.5 vs 243.4. The quoted rows therefore move by at most ~4%
between runs here, and no ordering changes. The second run was not captured in
full, so no second complete table exists — and none is claimed: the largest
sizes (120 calls, hundreds of milliseconds each) are where GC, JIT and
scheduler state have the most room to move, which is why a single-run
difference at 1 MiB is reported as a direction, not a ratio.

In the same runs:

- **Cheap FFM downcall** (`veltis_native_capabilities`, no buffers): **76.4–76.8
  ns per call** (earlier round: 41.6 ns). The downcall itself is tens of
  nanoseconds; it is not the wall.
- **The harness's own verdict line:** `threshold: native-engine did not beat
  the allocation-free Java baseline at any measured batch size` — printed by
  the harness, not typed here.
- Heap: 9.1 MiB → 314.2 MiB across the run; measured thread allocation
  2,762.5 MiB; committed virtual memory 898.5 → 925.6 MiB.
- Per-call heap: `java-optimized` and `native-reusable` 0–6 B/call;
  `native-engine` ~8 B/element; `java` allocates the result array.

### What the numbers say

- **The production native path (`native-engine`) never wins.** Not at any batch
  size, on this host — the harness's own verdict line says so for the captured
  run, and the earlier run's visible rows agree. The two mandatory copies plus
  per-call arena work dominate: at 1 MiB it is ~2.9× slower than the plain
  `java` baseline and ~5× slower than `java-optimized`.
- **The reusable shape wins the middle** (roughly 256 → 262,144 elements,
  peaking near 1,024–65,536 at ~1.4–2× over `java-optimized`) and loses at
  1 MiB, where a tight Java loop streams through cache better than a
  copy-heavy native call. The crossover is real; the 262,144 row is within
  5% of `java-optimized` ("roughly even") and the 1 MiB row is "Java wins".
- **Small batches are noise-dominated** (timer quantum 100 ns vs ~20–60 ns
  operations). Only orders of magnitude are readable there.
- **Allocation, not call overhead, is the story**: the shapes that allocate
  nothing (`java-optimized`, `native-reusable`) are the ones that can win; the
  production path allocates 8 bytes/element per call and never wins.

This is consistent with the previous round's conclusion, which is the point of
re-running: the direction of the result reproduced. The magnitudes did not all
carry over — that older table was an LLVM-MinGW/Clang build and this one is
GCC: across the rows both tables share, `java-optimized` moved 0–4% and `java`
0–9%, while `native-engine` moved up to 18% (batch 1,024: 1,428.2 then vs
1,176.2 now) and `native-reusable` up to 29% (batch 256: 2,919.2 then vs
2,077.1 now; at 1 MiB, 1,829.9 vs 1,447.8). Same host, different compiler: treat the
cross-toolchain difference as real and unexplained, not as noise to be
averaged away.

## 4. Reproducibility

- Same command, same host, same build lane: the shape reproduces. The two runs
  captured today differ by at most ~4% at the mid sizes (§3's cross-run check);
  only one run was captured at the largest sizes, so no run-to-run claim is
  made there — GC/JIT/scheduler state, none of which this harness controls.
- Input, salt, sizes, round count and warmup are fixed constants, so a
  difference between two runs is the machine, not the corpus.
- The harness prints the JVM, OS, processor count, library build (ABI +
  capabilities), timer overhead and date context with every run — a number
  without that header is not evidence.
- There is **no JMH**. JIT compilation per implementation, CPU frequency and
  OS scheduling are uncontrolled. Adding JMH is a considered trade (it would
  add a dependency and constrain the FFM call shapes); until a claim depends on
  the third digit, the current harness answers the questions actually asked.
- **No Linux, macOS or ARM64 numbers exist.** CI's host jobs build and test
  there but do not run the benchmark. Every number in this file is Windows
  x86-64 only.

## 5. The optimization acceptance bar

Every future native optimization must satisfy **all** of these before it is
merged, in order — and this order is the checklist a reviewer should use:

1. **A demonstrated Java hotspot** from a real server profile (JFR or
   equivalent), with the sample counts recorded — not a guessed "native would
   be faster".
2. **A Java reference implementation** that is correct and is what the profile
   actually ran (or a corrected Java version — see §6: the first win here was
   going to be a Java fix, not native code).
3. **Correctness and differential tests**: native output ≡ Java reference over
   boundary values, in CTest *and* the Java suite, plus a fuzz target for the
   argument surface if the new export takes pointers and lengths
   (TESTING_STRATEGY §6, §10).
4. **A baseline benchmark run before the change**, on the host that will
   judge it, with the §3 header recorded.
5. **The production call shape measured end-to-end** — arena/copies/FFM
   overhead included — with at least one reusable-shape number beside it if
   that is the proposed integration.
6. **An improvement on the actual workload at the actual batch sizes** the
   profile showed, not at a size chosen afterwards. Reproduced across at least
   two runs; a single run is a smoke signal, not evidence.
7. **A generic fallback** that stays on the hot path's quality bar or is
   explicitly documented as slower; the Java path must remain correct and
   available for every platform in the support table.
8. **No regression elsewhere**: the same benchmark before/after at every batch
   size, plus the server suite green.
9. **Supported-platform correctness preserved** — a specialization is only ever
   reached after runtime feature detection with a tested generic path
   (CPU_DISPATCH); never assume the build host's ISA.
10. **Documented**: this file updated with the numbers, their host and date,
    and the decision. A benchmark that is not written down did not happen.

A benchmark improvement never justifies sacrificing correctness or stability
(non-negotiable rule 12 of the project), and native code is never added merely
because the module exists (rule 13).

## 6. Profiled evidence so far (the honest candidate list)

An existing workload was profiled rather than guessed at
(`EngineResourceBenchmark`, 64 chunks, "resource" config, 2 workers, 1-CPU
JVM): **278.5 chunks/s, 201 MiB peak, 98 MiB retained**, JFR 163 execution
samples:

- **~47% in `ChunkDataCodecImpl.encode`** — the chunk save path;
- ~a fifth in `LightJob` (`computeSkyLight`, `sectionAt`, `Chunk.section`);
- enclosing frames: `ChunkSaveJob`, scheduler worker loop.

A temporary harness compared the real codec with an equivalent `ByteBuffer`
implementation of the same format: **encode 1,771 → 70 µs (~25×), decode 1,626
→ 66 µs (~25×), byte-identical output.**

**Decision, recorded here so it is not re-litigated: no native workload was
integrated.** The profiled hotspot is a Java serialization loop that a native
version would drive with one FFM call per block — the exact per-element call
pattern the project forbids (non-negotiable rule 14) — and the measured fix
was Java-side anyway. Candidate areas for *future* profiling, in the order the
profile suggests and with no commitment implied: the chunk codec (Java fix
first), sky-light computation, block-data transforms, entity bulk math,
collision work. Each must re-earn §5 from a fresh profile.

## 7. Running it

```bash
./gradlew :veltis-native:benchmarkNative          # builds the library first
```

Optional properties are the usual native ones (`-PveltisNativeGenerator=Ninja`,
`-PveltisNativeBuildType=Debug` for an instrumented-but-slow build — numbers
from a Debug build are **not** comparable to Release ones and the harness does
not label them, so do not mix them). The task refuses to run against a
cross-compiled artifact (`cannot run a benchmark on a library this JVM cannot
load` in effect) because a Java benchmark against a foreign DLL is just a Java
microbenchmark.

There is no CI benchmark job: benchmarks on shared runners produce noise that
looks like evidence. Run them on a quiet machine and record the host.

## 8. What is deliberately absent

- JMH, profilers in-process, continuous benchmarking, regression thresholds —
  all deferred until a claim needs them (§4).
- Multi-host aggregates — no second host has produced a number.
- Allocation profiling per implementation beyond `ThreadMXBean`'s byte count —
  no object-level breakdown, because no optimization is currently waiting on
  one.
- Any benchmark of a Minecraft workload on a native path — no such path exists
  (§6).
