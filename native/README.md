# Optional native modules

VeltisMC's optional native layer is written in C11 and loaded from Java through
the Foreign Function & Memory API. It is not required to build or start the
server: when a compatible module is missing or rejected, the Java implementation
remains available.

The current module is `transform`, a deterministic batch `int32` operation.
Native platform recognition is not evidence that a module has been built or
tested on that platform; CI and release artifacts are the source of such
evidence.

## Layout

| Path | Purpose |
| --- | --- |
| `include/veltis/native/veltis_module.h` | Shared module ABI constants and visibility/calling-convention macros. |
| `include/veltis/native/veltis_native.h` | Current native ABI declarations, including the transform operation. |
| `modules/<name>/main.c` | Module identity, version, lifecycle, capabilities, and operation exports. |
| `modules/<name>/*.c` | Module-local C implementation; each CMake target compiles only its own module directory. |
| `tests/` | C contract tests and incompatible-library fixtures. |
| `src/main/java/org/veltismc/nativeengine/` | FFM loader, module lifecycle, Java fallback, and checksum-verified distribution handling. |
| `docs/` | ABI, build, distribution, security, and testing notes. |

CMake discovers module directories in sorted order. Each directory must have a
valid lowercase module name and a `main.c`; it becomes an independent shared
library target. The build uses C11 with extensions disabled and does not enable
a C++ language or compile C++ sources.

## Build and test

Native tooling is needed only for native tasks:

```powershell
./gradlew :veltis-native:buildNative
./gradlew :veltis-native:testNative
./gradlew :veltis-native:test
./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true
```

`buildNative` requires CMake 3.21 or newer and a C11 compiler. `testNative` runs
the CTest suite. Java tests run without a native compiler; native integration
cases are skipped when the built library is absent or older than the current
native sources. `verifyNativeRelease` is a host-build verification task, not a
claim that every recognized platform has a verified artifact.

The Gradle output directory is `native/build/native/<platform>/`. Library names
derive from their module directory: `<module>.dll` on Windows,
`lib<module>.so` on Linux, and `lib<module>.dylib` on macOS.

## Module ABI and Java loading

Every loadable module identifies itself and reports an ABI version and semantic
version. The loader verifies the requested module name, ABI, engine version, and
required lifecycle symbols before returning a loaded module. A module's
capability mask describes its operations; `transform` additionally exports
`veltis_native_transform_i32`.

`NativeEngine` keeps the existing transform convenience API and also exposes
module-specific status and load accessors. `NativeLoader.tryLoad(config, name,
abi)` performs the FFM handshake for a named module. Callers must use the
module's exported operations only while its `LoadedLibrary` remains open.

The server defaults to the `transform` module. Java fallback is controlled by
the existing `veltis.native.*` properties; disabling fallback makes an
unavailable operation fail explicitly rather than silently changing its result
source.

## Distribution

Each successful CI platform lane produces one archive for the full commit, for
example `linux-x86_64-20421c2.zip`. Its only top-level directory is
`linux-x86_64/`, containing each module in its own directory plus
`native-build-info.json`. The archive contains no objects, build trees, or
other-platform binaries.

The archives are assets of the same GitHub Release as the server JAR, whose tag
is `veltis-<channel>-<minecraftVersion>-<short-commit>`. Its manifest records
the full and short commit, each platform ZIP's actual Release asset URL and
SHA-256, and the included modules and ABI versions. CI publishes only archives
that passed the host verification gates; unavailable optional platforms are
recorded as skipped.

At startup, Veltis independently detects the JVM operating system and CPU
architecture, then requests only that platform's ZIP for the exact commit
embedded in the server build. Downloads are cached in
`<server home>/cache/native/`; modules are installed under
`<server home>/native/<platform>/`. The installer validates the archive
SHA-256 against the manifest, URL, archive size, ZIP root and entries, build
metadata, module ABI, and binary architecture before loading modules
independently through FFM. Missing, unsupported, or invalid artifacts leave
Java fallback available.

The manifest is not digitally signed. Its authenticity rests on being fetched
over HTTPS from the official GitHub repository the runtime is configured with;
SHA-256 proves each archive matches that manifest, not who published it. No
signing secret is required for publication; do not generate or substitute
release metadata locally.

## Documentation

- [ABI contract](docs/ABI_CONTRACT.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Build system](docs/BUILD_SYSTEM.md)
- [Distribution](docs/DISTRIBUTION.md)
- [Java FFM integration](docs/JAVA_FFM_INTEGRATION.md)
- [Platform support](docs/PLATFORM_SUPPORT.md)
- [Security model](docs/SECURITY_MODEL.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)
