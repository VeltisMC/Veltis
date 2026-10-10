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

Build the release:

```bash
./gradlew buildVeltisMC
```

The root `build.gradle.kts` is the one place that defines a release, and every
output derives from it:

| Value | Source | Default |
|---|---|---|
| Minecraft version | `-PminecraftVersion` / `gradle.properties` | `26.3` |
| Release channel | `-Pchannel` / `gradle.properties` | `nightly` |
| Commit | `GITHUB_SHA`, then `git rev-parse HEAD` | the checked-out revision |

`buildVeltisMC` writes exactly one runnable artifact, always at the same path:

```text
build/distributions/veltis.jar
```

The local name is deliberately constant: a start script never has to know the
channel or the commit, and stable, beta and nightly builds all land on the same
file. The channel- and commit-qualified name is applied only when the artifact is
published, so it never becomes a second jar in `build/distributions/`:

```text
Veltis <MinecraftVersion> <Channel> <7-char-commit>.jar
```

For example, on the CI release page:

```text
Veltis 26.3 nightly 0123456.jar
```

That spaced string is the release **title**; a release asset cannot keep it.
GitHub normalizes every uploaded asset's file name — spaces and everything
outside `[A-Za-z0-9_+@-]` become dots -- so the file the release actually stores
is:

```text
Veltis.26.3.nightly.0123456.jar
```

The channel is explicit and is never inferred from the commit or the branch;
build a different channel with `-Pchannel`:

```bash
./gradlew buildVeltisMC -Pchannel=stable
```

The same build writes `build/metadata/build-metadata.json`, the
machine-readable description a download page needs. `artifact` is the human
release title, `assetName` is the same file as a GitHub release stores it, and
`distributionArtifact` is the file the build actually wrote:

```json
{
  "channel": "nightly",
  "version": "1.0.0-SNAPSHOT",
  "minecraftVersion": "26.3",
  "commit": "0123456789abcdef0123456789abcdef01234567",
  "shortCommit": "0123456",
  "artifact": "Veltis 26.3 nightly 0123456.jar",
  "assetName": "Veltis.26.3.nightly.0123456.jar",
  "distributionArtifact": "veltis.jar",
  "minimumJavaVersion": 25
}
```

The workflow uploads and verifies `assetName`, because that is the string the
release's own asset list holds; the release is titled with `artifact`. A
verification that compared the spaced title to the release's assets is what
reported a successful GitHub Release as a missing one.

The launcher's own jar (`launcher/build/libs/veltismc-1.0.jar`) is the
intermediate the packaging step reads; it is not a distributable anyone takes
away. The commit is resolved from `GITHUB_SHA` first so a CI build and the file
it produces name the same revision, and a build whose commit cannot be resolved
fails instead of inventing one.

## Running

Once you have the distribution:

```bash
java -jar veltis.jar --nogui
```

Veltis obtains the required Minecraft server files from Mojang, verifies them, prepares the Veltis runtime, and starts the server.

Minecraft itself is not redistributed with Veltis.

## Releases

`.github/workflows/gradle.yml` builds and tests every pull request and every
push to `main`, then publishes the build as a durable GitHub Release asset — a
workflow artifact expires, a release does not. A `workflow_dispatch` run accepts
a channel, which is passed to Gradle as `-Pchannel`, so the release name, the
metadata and the published asset are one value.

Every build gets its own release, tagged `veltis-<channel>-<minecraft>-<sha>`, so
each channel keeps a real history and the download URL is permanent. Non-stable
channels are marked as prereleases, so the website can tell them apart even when
it reads the releases API.

After the release asset exists, the workflow dispatches the metadata plus the
durable download URL to the website repository with a `veltis-release` repository
event. Two repository settings turn that on; without them the workflow prints a
warning and skips, rather than reporting a publication that did not happen:

- **Secret** `WEBSITE_DISPATCH_TOKEN` — a fine-grained PAT with `contents:
  write` on the website repository only.
- **Variable** `WEBSITE_REPOSITORY` — the website repository, for example
  `VeltisMC/website`.

The website repository is a separate checkout, so its half of the integration —
a workflow that reacts to `veltis-release`, validates the payload, updates the
channel-specific metadata and deploys through Vercel — lives there, not here. Its
`veltis-release` receiver and download-page changes are described in that
repository. This repository's side is complete: the artifact, its metadata, the
durable release asset, and the exact dispatch the other side receives.

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
