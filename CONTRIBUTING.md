# Contributing to Veltis

Thanks for contributing to Veltis.

Veltis is an open-source Minecraft server project maintained under the VeltisMC organization. Contributions are welcome, whether you're fixing a bug, improving performance, working on the world engine, improving Shulker, or improving the documentation.

## Requirements

You'll need:

- JDK 26
- Git
- An internet connection for the initial Minecraft/dependency setup
- IntelliJ IDEA or another Java IDE

You don't need to install Gradle separately. The repository includes the Gradle wrapper.

## Getting started

Clone the repository:

```bash
git clone https://github.com/VeltisMC/Veltis.git
cd Veltis
```

Build the project:

### Windows

```powershell
.\gradlew.bat buildVeltisMC
```

### Linux / macOS

```bash
./gradlew buildVeltisMC
```

## Project structure

```text
Veltis/
├── launcher/
├── patch-engine/
├── server/
│   └── Shulker/
│       ├── code/
│       ├── data/
│       └── modules/
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```

### Modules

**`launcher`**

The application entry point and distribution.

**`patch-engine`**

Handles the Minecraft preparation and patching pipeline.

**`server`**

Contains the Veltis server runtime, world engine, and Minecraft-facing code.

**`server/Shulker`**

Contains the patches used to modify Minecraft.

Keep the module structure simple. A new module should have a clear technical reason to exist.

## Working with Shulker

Minecraft source changes belong in Shulker.

The usual workflow is:

```text
Apply patches
    ↓
Make changes
    ↓
Rebuild patches
    ↓
Build and test
    ↓
Review the patch
```

Apply the patches:

```bash
./gradlew applyPatches
```

On Windows:

```powershell
.\gradlew.bat applyPatches
```

Make your changes in:

```text
build/minecraft/<version>/patched/
```

Rebuild the patches:

```bash
./gradlew rebuildPatches
```

Then review the result:

```bash
git diff -- server/Shulker
```

Do not make direct changes to the pristine Minecraft source tree.

## Patch categories

Shulker is organized into:

```text
server/Shulker/
├── code/
├── data/
└── modules/
```

Keep changes in the category they belong to.

Minecraft modifications should not be implemented through a separate patching mechanism.

## Patch naming

Use short, descriptive names:

```text
001-Fix-Hopper-Lookup.patch
002-Improve-Chunk-Ticking.patch
003-Add-Veltis-Bootstrap.patch
```

Patch numbering is managed by the patch workflow. Don't manually create gaps or renumber the existing patch set.

## Performance changes

Performance is an important part of Veltis, but faster code is not automatically better code.

Before submitting a performance change, consider:

- What work is being removed or reduced?
- How often does the code run?
- Does it reduce allocations?
- Does it improve cache or memory behavior?
- Can the work safely run concurrently?
- Does it change vanilla behavior?
- Can the improvement be measured?

Avoid adding threads simply because something can technically run in parallel.

Veltis aims for aggressive multithreading where it is safe and useful. World state and gameplay logic must remain correct.

For meaningful performance changes, include benchmark or profiling results in the pull request.

## Testing

Before opening a pull request, run at least:

```bash
./gradlew clean buildVeltisMC
```

If your change affects Shulker, also verify that the patches apply cleanly.

For patch changes, check that rebuilding the patch set produces the expected result and does not introduce unrelated changes.

Test the actual affected functionality whenever possible.

## Pull requests

Keep pull requests focused.

A good pull request should explain:

- What changed
- Why it changed
- How it was tested
- Any performance impact
- Any compatibility or behavior changes

Avoid unrelated formatting changes or drive-by refactoring.

Large architectural changes should be discussed before implementation when possible.

## Code style

Follow the style of the surrounding code.

Prefer:

- Simple implementations
- Clear names
- Small, focused changes
- Existing project patterns
- Minimal dependencies

Avoid:

- Unnecessary abstractions
- Large utility classes
- Manager/Service/Controller chains without a real need
- Keeping dead code "just in case"
- Adding dependencies for functionality the JDK or existing code already provides

## Dependencies

Keep runtime dependencies small.

Before adding a dependency, consider whether:

1. The functionality is actually required.
2. The JDK already provides it.
3. Minecraft already provides an equivalent dependency.
4. It is build-time only.
5. The additional maintenance cost is justified.

Do not bundle or shade dependencies without a specific reason.

## Minecraft changes

Changes to Minecraft belong in:

```text
server/Shulker/
```

Keep patches focused and logically separated.

If multiple changes are unrelated, split them into separate patches rather than creating one large patch.

## Commit messages

Keep commit messages short and descriptive.

Examples:

```text
fix: prevent unnecessary hopper searches
perf: reduce chunk ticking overhead
patch: improve server bootstrap
build: clean runtime dependencies
docs: update contribution guide
```

## Reporting bugs

When reporting a bug, include:

- Veltis version or commit
- Minecraft version
- Java version
- Operating system
- Steps to reproduce
- Expected behavior
- Actual behavior
- Relevant logs or stack traces

For performance issues, include profiling or benchmark information when available.

## Contributing to discussions

For major changes to the architecture, threading model, patch system, or public APIs, open a discussion or issue before doing substantial implementation work.

This helps avoid spending time on an approach that may conflict with the direction of Veltis.

## Documentation and comments

Contributions must include proper documentation or comments for the work being done.

Document code when the purpose, behavior, or reasoning behind an implementation is not immediately obvious.

For larger changes, explain:

- What the code does
- Why it is implemented that way
- Any important assumptions or limitations
- Any non-obvious behavior future contributors should know

Avoid unnecessary comments that simply restate what the code already says. Comments should explain the **why** when the code alone cannot make it clear.

## License

Veltis is licensed under the GNU General Public License v3.0.

By contributing to Veltis, you agree that your contributions will be licensed under the same license.

---

Veltis is actively developed, so parts of the codebase and development workflow may change over time.

Thanks for helping build Veltis.
