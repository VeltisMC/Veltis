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
to the server's own GitHub Release, tagged
`veltis-<channel>-<minecraftVersion>-<short-commit>`. The native archives and
the server JAR are assets of that one release; CI never creates a separate
native-only release. A missing mandatory platform fails the distribution gate;
a missing optional platform is skipped and does not prevent other verified
platforms from being published.

## Release manifest

After uploading the ZIPs, CI reads their asset names, sizes, and
`browser_download_url` values from GitHub Release metadata. It recomputes each
local archive's SHA-256, confirms it matches the recorded size and digest, and
builds the manifest from those actual values; download URLs are never guessed.
Each platform entry records the full commit, normalized OS and architecture,
exact asset name and URL, ZIP SHA-256, included modules, per-library checksums,
and ABI/native versions.

The manifest is not digitally signed. Its authenticity rests on the HTTPS
transport and the official GitHub repository the runtime is configured with.
SHA-256 establishes that an archive matches the manifest that was served; it
does not by itself prove who published the manifest.

The runtime accepts only the release tag and full commit associated with the
running server's embedded build metadata. A platform entry must identify the
exact `<platform>-<short-commit>.zip` asset and be marked verified.

## Installation and fallback

The runtime independently detects the JVM OS and `os.arch`, then normalizes
them to a supported platform key. It downloads only that platform's ZIP and
caches it under `<server home>/cache/native/`. Installed files are kept under
`<server home>/native/<platform>/`, preserving the platform directory and
module subdirectories.

Before installation or reuse, the runtime validates the manifest structure,
HTTPS/GitHub asset URL, archive size and SHA-256, ZIP root and entries,
metadata, module ABI, library checksum, binary format, and binary architecture.
No archive is extracted until its SHA-256 matches the manifest. Downloads and
installs use temporary files/directories, with a lock per platform. Invalid or
unavailable artifacts are reported and leave Java fallback available; an
artifact for a different commit is never silently selected.

Native publication requires no signing key or secret. A missing signing secret
cannot block release publication; the release job only requires the verified
archives and the generated manifest.
