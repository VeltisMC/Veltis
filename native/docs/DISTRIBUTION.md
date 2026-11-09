# Distribution

## Commit-pinned platform archives

Each verified CI platform produces one ZIP named
`<os>-<architecture>-<short-commit>.zip`, such as
`linux-x86_64-20421c2.zip`. The normalized OS is `windows`, `linux`, or
`macos`; the normalized architecture is `x86_64` or `aarch64`.

The ZIP has exactly one top-level `<os>-<architecture>/` directory. It contains
one directory and library per successfully built native module, plus
`native-build-info.json`, which records the full and short commit, platform,
module ABI/version, and library checksum. It does not contain build
intermediates or binaries for other platforms.

CI attaches only archives that pass the platform's required verification gates
to the `veltis-native-<short-commit>` GitHub Release. A missing mandatory
platform fails the distribution gate; a missing optional platform is skipped
and does not prevent other verified platforms from being published.

## Signed Release manifest

After uploading the ZIPs, CI reads their asset names, sizes, and
`browser_download_url` values from GitHub Release metadata. It builds and signs
the manifest from those actual values; download URLs are never guessed. The
Ed25519 signature covers the manifest's exact bytes. Each platform entry
records the full commit, normalized OS and architecture, exact asset name and
URL, ZIP SHA-256, included modules, per-library checksums, and ABI/native
versions.

The runtime accepts only the release tag and full commit associated with the
running server's embedded build metadata. It verifies the signature before
trusting artifact URLs or checksums. A platform entry must identify the exact
`<platform>-<short-commit>.zip` asset and be marked verified.

## Installation and fallback

The runtime independently detects the JVM OS and `os.arch`, then normalizes
them to a supported platform key. It downloads only that platform's ZIP and
caches it under `<server home>/cache/native/`. Installed files are kept under
`<server home>/native/<platform>/`, preserving the platform directory and
module subdirectories.

Before installation or reuse, the runtime validates the signed manifest,
HTTPS/GitHub asset URL, archive size and checksum, ZIP root and entries,
metadata, module ABI, library checksum, binary format, and binary architecture.
Downloads and installs use temporary files/directories, with a lock per
platform. Invalid or unavailable artifacts are reported and leave Java fallback
available; an artifact for a different commit is never silently selected.

Native publication requires the repository signing key whose public half is
embedded in `NativeManifest`. The workflow refuses to publish an unsigned
manifest.
