# `veltis-native`

The optional native engine for VeltisMC: a small C/C++ shared library with a
stable C ABI, plus the Java Foreign Function & Memory (FFM) integration the
server uses to call it.

The native library is **never required**. The Java classes in this module
compile with every ordinary `./gradlew build`, `:server` depends only on those
classes, and the server starts and runs on the Java fallback when no compatible
library is installed. CMake and a C/C++ compiler are needed only for the
`native` tasks below.

## Layout

| Path | Role |
| --- | --- |
| `include/veltis/native/veltis_native.h` | The public C ABI. Every exported symbol is declared here. |
| `include/veltis/native/veltis_native.hpp` | Internal C++ surface. **Not** part of the ABI. |
| `src/c/native_utils.c` | C-only pieces: versions, status strings, thread-local last error. |
| `src/c/native_internal.h` | Bridge between the C utility layer and the C++ engine, deliberately unexported. |
| `src/cpp/algorithms.cpp` | The deterministic bulk transform. |
| `src/cpp/native_engine.cpp` | Engine state and the exported C ABI entry points. |
| `src/main/java/org/veltismc/nativeengine/` | FFM loader, configuration, status, lifecycle, Java fallback. |
| `src/test/java/` | Java unit tests and the real-library end-to-end test. |
| `tests/` | Native C++ tests and the incompatible-library fixtures. |

## Architecture

```
               server bootstrap
                      |
               NativeEngine (Java)  -----> Java fallback (identical output)
                      |
               NativeLoader (FFM)
                      |
        veltis_native_transform_i32, veltis_native_init, ...
                      |
             native_engine.cpp (C++)
                      |
               algorithms.cpp
```

Rules the module keeps:

- **One ABI.** Only the functions declared in `veltis_native.h` are exported.
  CMake sets hidden symbol visibility, and the C++ engine class is not exported.
- **No C++ across the boundary.** No exceptions, STL types, references or
  ownership transfer cross an exported function. Every entry point catches
  exceptions and reports an integer status.
- **No allocator contract.** The caller owns every buffer. The library only
  writes the exact output extent it is given.
- **Thread-safe, process-wide lifecycle.** `veltis_native_init()` and
  `veltis_native_shutdown()` are idempotent. The last-error string is
  thread-local and valid until the next call on that thread.
- **Additive versioning.** `VELTIS_NATIVE_ABI_VERSION` changes only for a
  binary-incompatible change. The Java side requires an exact ABI, engine major
  `1`, and accepts an equal or newer minor.

### The implemented operation

`veltis_native_transform_i32` is a real, deterministic bulk transform:

```
output[i] = (uint32_t)input[i] * 31u + (uint32_t)salt + i    (mod 2^32)
```

The unsigned arithmetic makes overflow defined. `NativeEngine.javaTransform`
uses Java's two's-complement `int` arithmetic, which produces the same bits, so
the end-to-end test can assert native and fallback results are identical for
every input, including `Integer.MIN_VALUE` and `Integer.MAX_VALUE`.

Capabilities are reported by the library, not assumed by Java:

| Capability | Bit |
| --- | --- |
| `VELTIS_NATIVE_CAPABILITY_TRANSFORM_I32` | `1 << 0` |
| `VELTIS_NATIVE_CAPABILITY_SELF_TEST` | `1 << 1` |

## Prerequisites

For the Java side, nothing beyond the repository's normal JDK requirement.

For the native side:

- CMake 3.20 or newer (CTest ships with it)
- A C11/C++20 compiler:
  - Windows x86-64: Visual Studio 2022 (MSVC) or LLVM/MinGW
  - Linux x86-64: GCC or Clang
- A generator: Ninja is recommended; Makefiles and Visual Studio work too

If a tool is not on `PATH`, name it explicitly:

```bash
./gradlew :veltis-native:buildNative -PveltisNativeCmake=/path/to/cmake
```

