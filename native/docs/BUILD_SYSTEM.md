# Build system

The native build is isolated from the ordinary Java build. CMake configures
only the C language, requires C11 with extensions disabled, and discovers
independent shared-library targets from `modules/<name>/`.

## Gradle tasks

```powershell
./gradlew :veltis-native:buildNative
./gradlew :veltis-native:testNative
./gradlew :veltis-native:test
./gradlew :veltis-native:verifyNativeRelease -PveltisNativeTest=true
```

- `buildNative` configures and compiles the host modules.
- `testNative` runs CTest for the native build.
- `test` runs the Java unit and integration suite; native cases require a
  current host binary and otherwise skip.
- `verifyNativeRelease` combines host build, tests, packaging, and artifact
  checks. It is not a cross-platform execution claim.

Native tasks require CMake 3.21+ and a C11 compiler. Java-only tasks do not
require native tooling. The default output is
`native/build/native/<platform>/`; CMake state is kept separately under
`native/build/native/cmake/<platform>/`.

The generated build-info JSON records the target platform, compiler, CMake
configuration, and exports derived from the public header. The package tasks
compare it with the library and archive before a release artifact is accepted.
