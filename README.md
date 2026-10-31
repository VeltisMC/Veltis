# VeltisMC

**A performance-focused Minecraft server project.**

VeltisMC is an independently developed Minecraft server project focused on performance, extensibility, and a maintainable server architecture. The project aims to provide a foundation for server owners and developers who want to build reliable Minecraft server experiences.

- **Project:** [VeltisMC](https://www.veltismc.org)
- **Source code:** [GitHub](https://github.com/VeltisMC/Veltis)
- **Website:** [veltismc.org](https://www.veltismc.org)

> **Development status:** VeltisMC is under active development. Features, compatibility, and stability may vary between builds.

## Goals

- **Performance:** Identify and optimize server-side bottlenecks using profiling and reproducible benchmarks.
- **Extensibility:** Develop a clean, maintainable extension API for server developers.
- **Architecture:** Keep the codebase modular and make core systems easier to maintain.
- **Compatibility:** Evaluate compatibility with existing Minecraft server ecosystems where practical.
- **Reliability:** Prioritize correctness, predictable behavior, and testing alongside performance improvements.

## Getting Started

### Prerequisites

Check the repository's Gradle configuration for the exact required Java version and other build requirements.

You will need:

- Git
- A compatible JDK
- The Gradle wrapper included in the repository

### Clone the Repository

```bash
git clone https://github.com/VeltisMC/Veltis.git
cd Veltis
```

### Build

```bash
./gradlew buildVeltisMC
```

If the build succeeds, check the repository's configured distribution and artifact directories for the generated server JAR.

### Optional native engine

VeltisMC can use an optional C/C++ engine through a stable C ABI and the Java
Foreign Function & Memory API. It is not required: the normal build and server
startup use the Java fallback unless a compatible library is installed. Building
the library needs CMake 3.20+ and a C11/C++20 compiler, and is a separate task:

```bash
./gradlew :veltis-native:buildNative
./gradlew :veltis-native:testNative
```

Supported and tested platforms are Windows x86-64 and Linux x86-64. See
[`veltis-native/README.md`](veltis-native/README.md) for the ABI, installation
layout and `veltis.native.*` configuration.

### Run

Use the launch command documented by the current repository version. The exact command and runtime requirements may change during development.

**Note:** Build success does not necessarily mean every feature is production-ready.

## Project Architecture

VeltisMC uses a modular Gradle-based codebase. Its modules and responsibilities should be understood from the current source tree and build configuration.

The project also uses the Shulker patch workflow for maintaining and applying source changes. When contributing, follow the existing workflow rather than editing generated or patched sources in a way that bypasses it.

## Performance and Benchmarking

Performance work should be guided by measurements rather than assumptions.

When evaluating an optimization, consider:

- Server tick duration and tail latency
- CPU and memory usage
- Allocation and garbage-collection overhead
- Behavior under different player and entity loads
- Correctness under concurrent execution, where applicable

Benchmark results should include the test environment, workload, methodology, and comparison baseline. No performance advantage should be assumed without reproducible evidence.

## Compatibility

Minecraft version support and compatibility with third-party server extensions are dependent on the specific VeltisMC build.

Consult the repository's current build configuration, release information, and documentation before deploying a build or migrating an existing server.

Do not assume that an extension written for another server implementation will work without an explicitly supported compatibility layer.

## Contributing

Contributions, bug reports, testing, and technical feedback are welcome.

Before submitting a change:

1. Review the existing architecture and coding conventions.
2. Keep changes focused and avoid unrelated modifications.
3. Preserve existing gameplay behavior unless a change is explicitly intended.
4. Add or update tests where appropriate.
5. Run the relevant build and tests.
6. Document meaningful architectural changes and performance claims.
7. Submit a pull request describing the problem, implementation, and validation.

For performance-sensitive changes, include benchmark evidence whenever possible.

## Bug Reports

When reporting a bug, include:

- VeltisMC build or commit
- Minecraft version
- Java version
- Operating system
- Steps to reproduce the issue
- Relevant logs, stack traces, or profiling results
- Expected behavior and actual behavior

Avoid sharing server credentials, access tokens, or other sensitive information in public reports.

## License

See the repository's `LICENSE` file for the applicable license and redistribution terms.

## Links

- **Website:** https://www.veltismc.org
- **GitHub repository:** https://github.com/VeltisMC/Veltis

---

*VeltisMC is an independent development project. Verify the current repository documentation before using development builds in production.*