The corresponding environment variables `CMAKE` and `CTEST` are also accepted.
Only the native tasks look for these tools; configuration and every Java task
do not.

## Building and testing

```bash
# Configure, compile and link; output lands in build/native/<platform>/
./gradlew :veltis-native:buildNative

# Run the C++ tests through CTest, plus the incompatible-library fixtures
./gradlew :veltis-native:testNative

# Run Java unit tests and, when a library exists, the real end-to-end test
./gradlew :veltis-native:test

# Do all of it, in the right order
./gradlew :veltis-native:check -PveltisNativeTest=true

# Compare the Java fallback, an allocation-free Java baseline and the native
# engine across batch sizes (measurement tool, not a test)
./gradlew :veltis-native:benchmarkNative

# Remove this module's native build tree
./gradlew :veltis-native:cleanNative
```

`buildNative` is deliberately **not** wired into the normal `build` graph.
`-PveltisNativeTest=true` is the opt-in that makes the Java test task build the
native library first and adds `testNative` to `check`. Without it, the
end-to-end test skips when no library has been built, so Java-only CI keeps
working with no compiler installed.

Native artifacts:

```
veltis-native/build/native/<platform-key>/veltis_native.dll      (Windows)
veltis-native/build/native/<platform-key>/libveltis_native.so    (Linux)
veltis-native/build/native/<platform-key>/test-fixtures/         (incompatible fixtures)
```

`<platform-key>` is `windows-x86_64` or `linux-x86_64` (see below).

### Manual CMake

The Gradle tasks are a thin wrapper; a direct CMake build is equivalent:

```bash
cmake -S veltis-native -B veltis-native/build/native/cmake/manual \
  -DCMAKE_BUILD_TYPE=Release \
  -DVELTIS_NATIVE_OUTPUT_DIR="$PWD/veltis-native/build/native/linux-x86_64"
cmake --build veltis-native/build/native/cmake/manual --parallel
ctest --test-dir veltis-native/build/native/cmake/manual --output-on-failure
```

`CMakePresets.json` also defines `windows-x86_64` and `linux-x86_64` presets:

```bash
cmake --preset linux-x86_64
cmake --build --preset linux-x86_64
ctest --preset linux-x86_64
```

## Supported platforms

| Platform key | Status |
| --- | --- |
| `windows-x86_64` | **Built and tested on Windows 11 x86-64** with LLVM-MinGW and Ninja: CTest, the 52 Java unit/integration tests and the benchmark all ran there. |
| `linux-x86_64` | **Buildable, not yet verified here.** CMake, CTest and the Java integration tests are written for it and run in the `native-linux` CI job. Do not describe it as supported until that job has passed on the commit in question. |
| `windows-aarch64`, `linux-aarch64`, `macos-*` | Recognized by the loader but **not built or tested** by this repository. |

The loader recognizes the other keys so a future build can ship without a Java
change, not because they are verified. Do not claim support for a platform on
this table until `buildNative` and `testNative` have actually run there.

### What "tested" covers

The Windows run covers:

- the C++ test executable through CTest (argument validation, boundary values,
  zero-length calls, 1 MiB transforms, concurrent calls, thread-local errors);
- the Java loader against deliberately broken fixtures: wrong ABI, newer major,
  missing transform export, failing initialization, failing transform, a
  minimal library with no version symbols, a corrupted file and a truncated
  file;
- the real library through `NativeEngine`: repeated init/shutdown cycles,
  post-shutdown fallback, 1 MiB batches, 8 concurrent callers and shutdown
  while calls are in flight;
- the Java-only fallback with every native path absent.

User-visible status is honest about the distinction: a library that was never
found reports `loaded=false`; one that was found and rejected reports
`loaded=true` with the reason, because those are different operator problems.

## Implementation status

Implemented and exercised:

