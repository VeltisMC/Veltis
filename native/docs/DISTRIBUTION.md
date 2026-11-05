# veltis-native Selective Distribution

How a published native library reaches a running server: which one artifact is
fetched, how it is proven genuine, where it is installed, and what happens when
any of it fails.

Companion documents: [JAVA_FFM_INTEGRATION.md](JAVA_FFM_INTEGRATION.md) for the
loader the installation feeds, [ABI_CONTRACT.md](ABI_CONTRACT.md) for the version
negotiation, [PLATFORM_SUPPORT.md](PLATFORM_SUPPORT.md) for what each platform
claim means, [BUILD_SYSTEM.md](BUILD_SYSTEM.md) and
[CI_SUPPORT_MATRIX.md](CI_SUPPORT_MATRIX.md) for the lanes that produce the
artifacts, [SECURITY_MODEL.md](SECURITY_MODEL.md) for the threat model this sits
inside.

## 1. The rule

**One server, one platform, one archive.** A Linux AArch64 server downloads
`veltis-native-linux-aarch64.zip` and nothing else — not because the other five
are filtered out of a bundle, but because they are never requested. The only
document that may describe more than one platform is the manifest, and a
manifest is metadata: a few kilobytes, signed, capped at
[`MAX_MANIFEST_BYTES`](../src/main/java/org/veltismc/nativeengine/NativeManifest.java#L79).

The operator experience the rule exists to produce is one file:

```bash
java -jar veltis.jar
```

The server resolves its own home, creates `native/` under it, works out which
single artifact matches the operating system *and* the JVM architecture, and
either reuses what is already installed or fetches that one archive. If anything
in that chain fails, the server starts anyway on its Java implementations.

## 2. Components

| Component | Responsibility |
| --- | --- |
| [`NativeDistributionManager`](../src/main/java/org/veltismc/nativeengine/NativeDistributionManager.java#L118) | The whole decision: detect, select, cache-check, fetch exactly one artifact, hand the installed path to the loader |
| [`NativeDistributionTransport`](../src/main/java/org/veltismc/nativeengine/NativeDistributionTransport.java) | The only code allowed to touch the network; scheme, host, redirect and size rules |
| [`NativeDistributionInstaller`](../src/main/java/org/veltismc/nativeengine/NativeDistributionInstaller.java) | The only code allowed to write under `native/<platform>/`; unpack, verify, lock, move |
| [`NativeManifest`](../src/main/java/org/veltismc/nativeengine/NativeManifest.java) | The signed document and every validation of its fields; owns the embedded trust anchor |
| [`NativeDistributionConfig`](../src/main/java/org/veltismc/nativeengine/NativeDistributionConfig.java) | The five operator switches, read exactly like `NativeConfig` |
| `VeltisBootstrap` | Calls `ensureInstalled` before `NativeEngine.initialize` and reports the outcome |

The manager is a *client* of the loader, not a rival: it writes a file to the
place [`NativeLoader`](../src/main/java/org/veltismc/nativeengine/NativeLoader.java)
already searches (`<home>/native/<platform>`) and then gets out of the way. It
also asks the loader [`isRecognizedPlatform`](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L683)
before deciding anything, so an operating system the loader would refuse never
reaches the network at all.

## 3. The sequence

```
startup
  -> veltismc.home resolved (VeltisBootstrap sets it from --home/working dir)
  -> <home>/native/ created
  -> platform key = normalize(os.name) + "-" + normalize(os.arch of the JVM)
  -> not one of the six recognised keys?  -> UNSUPPORTED_PLATFORM, stop
  -> already-installed library verifies?  -> REUSED, zero requests, stop
  -> policy "never" or offline?           -> NO_NETWORK / OFFLINE, stop
  -> fetch native-manifest.json + .sig (or use the cached pair)
  -> verify signature against the embedded key, then validate every field
  -> select THIS platform's entry; absent or not host-verified -> PLATFORM_ABSENT
  -> download exactly that one asset to <library>.zip.part
  -> digest the archive, unpack through the three-entry allowlist,
     digest the library, probe the container and architecture
  -> atomic move into place + write the install record
  -> NativeEngine.initialize() -> NativeLoader -> FFM
  -> any failure above: log it, continue on Java
```

Outcomes are an exhaustive enum
([`Outcome`](../src/main/java/org/veltismc/nativeengine/NativeDistributionManager.java#L59)),
and only `shouldReportSkip()` ones produce the operator-visible line
`Native acceleration unavailable for <platform>; continuing with Java fallback.`

## 4. Platform matrix

Six keys, and no others, because the loader only knows these
([`RECOGNIZED_PLATFORMS`](../src/main/java/org/veltismc/nativeengine/NativeLoader.java#L57)):

| Platform key | Release status | Meaning at runtime if absent |
| --- | --- | --- |
| `windows-x86_64` | mandatory ([`DISTRIBUTION_MANDATORY_PLATFORMS`](../build.gradle.kts#L1898)) | packaging fails — a release without it is not a release |
| `linux-x86_64` | mandatory | as above |
| `linux-aarch64` | mandatory | as above |
| `macos-x86_64` | optional ([`DISTRIBUTION_OPTIONAL_PLATFORMS`](../build.gradle.kts#L1903)) | skipped in the manifest with a recorded reason; servers on it log the skip line and run on Java |
| `macos-aarch64` | optional | as above |
| `windows-aarch64` | optional | as above |

Architecture is taken from the **JVM** (`os.arch`), never from the operating
system, because a 32-bit JVM on a 64-bit Windows install is the case that would
otherwise produce a library the process cannot load. A cross-compiled artifact
(`verification: "cross"`) is representable in the manifest and is *never*
installed: the manager reports `PLATFORM_ABSENT` and requests nothing.

## 5. Release artifacts

Each platform's archive contains exactly three entries, in this order, and the
installer's allowlist refuses any other set:

```text
veltis-native-<platform>.zip
├── <platform library file>        veltis_native.dll / libveltis_native.so / libveltis_native.dylib
├── veltis-native-manifest.json    the per-platform record the build wrote
└── SHA256SUMS                     digests of both files above
```

The release publishes, as GitHub Release assets under the tag
`veltis-native-v<nativeVersion>`:

```text
veltis-native-windows-x86_64.zip
veltis-native-linux-x86_64.zip
veltis-native-linux-aarch64.zip
… (only the platforms that passed verification)
native-manifest.json
native-manifest.json.sig
```

## 6. Manifest schema

The signed document is the whole trust boundary; the runtime refuses a field it
cannot validate rather than guessing
([`parseArtifact`](../src/main/java/org/veltismc/nativeengine/NativeManifest.java#L270)):

```json
{
  "schemaVersion": 1,
  "kind": "veltis-native-distribution-manifest",
  "release": "veltis-native-v1.0.0",
  "nativeVersion": "1.0.0",
  "abiVersion": 1,
  "generatedAt": "2026-11-05T00:00:00Z",
  "commit": "<40 hex, the commit the artifacts were built from>",
  "artifacts": {
    "linux-aarch64": {
      "platform": "linux-aarch64",
      "os": "linux",
      "architecture": "aarch64",
      "asset": "veltis-native-linux-aarch64.zip",
      "sha256": "<64 hex of the archive>",
      "sizeBytes": 123456,
      "library": "libveltis_native.so",
      "librarySha256": "<64 hex of the library inside it>",
      "abiVersion": 1,
      "nativeVersion": "1.0.0",
      "verified": true,
      "verification": "host"
    }
  }
}
```

Rules the parser enforces, each with a refusal that names the field:
`schemaVersion` and `kind` must match this build; root and per-artifact
`abiVersion` must equal `NativeLoader.EXPECTED_ABI_VERSION`; `platform` must
equal its own key *and* `os + "-" + architecture`; `asset` must be exactly
`veltis-native-<platform>.zip`, so a signed manifest cannot describe a path, a
URL, or another platform's archive; `library` must be the file that platform's
loader looks for; digests are 64 lowercase hex; `sizeBytes` is within
`1..MAX_ARCHIVE_BYTES` (64 MiB); `verified` is a real boolean and
`verification` is `host` or `cross`.

A signed manifest that fails these is **corruption or a bug, not a platform to
skip** — it is refused as a document, and the server falls back to Java.

## 7. Signature mechanism

Ed25519 over the exact manifest bytes, stored beside it as
`native-manifest.json.sig` (base64). There is no canonical re-serialisation:
what is signed is what is parsed, so no verifier and reader can disagree about
the document.

The runtime trusts exactly one key, embedded in the binary
([`TRUSTED_PUBLIC_KEY_BASE64`](../src/main/java/org/veltismc/nativeengine/NativeManifest.java#L103)),
so verification needs no configuration, no network and no trust-on-first-use.
A checksum would only prove the download was not damaged; the signature is what
proves the publisher.

### Producing and rotating the pair

```bash
# once, by whoever administers the repository
./gradlew :veltis-native:generateNativeManifestKey \
  -PveltisNativeManifestKeyOut="$HOME/manifest-signing-key.pem"
```

The task refuses to print the private half, refuses to write it inside the
repository, and refuses to overwrite an existing file (a silent second key
would orphan every manifest signed with the first). It prints the public
constant to paste into `NativeManifest.TRUSTED_PUBLIC_KEY_BASE64` and the
contents of that file become the `VELTIS_NATIVE_MANIFEST_KEY` repository secret.

```bash
# proves the secret and the anchor are one pair
VELTIS_NATIVE_MANIFEST_KEY="$(cat ~/manifest-signing-key.pem)" \
  ./gradlew :veltis-native:verifyNativeManifestKeyPairing
```

Three places catch a mismatch, because every one of them is a way to ship a
manifest no server will accept:

1. this pairing check, run in CI before packaging
   ([`gradle.yml:1050`](../../.github/workflows/gradle.yml#L1050)) — it is gated on
   `env.VELTIS_NATIVE_MANIFEST_KEY != ''`, because the `secrets` context is not
   available in a step `if:`;
2. [`packageNativeDistribution`](../build.gradle.kts#L2079) signs the manifest it
   just wrote and then **reads it back through `NativeManifest` itself**, which
   verifies against the embedded key;
3. every server, at startup, refuses a manifest whose signature does not verify.

A rotation is a two-step change — new secret, new constant — and the two steps
must land in the same release. Servers running an older constant reject a
manifest signed by a new key; that is the point of an embedded anchor, and the
reason the fallback is Java rather than "try anyway".

## 8. Network and filesystem safety

| Requirement | Where it lives |
| --- | --- |
| HTTPS only, one documented exception | [`requireTransport`](../src/main/java/org/veltismc/nativeengine/NativeDistributionTransport.java#L99): plain HTTP is accepted for a loopback address and nothing else, which is what lets the suite run a real `HttpServer` without weakening a real installation |
| Official host allowlist | [`OFFICIAL_HOSTS`](../src/main/java/org/veltismc/nativeengine/NativeDistributionTransport.java#L48); an operator's `veltis.native.repository` adds exactly its own host |
| Redirects cannot change the rules | every hop is re-validated, `NEVER`-follow policy in the client, five hops maximum |
| Bounded bodies | connect/request timeouts from config, manifest capped at 1 MiB, archive at 64 MiB and at `sizeBytes` exactly |
| Asset names cannot become paths | [`ASSET_NAME`](../src/main/java/org/veltismc/nativeengine/NativeDistributionTransport.java#L46) plus the manifest's fixed asset naming |
| Zip-slip / unexpected entries | three-entry allowlist in the installer — traversal is refused by shape, not by normalising `..` |
| Zip bomb | entries are read into a capped byte sink, not streamed to disk |
| Wrong architecture never installed | `NativeLoader.checkBinary` runs on the extracted bytes *before* the atomic move |
| Atomic installation | download to `*.zip.part`, unpack to a temporary file, one `ATOMIC_MOVE` (with a documented same-directory fallback where the filesystem cannot do it) |
| Concurrent installs | an exclusive `FileLock` on `.distribution.lock`; a second server reports `LOCKED` rather than racing |
| Half-written state | an installation without a valid record is not trusted, so a corrupt file is replaced rather than adopted |
| No hot replacement | the distribution runs before the library is loaded; a new version takes effect on the next restart |
| Other platforms preserved | only `<platform>/` and `manifests/` are written; unrelated platform directories are left alone |

Nothing is ever loaded that has not passed the archive digest, the library
digest, the inner-manifest agreement, `SHA256SUMS`, the container probe and the
ABI/architecture/version checks. Java fallback is the answer to every one of
them.

## 9. Cache, reuse and update policy

```text
<server home>/
└── native/
    ├── linux-aarch64/
    │   ├── libveltis_native.so
    │   └── veltis-native-install.json     what was installed, its digest and version
    └── manifests/
        ├── native-manifest.json           the last document that verified
        └── native-manifest.json.sig
```

| `veltis.native.updatePolicy` | Cached verified library | No cached library |
| --- | --- | --- |
| `never` | reused, zero requests | `NO_NETWORK`, zero requests |
| `if-missing` (default) | reused, zero requests | manifest + signature + one archive |
| `always` | manifest re-read; library replaced only if the published bytes differ | as `if-missing` |

`veltis.native.offline=true` never touches the network; with a verified
installation it reports `REUSED`, without one it reports `OFFLINE`. An
`installedAt` record makes a newer-than-published local version impossible to
downgrade: `decide` refuses to replace a library whose native version is ahead
of the manifest's.

## 10. Configuration

Read from `<home>/server.properties`, then overlaid by any `-Dveltis.native.*`
system property, exactly like the loader's own settings — so one run can be
steered without editing the file.

| Property | Default | Meaning |
| --- | --- | --- |
| `veltis.native.autoDownload` | `true` | the only switch that makes a network request possible; `false` means the server never fetches anything |
| `veltis.native.offline` | `false` | hard "no network for this run", reuse only |
| `veltis.native.updatePolicy` | `if-missing` | `never`, `if-missing`, `always` |
| `veltis.native.downloadTimeout` | `30` | seconds, per request, both connect and read |
| `veltis.native.repository` | empty → the official release line | an explicit mirror; empty means [`DEFAULT_REPOSITORY`](../src/main/java/org/veltismc/nativeengine/NativeDistributionConfig.java#L68), a versioned native tag rather than `releases/latest` |

An unparseable value is logged and replaced by its default: a typo in a
properties file must not stop a server from starting, and must not silently
*enable* something either — `autoDownload` only becomes true when it is spelled
`true`.

## 11. Versioning and compatibility

Three independent dimensions, deliberately not tied together:

| Dimension | Owned by | Changes when |
| --- | --- | --- |
| server version | Gradle project version | VeltisMC changes |
| native version | the CMake `VELTIS_NATIVE_VERSION` the artifact reports | the implementation changes |
| ABI version | `include/veltis_native.h` | the public contract changes |

A native release can move without a server release as long as the ABI rule
holds; the manifest pins the tag (`veltis-native-v1.0.0`) and declares
`abiVersion`, and the loader negotiates version-exact plus engine-major-exact
with minor `>=` minimum ([ABI_CONTRACT.md](ABI_CONTRACT.md)). The runtime never
chases "latest": an incompatible manifest is refused rather than downloaded in
the hope that the library turns out usable.

## 12. CI to runtime

```
source -> native-host matrix (9 lanes)
       -> per-lane: CTest, export check, Java FFM suite against the built library,
          artifact + runtime-policy verification, packaging with digests
       -> native-package (downloads only verified lanes' artifacts,
          assembles native-manifest.json, signs, reads it back through the runtime parser)
       -> native-release (refuses unsigned; publishes zips + manifest + .sig as release assets)
          ^ push/dispatch only — this hop has never executed, see §13.1
       -> server startup: NativeDistributionManager -> NativeLoader -> NativeEngine
```

Mandatory platform missing ⇒ packaging fails. Optional platform missing ⇒ the
manifest records it as skipped and it is absent from `artifacts`, so a server on
that platform gets `PLATFORM_ABSENT` and the skip line rather than a broken
download. A lane that built but could not execute its tests produces a
`verification: "cross"` entry, and cross entries are never installed.

## 13. What has been executed on this host (2026-11-05)

| Evidence | Result |
| --- | --- |
| `:veltis-native:test -PveltisNativeTest=true` | 131 tests, 0 failures, 1 tolerated skip |
| [`NativeDistributionRealArtifactTest`](../src/test/java/org/veltismc/nativeengine/NativeDistributionRealArtifactTest.java) | **Executed**: the DLL this build linked was packaged, published through a two-platform signed manifest, installed after exactly three requests (manifest, signature, own archive), then loaded through FFM, initialized, self-tested, called and re-loaded on a simulated restart |
| Selective download, six platforms | [`NativeDistributionManagerTest`](../src/test/java/org/veltismc/nativeengine/NativeDistributionManagerTest.java) asserts the full request log per platform; a deliberate "download every artifact" mutation makes it and the real-artifact test fail, which is how the assertion was shown to be load-bearing |
| Key pairing | `generateNativeManifestKey` → `verifyNativeManifestKeyPairing` executed: fails against a stale anchor, succeeds against the current one; three guard failures (no path, path inside repo, missing secret) executed |
| `:veltis-native:verifyNativeRelease -PveltisNativeTest=true` | passed; report at `build/native/dist/verification-report-windows-x86_64.json` |
| `:veltis-native:packageNativeDistribution` | **fails here, correctly**: `linux-x86_64` and `linux-aarch64` are mandatory and no Windows host can produce them. Manifest assembly has therefore never run end to end locally — it has now run on a runner, see §13.1 |
| GitHub Actions | **Executed on 2026-11-05** — [run 90](https://github.com/VeltisMC/Veltis/actions/runs/42006900852) ran `Package native distribution` to success on `ubuntu-latest`. Nothing in the rows above this one was executed by that job; the split is §13 versus §13.1, and this host's evidence stays this host's. |

### 13.1 What run 90 executed (GitHub runners, 2026-11-05)

The distribution feature's CI half ran for the first time. Six release-flagged
lanes built on their own platform runners, `native-package` downloaded those
verified artifacts, and the packaging step assembled and uploaded the
distribution under its release names — the first real `native-manifest.json` and
`native-release.json` produced from real per-platform artifacts rather than from
this host's single platform. Also proven, in that order and only in that order:

- **the `env` gate works**: the key-pairing step skipped while the rest of the job
  ran, so the condition neither failed the job nor silently disabled the steps
  after it. The reason it skipped is specific and worth stating exactly: the
  private half is still only on this host, so `VELTIS_NATIVE_MANIFEST_KEY` is
  **not configured in the repository yet**. Once it is, a same-repository pull
  request will run the pairing check (a fork's pull request still will not —
  GitHub does not pass secrets to those), which is the intended behavior; the
  skip in run 90 is an unconfigured secret, not a policy that pull requests are
  forever unsigned.
- **unsigned is recorded as unsigned**: the signing step emitted
  `signed=false` with a warning and the descriptor said so, instead of producing
  a manifest that looks publishable;
- **the release-lane set is host-only**: `native-package` needs `native-host`
  and collects `veltis-native-release-*`, while the cross lanes upload
  `veltis-native-cross-*`. Packaging therefore never saw a cross artifact in
  run 90, which matches the task itself — it writes `"verification" to "host"`
  ([build.gradle.kts:2251](../build.gradle.kts#L2251)) and has no path that
  writes `"cross"`. The `verification: "cross"` rule on the runtime side is a
  *rejection* rule, exercised by unit tests, for a manifest a hand-built or
  future release could carry; run 90 is not evidence that a cross entry survives
  packaging, because no cross entry was ever packaged.

Not proven there, and not to be quoted as if it were: `Publish native release` is
push/dispatch-only, so its conclusion in run 90 is *skipped* and the step that
refuses to publish an unsigned manifest (gradle.yml:1138) has still never
executed anywhere — not on a runner, and not on this host, because its input is
the packaging job's artifact. No GitHub Release was created, and no server has
downloaded from one. See `CI_SUPPORT_MATRIX.md` §1.

## 14. Honest limits

- The local key pair was generated by the new task on 2026-11-05 and the private
  half exists only on this host at `~/.veltis-native/manifest-signing-key.pem`.
  Until the repository owner puts it in `VELTIS_NATIVE_MANIFEST_KEY`, releases
  stay unsigned. Run 90 showed that state from the inside: the packaging job ran
  without the secret, wrote an unsigned manifest and said so — the design holds,
  and the *enforcement* half (the `native-release` refusal) has still never
  executed, because that job is push/dispatch-only. Consequence for this
  document's other claims: the runtime path has never fetched from a real GitHub
  release, and nothing in §13.1 should be read as if it had.
- Only `windows-x86_64` artifacts exist on this host. The Linux lanes that make
  packaging succeed have never run *here*, and since run 90 they have run on
  GitHub runners — that is what let the packaging job complete. This host is
  still, structurally, unable to produce a distribution on its own.
- Signature verification in the tests uses a per-run key passed to the
  package-private overload — the embedded anchor's own pairing is proven by the
  Gradle tasks and the packaging read-back, not by the test suite.
- No proxy, mTLS or enterprise-mirror story exists; `veltis.native.repository`
  is the only redirection an operator gets, and it is added to the allowlist by
  its own host.
- Cleanup is bounded: a `*.part` file from a crashed run is overwritten by the
  next attempt for the same platform, and nothing sweeps another platform's
  abandoned parts.

## 15. Troubleshooting

| Outcome | What it means | What to do |
| --- | --- | --- |
| `UNSUPPORTED_PLATFORM` | this OS/architecture is outside the six keys | nothing; the loader would refuse the library anyway |
| `PLATFORM_ABSENT` | the release published no verified artifact for this platform (or only a cross one) | wait for the lane, or accept the Java fallback |
| `FETCH_FAILED` | manifest unreachable **or** signature invalid | check connectivity first; if the repository is reachable, treat a signature refusal as a release-integrity problem |
| `DOWNLOAD_FAILED` | archive missing, oversized or digest-mismatched | re-publish; never copy a file in by hand and expect it trusted |
| `INSTALL_FAILED` | unpack, allowlist or binary probe refused | report it: a verified manifest plus a refused archive means the artifact is wrong |
| `LOCKED` | another process is installing | it will finish; this run continues on Java |
| `INVALID_REPOSITORY` | the configured URL is not HTTPS or has no host | fix `veltis.native.repository` |
| `NO_NETWORK` / `OFFLINE` | policy forbids fetching and nothing is installed | install once with network, or accept the fallback |
| `DISABLED` | `autoDownload=false`, `veltis.native.enabled=false`, or a pinned `veltis.native.path` | intended state; a pinned path deliberately means "never download" |
