# Testing strategy

The native test layers are independent:

1. CTest validates module operations, boundary conditions, and C ABI fixtures.
2. Java unit tests validate configuration, manifest parsing, distribution,
   fallback, and loader behavior.
3. Java native integration tests load the current host library when available.
4. `verifyNativeRelease` checks the packaged library, manifest, archive, and
   host execution results together.

Run the Java suite without a C toolchain:

```powershell
./gradlew :veltis-native:test
```

To build and execute native tests on the host:

```powershell
./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true
```

Cross-compiled outputs are inspected as artifacts; they are not executed by
host-only tests. A skipped native integration test means the current native
sources were not exercised and must not be reported as a native pass.
