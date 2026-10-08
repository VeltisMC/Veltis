# Veltis

Veltis is a high-performance Minecraft server built around a simple idea: keep vanilla Minecraft at the center, and improve how it runs.

It is not based on Bukkit, Spigot, or Paper. Veltis works directly with Minecraft's server code and adds its own runtime, world engine, and patching system.

## What we're building

Veltis is focused on:

- Multithreaded world simulation
- Better use of modern CPUs
- Lower server overhead
- A clean development workflow
- Fast and reliable server startup
- A simple foundation for future plugin development

The goal isn't to change how Minecraft works. The goal is to make the server underneath it better.

## Shulker

Minecraft changes in Veltis are maintained through **Shulker**, the project's source patch system.

```text
server/
└── Shulker/
    ├── code/
    ├── data/
    └── modules/
```

Shulker patches are kept in Git and applied during the build. The resulting runtime patch is generated from the final patched Minecraft classes.

This keeps Minecraft changes reviewable, reproducible, and separate from the rest of the Veltis codebase.

## Architecture

Veltis is split into three main parts:

```text
launcher
   │
   ├── patch-engine
   │
   └── server
          │
          └── world engine
```

- **launcher** — starts Veltis and handles the server entry point.
- **patch-engine** — handles Minecraft acquisition, patching, compilation, and runtime preparation.
- **server** — contains the Veltis runtime and world simulation engine.

The world engine is designed around region ownership and single-writer state. Independent work can run across workers while conflicting world mutations remain ordered.

## Minecraft versions

The current development target is:

```text
Minecraft 26.3
Java 26
```

Veltis is under active development, so version support and APIs may change.

## Building

Requirements:

- JDK 26
- Git
- Internet connection for the first Minecraft download

Build the project:

```bash
./gradlew build
```

Build the Veltis server distribution:

```bash
./gradlew buildVeltisMC
```

The resulting distribution can be found under:

```text
build/distributions/
```

## Running

Once you have the Veltis distribution:

```bash
java -jar server.jar --nogui
```

Veltis obtains the required Minecraft server files from Mojang, verifies them, prepares the Veltis runtime, and starts the server.

Minecraft itself is not redistributed with Veltis.

## Contributing

Veltis is open source and contributions are welcome.

If you're interested in working on the project, start with:

[CONTRIBUTING.md](CONTRIBUTING.md)

Some of the areas we're actively working on include:

- World simulation
- Multithreading
- Server performance
- Minecraft patches
- Shulker
- Runtime architecture
- Plugin infrastructure

## Project status

Veltis is currently in active development.

The core framework and build pipeline are in place, while the world engine and Minecraft-side optimizations are still being integrated and tested.

Expect things to change.

## License

Veltis is licensed under the GNU General Public License v3.0.

Developed by VeltisMC.
