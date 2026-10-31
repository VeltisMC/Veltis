# Contributing to Veltis

Thank you for your interest in contributing to Veltis.

Contributions to bug fixes, performance, world simulation, source patches, and documentation are welcome.

## Requirements

- JDK 26
- Git
- Internet access for initial setup
- A Java IDE (IntelliJ IDEA recommended)

CMake 3.20+ and a C11/C++20 compiler are needed only for the optional native
engine's `native` tasks; ordinary Java builds do not require them. See
[`veltis-native/README.md`](veltis-native/README.md).

Gradle is provided through the repository wrapper.

## Setup

Clone the repository:

```bash
git clone https://github.com/VeltisMC/Veltis.git
cd Veltis
```

Build Veltis:

```bash
./gradlew buildVeltisMC
```

## Making Changes

Before making changes, inspect the relevant module and follow the existing code conventions.

For ordinary Java or Kotlin changes, edit the appropriate source files.

For changes to Minecraft source code, use the repository's patch workflow rather than editing the original source directly.

### Working with Source Patches

1. Apply the existing patches to prepare the development workspace:

   ```bash
   ./gradlew applyPatches
   ```

2. Locate the generated Minecraft source workspace using the paths configured by the repository.
3. Make the required changes in that workspace.
4. Rebuild the patches:

    ```bash
   ./gradlew rebuildPatches
   ```

5. Review the resulting changes and ensure only the intended modifications are included.

Keep each patch focused on a specific change. Preserve existing behavior unless the contribution explicitly intends to change it. Do not manually renumber existing patches.

## Development Guidelines

- Follow the surrounding code style and architecture.
- Prefer simple, maintainable implementations.
- Avoid unnecessary dependencies, abstractions, and allocations.
- Preserve Minecraft gameplay behavior unless a change is intentional.
- Ensure world state remains consistent under concurrent execution.
- Avoid unrelated refactoring in focused bug fixes.
- Document non-obvious design decisions where necessary.

## Testing

Before submitting a contribution, build the project:

```bash
./gradlew clean buildVeltisMC
```

Also run relevant tests and verify the affected functionality.

If a change touches the optional native engine, build and test both sides:

```bash
./gradlew :veltis-native:check -PveltisNativeTest=true
```

This requires CMake and a C/C++ compiler. Without that toolchain, run
`./gradlew :veltis-native:test`; the real-library test skips and the unit tests
still cover configuration, compatibility rules and the Java fallback.

For Minecraft source changes, confirm that the patch workflow completes successfully and that rebuilding the project does not introduce unrelated changes.

For performance improvements, include profiling or benchmark results when available. Do not claim performance gains without supporting measurements.

## Pull Requests

Keep pull requests focused and provide:

- **Summary:** What changed?
- **Motivation:** Why is the change needed?
- **Testing:** How was it verified?
- **Performance:** What impact does it have, if applicable?
- **Compatibility:** Does it affect existing behavior or integrations?

Discuss substantial changes to the public API, architecture, threading model, or patch workflow before implementing them.

## Reporting Bugs

When reporting a bug, include:

- Veltis commit or build version
- Minecraft version
- Java version
- Operating system
- Steps to reproduce
- Expected and actual behavior
- Relevant logs or stack traces

For performance issues, include profiling or benchmark results when available.

## License

Veltis is licensed under the GNU General Public License v3.0, as specified in the project's licensing terms. Contributions must comply with the applicable license.

---

Thank you for helping improve Veltis.