- the ABI and version/status diagnostics in `veltis_native.h`;
- the process-wide `init`/`shutdown` lifecycle and capability query;
- `veltis_native_transform_i32`, with an exactly equivalent Java fallback;
- the FFM loader, configuration overlay, status reporting and fallback policy;
- startup integration in `VeltisBootstrap` and release on server stop;
- the CTest suite, Java unit/integration suite and the benchmark harness.

Deliberately **not** implemented:

- any operation other than the bulk `transform_i32` primitive;
- compression, chunk encoding, lighting, entity or scheduler acceleration;
- automatic installation or download of a native library by the launcher;
- a JNI compatibility layer (the integration is FFM-only, JDK 22+).


## Installing a library

The loader looks for `<platform>/<library>` in this order:

1. `veltis.native.path`, when set — either the shared library file itself or a
   directory containing it.
2. `<server home>/native/<platform-key>/<library>`
3. `<working directory>/native/<platform-key>/<library>`
4. `<working directory>/build/native/<platform-key>/<library>`

For example, on Linux:

```bash
mkdir -p /srv/veltis/native/linux-x86_64
cp veltis-native/build/native/linux-x86_64/libveltis_native.so \
   /srv/veltis/native/linux-x86_64/
```

On Windows, copy `veltis_native.dll` to
`<server home>\native\windows-x86_64\`.

The launcher jar does **not** package a native library. An operator chooses
whether to install one, and the version/ABI check in the loader is what decides
whether it can be used.

## Configuration

Settings are read from `<home>/server.properties` and overlaid with
`-Dveltis.native.*` system properties, so a permanent value and a one-run
override use the same names:

```properties
# Master switch. false means no library is looked for at all.
veltis.native.enabled=true
# Load during startup. false leaves the engine disabled until NativeEngine.load().
veltis.native.autoload=true
# Use the Java implementation when native support is unavailable or fails.
veltis.native.fallback=true
# Cheap invocation counters for diagnostics.
veltis.native.stats=false
# Optional file or directory. Empty means use the search order above.
veltis.native.path=
```

On Linux, `-D` overrides look like:

```bash
java -Dveltis.native.path=/srv/veltis/native/linux-x86_64/libveltis_native.so -jar veltis.jar
```

## Behaviour and fallback

- **Disabled:** `enabled=false` reports `NativeStatus.disabled` and every
  operation uses Java.
- **Missing:** no library in any location reports `loaded=false` and Java is
  used. This is normal on a fresh install and is logged at `INFO`, not as an
  error.
- **Found but incompatible:** an ABI mismatch, an engine major mismatch, a
  missing export or an initialization failure reports `loaded=true` with a
  reason. The loader closes the library, logs a warning during startup, and
  leaves the server on Java.
- **Fallback disabled:** with `veltis.native.fallback=false`, an operation that
  cannot run natively throws `IllegalStateException` instead of silently
  switching implementations. Use this when an operator needs to prove native
  execution.
- **Repeated lifecycle:** `initialize`/`shutdown` are idempotent and safe to call
  in any order. A failed load never leaves a half-open library behind.

The server bootstrap reads this configuration during startup, logs a single
status line, and calls `shutdown` during server stop. Nothing in the startup
path fails the server if native support is unavailable.

## Benchmark methodology and results

`NativeBenchmark` is a measurement tool, not a test. It runs through
`:veltis-native:benchmarkNative`, which builds the native library first and then
executes in a normal JVM:

```bash
./gradlew :veltis-native:benchmarkNative
```

It compares four implementations on deterministic inputs (an xorshift-seeded
`int[]`, salt `-987654321`):

| Name | What it measures |
| --- | --- |
| `java` | `NativeEngine.javaTransform`: allocates the result array, exactly like the server's fallback. |
| `java-optimized` | The same arithmetic writing into a caller-supplied array: the fastest shape pure Java can use. |
| `native-engine` | The production integration path: confined arena, copy in, downcall, copy out. |
| `native-reusable` | One shared arena with pre-allocated segments, so only compute and the two mandatory copies are measured. |

Each batch size is warmed up, then measured for three rounds of timed calls.
Every result is checked against `javaTransform` before it is timed, so a wrong
implementation fails instead of producing a fast number. Percentiles are over
individual calls and therefore include timer overhead, which the harness
measures and prints separately (34.6 ns per `System.nanoTime` call on the
reference host; two calls per sample). Heap allocation comes from
`ThreadMXBean.getCurrentThreadAllocatedBytes`; heap use from `MemoryMXBean`;
committed virtual memory from the operating-system MXBean. Sizes run from 1 to
1,048,576 elements.

Reference run (Windows 11 x86-64, 20 logical processors, JDK 25.0.5.0.3,
HotSpot 64-bit, library built with LLVM-MinGW/Clang 23 in Release):

| Batch | `java` | `java-optimized` | `native-engine` | `native-reusable` |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 31.9 | 17.8 | 4.6 | 16.0 |
| 16 | 483.2 | 665.3 | 86.3 | 254.2 |
| 256 | 1756.2 | 1872.9 | 922.4 | **2919.2** |
| 1,024 | 1508.5 | 2094.0 | 1428.2 | **5487.1** |
| 4,096 | 875.6 | 2185.3 | 879.1 | **3639.9** |
| 65,536 | 1294.9 | 2076.9 | 1394.1 | **4464.8** |
| 262,144 | 1330.5 | 2067.2 | 408.1 | 1972.9 |
| 1,048,576 | 1139.9 | **1988.7** | 373.0 | 1829.9 |

M elements/s, higher is better. Bold marks the best row value.

What the numbers say:

- The **production native path never beat the allocation-free Java baseline at
  any batch size**, so the harness reports "threshold: not measured" rather than
  inventing one. At 1,024 elements the two are close; beyond that the copies and
  per-call arena work dominate.
- The **reusable native path** wins from roughly 256 to 65,536 elements, then
  loses again at 262,144 and above, where a tight Java loop streams through
  cache better than a copy-heavy native call. That is the only shape worth
  considering for a future integration: caller-owned, reused segments and a
  batch in the hundreds-to-tens-of-thousands range.
- The FFM transition itself is cheap: a trivial downcall
  (`veltis_native_capabilities`) measured **41.6 ns**. The cost is the two
  memory copies and the per-call allocation, not the call.
- `java-optimized` allocations were zero by construction; `native-engine`
  allocated about 8 bytes per element for its two segments plus the result
  array. In total the run allocated 2,762.8 MiB across all measured calls, used
  about 314 MiB of heap, and moved committed virtual memory from 898 MiB to
  919 MiB.
- Small-batch p50 values are quantized to 0/100 ns by the timer's resolution.
  These rows are only useful for orders of magnitude, and are not a claim of
  sub-100 ns precision.

These are single-host, non-JMH numbers. They are reproducible in shape, not in
the third digit, and treat them as such. There are no Linux benchmark numbers
yet; the CI job builds and tests there but does not publish a benchmark.

## Real server workload investigation

Before adding any native call to the engine, an existing workload was profiled
rather than guessed at. `EngineResourceBenchmark` (64 chunks, the "resource"
configuration with two workers and a one-CPU JVM) measured **278.5 chunks/s**,
**201 MiB peak** and **98 MiB retained**. A JFR profile of that run produced 163
execution samples, of which:

- **77 (~47%)** were inside `ChunkDataCodecImpl.encode` — the chunk save path;
- roughly another fifth were in `LightJob` (`computeSkyLight`, `sectionAt`,
  `Chunk.section`);
- the enclosing frames were `ChunkSaveJob` and the scheduler's worker loop.

The codec is therefore the first CPU consumer, and it is a Java serialization
problem, not a compute problem. A temporary harness compared the real codec with
an equivalent `ByteBuffer` implementation of the same format:

| Operation | Actual codec | `ByteBuffer` | Speed-up |
| --- | ---: | ---: | ---: |
| encode | 1,771 µs | 70 µs | ~25x |
| decode | 1,626 µs | 66 µs | ~25x |

The `ByteBuffer` output was **byte-identical** and decoded back to the same
snapshot, so this is a safe Java-side improvement, not a format change.

**Decision: no native workload was integrated.** The only native operation is a
bulk integer transform, no real server code calls it, and the profiled hotspot
is a byte-serialization loop that would require one FFM call per block — the
exact pattern the integration rules forbid. Fixing the codec in Java is the
correct first step. If that lands, re-profile before considering native
acceleration again; do not add native code merely because the module exists.

## Memory and lifecycle

- **The caller owns every buffer.** No exported function allocates memory the
  caller must free, and no ownership is transferred across the ABI boundary.
- **Production path (`NativeEngine.transform`).** Each call opens a confined
  arena, allocates an input and an output segment (8 bytes per element total),
  copies in, calls, copies out, and closes the arena. That memory is released
  at the end of the call even when the native call fails.
- **Reusable shape (`NativeBenchmark.NativeReusable`).** One shared arena and two
  pre-allocated segments; the caller keeps them alive and is responsible for
  closing the arena. This is the shape that produced the only wins in the
  benchmark, and it is *not* wired into the server.
- **Last error is thread-local.** `veltis_native_last_error()` points at storage
  valid until the next call on the same thread; it must never be freed.
- **Closing a loaded library is final.** `NativeLoader.LoadedLibrary.close()`
  releases the symbol lookup and the library arena; any later call throws
  `IllegalStateException` instead of touching unmapped code.
- **Engine lifecycle is locked.** `NativeEngine.initialize` and `shutdown` take
  a write lock; transforms take a read lock. An in-flight native call completes
  before unload, and a call that arrives after shutdown uses the Java fallback
  (or throws when fallback is disabled). Repeated `initialize`/`shutdown` calls
  are idempotent.
- **Failed load leaves nothing open.** Every rejection path closes the library
  before returning, and a failed manual `load()` can be retried.

## Limitations

- The module accelerates one bulk primitive. It is not a general native
  acceleration layer, and the server does not depend on it for correctness.
- Windows builds statically link the MinGW C++ runtime, so a copied DLL imports
  only Windows system libraries. Linux builds link the system `libstdc++`,
  `libgcc_s`, `libm` and `libc`, which any distribution with a C++ toolchain
  provides; the CI dependency check enforces that list.
- Only FFM (JDK 22+) is supported. There is no JNI fallback for older JDKs.
- The launcher never packages or downloads a library. An operator installs one
  and is responsible for matching the ABI and platform.
- The benchmark is not JMH and does not control CPU frequency, JIT compilation
  per implementation or the operating system scheduler. Use it to compare
  shapes of integration, not to publish a speed-up claim.
- Linux, macOS and ARM variants are loader-recognized but unverified. The
  `native-linux` CI job exists to change that; until it passes, no such claim is
  made.

## Extending the ABI

1. Add the declaration and a `VELTIS_NATIVE_CAPABILITY_*` bit to
   `veltis_native.h`.
2. Implement it behind the C ABI in `native_engine.cpp` (catching exceptions and
   returning a status code) and put the algorithm in `algorithms.cpp`.
3. Add the bit to `NativeCapabilities` and a downcall to `NativeLoader.Bindings`.
4. Give `NativeEngine` a Java implementation with identical output and a test
   that asserts native/fallback equivalence.
5. Extend `tests/native_tests.cpp` for argument, boundary and lifecycle cases.

Bump `VELTIS_NATIVE_VERSION_MINOR` for an additive change; bump
`VELTIS_NATIVE_ABI_VERSION` only when an existing signature or semantic changes
in a way older callers cannot use.
