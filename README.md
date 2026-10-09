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
Java 25+
```

The build produces bytecode targeting Java 25 and the launcher includes a version guard that rejects Java versions older than 25. The development toolchain remains Java 26. CI builds and tests against both Java 25 and Java 26. Veltis is under active development, so version support and APIs may change.

## Building

Requirements:

- JDK 25 or 26 (builds with JDK 26; runtime requires Java 25+; guard rejects < 25)
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
java -jar veltismc.jar --nogui
```

Veltis obtains the required Minecraft server files from Mojang, verifies them, prepares the Veltis runtime, and starts the server.

Minecraft itself is not redistributed with Veltis.

## Hosting and container notes

Veltis is built to behave well under a hosting panel or inside a container:

- **EULA first, always.** On a start with no `eula.txt`, one is written with `eula=false` and the server refuses with a non-zero exit and the message `You need to agree to the EULA in order to run the server. Go to eula.txt for more info.` This happens *before* Mojang's files are downloaded and before the patch set is applied, so a panel that has not accepted the EULA does not pay for a runtime it will not use. Veltis never writes `eula=true`.
- **Container-aware sizing.** The scheduler's worker ceiling follows `Runtime.availableProcessors()` (cgroup-aware on modern JVMs) and is clamped to 2–8. World-engine pool ceilings scale with the JVM's maximum heap, so a small container gets small pools and a large host keeps roomy ones. The same jar is frugal on one core and two gigabytes, and unchanged on a workstation.
- **Console and shutdown.** Startup preserves stdin/stdout for panels, and `stop`/signal handling shuts the server down cleanly.
- **Data stays in the server directory.** Worlds, configuration, and logs are written next to the jar; no developer tooling is needed at runtime.

### Off-thread compression (opt-in)

Network compression can be moved off the Netty event loop so a large packet does not stall unrelated connections on a single core. It is **off by default** until it has proven itself in production:

```text
-Dveltis.network.offThreadCompression=true
```

Supporting knobs, if the defaults need tuning:

```text
-Dveltis.network.compressionThreads=<n>   # default: max(1, min(4, cpus / 2))
-Dveltis.network.compressionQueue=<n>     # default: 1024
```

When the bounded queue is full the calling thread performs the compression itself, so backpressure can never drop or reorder a packet.

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
