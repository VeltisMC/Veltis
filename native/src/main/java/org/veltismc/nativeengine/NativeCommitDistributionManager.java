package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves and installs only the signed native release built for this server commit. */
final class NativeCommitDistributionManager {

    private static final long MAX_RELEASE_METADATA_BYTES = 2L * 1024L * 1024L;
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    record Result(NativeDistributionManager.Outcome outcome, String platform,
            String detail, List<String> modules) {
        Result {
            modules = List.copyOf(modules);
        }
    }

    private record ReleaseAsset(String name, URI browserDownloadUrl, long size) {}

    private NativeCommitDistributionManager() {}

    static Result ensureInstalled(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution, PublicKey trustedKey, String commit, String platform) {
        if (!nativeConfig.enabled() || nativeConfig.hasLibraryPath() || !distribution.autoDownload()) {
            return new Result(NativeDistributionManager.Outcome.DISABLED, platform,
                    "native distribution is disabled or an explicit library path is configured", List.of());
        }
        if (!NativeLoader.isRecognizedPlatform(platform)) {
            return new Result(NativeDistributionManager.Outcome.UNSUPPORTED_PLATFORM, platform,
                    platform + " is not a supported native platform", List.of());
        }
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            return new Result(NativeDistributionManager.Outcome.PLATFORM_ABSENT, platform,
                    "the server build has no valid full Git commit; native downloads are disabled", List.of());
        }

        var shortCommit = commit.substring(0, 7);
        var tag = "veltis-native-" + shortCommit;
        var installDir = home.resolve("native").resolve(platform);
        var cacheDir = home.resolve("cache").resolve("native");
        var manifestDir = cacheDir.resolve("manifests").resolve(commit);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            return failed(NativeDistributionManager.Outcome.FETCH_FAILED, platform,
                    "cannot create the native cache: " + describe(e));
        }

        try (var lock = tryLock(cacheDir.resolve("." + platform + ".lock"))) {
            if (lock == null) {
                return failed(NativeDistributionManager.Outcome.LOCKED, platform,
                        "another server process is installing native modules for " + platform);
            }

            var manifestBytes = readManifestCache(manifestDir, trustedKey, commit, tag);
            var signatureBytes = readSignatureCache(manifestDir);
            NativeCommitManifest manifest = null;
            if (manifestBytes != null && signatureBytes != null) {
                try {
                    manifest = NativeCommitManifest.verifyAndParse(manifestBytes, signatureBytes, trustedKey);
                } catch (IOException ignored) {
                    manifest = null;
                }
            }

            var artifact = manifest == null ? null : manifest.platform(platform);
            if (artifact != null && artifact.verified()) {
                var installed = NativeCommitInstaller.validate(installDir, commit, artifact);
                if (installed.valid() && !distribution.policyRequiresRefresh()) {
                    return new Result(NativeDistributionManager.Outcome.REUSED, platform,
                            installed.detail(), artifact.modules().stream()
                                    .map(NativeCommitManifest.Module::name).toList());
                }
            }

            if (distribution.offline() || !distribution.policyAllowsNetwork()) {
                var cachedArchive = artifact == null ? null : cacheDir.resolve(artifact.asset());
                if (artifact != null && artifact.verified()
                        && validCachedArchive(cachedArchive, artifact)) {
                    try {
                        NativeCommitInstaller.install(installDir, cachedArchive, commit, artifact);
                        return new Result(NativeDistributionManager.Outcome.INSTALLED, platform,
                                "installed the verified cached archive for commit " + shortCommit,
                                artifact.modules().stream().map(NativeCommitManifest.Module::name).toList());
                    } catch (IOException | RuntimeException e) {
                        return failed(NativeDistributionManager.Outcome.INSTALL_FAILED, platform,
                                "the cached native archive was rejected: " + describe(e));
                    }
                }
                return failed(distribution.offline() ? NativeDistributionManager.Outcome.OFFLINE
                                : NativeDistributionManager.Outcome.NO_NETWORK,
                        platform, "no verified installation for commit " + shortCommit + " is available offline");
            }

            final Map<String, ReleaseAsset> releaseAssets;
            final URI releaseApiUrl;
            try (var transport = new NativeDistributionTransport(
                    repository(distribution), distribution.downloadTimeoutSeconds())) {
                releaseApiUrl = NativeDistributionTransport.resolve(
                        URI.create(transport.repository()), "tags/" + tag);
                var releaseJson = JsonParser.parseString(
                        new String(transport.fetch(releaseApiUrl, MAX_RELEASE_METADATA_BYTES),
                                java.nio.charset.StandardCharsets.UTF_8));
                if (!releaseJson.isJsonObject()) {
                    throw new IOException("GitHub release metadata is not a JSON object");
                }
                var release = releaseJson.getAsJsonObject();
                if (!tag.equals(string(release, "tag_name"))) {
                    throw new IOException("GitHub returned a release with a different tag");
                }
                releaseAssets = parseReleaseAssets(release);

                var manifestAsset = requireAsset(releaseAssets, NativeManifest.MANIFEST_ASSET);
                var signatureAsset = requireAsset(releaseAssets, NativeManifest.SIGNATURE_ASSET);
                manifestBytes = transport.fetch(manifestAsset.browserDownloadUrl(),
                        NativeManifest.MAX_MANIFEST_BYTES);
                signatureBytes = transport.fetch(signatureAsset.browserDownloadUrl(),
                        NativeManifest.MAX_MANIFEST_BYTES);
                manifest = NativeCommitManifest.verifyAndParse(manifestBytes, signatureBytes, trustedKey);
                if (!commit.equals(manifest.commit()) || !tag.equals(manifest.releaseTag())) {
                    throw new IOException("signed manifest is for commit " + manifest.commit()
                            + " and tag " + manifest.releaseTag() + ", not " + commit + " / " + tag);
                }
                writeManifestCache(manifestDir, manifestBytes, signatureBytes);
            } catch (IOException | RuntimeException e) {
                var missingRelease = e.getMessage() != null && e.getMessage().contains("HTTP 404");
                return failed(missingRelease ? NativeDistributionManager.Outcome.PLATFORM_ABSENT
                                : NativeDistributionManager.Outcome.FETCH_FAILED,
                        platform, missingRelease
                                ? "no native release exists for exact server commit " + shortCommit
                                : "could not resolve the signed native release for commit " + shortCommit
                                        + ": " + describe(e));
            }

            artifact = manifest.platform(platform);
            if (artifact == null) {
                return failed(NativeDistributionManager.Outcome.PLATFORM_ABSENT, platform,
                        "the signed release for commit " + shortCommit + " has no " + platform + " artifact");
            }
            if (!artifact.verified()) {
                return failed(NativeDistributionManager.Outcome.PLATFORM_ABSENT, platform,
                        platform + " was not runtime-verified for commit " + shortCommit);
            }
            var archiveAsset = releaseAssets.get(artifact.asset());
            if (archiveAsset == null || !archiveAsset.browserDownloadUrl().equals(artifact.downloadUrl())
                    || archiveAsset.size() != artifact.sizeBytes()) {
                return failed(NativeDistributionManager.Outcome.FETCH_FAILED, platform,
                        "the signed artifact URL or size does not match GitHub Release metadata");
            }

            var archive = cacheDir.resolve(artifact.asset());
            if (!validCachedArchive(archive, artifact)) {
                try (var transport = new NativeDistributionTransport(
                        repository(distribution), distribution.downloadTimeoutSeconds())) {
                    var partial = archive.resolveSibling(archive.getFileName() + ".part");
                    try {
                        transport.download(artifact.downloadUrl(), partial,
                                artifact.sizeBytes(), NativeManifest.MAX_ARCHIVE_BYTES);
                        if (!sha256(partial).equals(artifact.sha256())) {
                            throw new IOException("downloaded ZIP checksum does not match signed metadata");
                        }
                        moveAtomically(partial, archive);
                    } catch (IOException | RuntimeException e) {
                        Files.deleteIfExists(partial);
                        return failed(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, platform,
                                "could not download " + artifact.asset() + ": " + describe(e));
                    }
                } catch (IOException e) {
                    return failed(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, platform, describe(e));
                }
            }

            try {
                NativeCommitInstaller.install(installDir, archive, commit, artifact);
            } catch (IOException | RuntimeException e) {
                return failed(NativeDistributionManager.Outcome.INSTALL_FAILED, platform,
                        "could not install " + artifact.asset() + ": " + describe(e));
            }
            return new Result(NativeDistributionManager.Outcome.INSTALLED, platform,
                    "installed " + artifact.modules().size() + " module(s) for commit " + shortCommit,
                    artifact.modules().stream().map(NativeCommitManifest.Module::name).toList());
        } catch (IOException e) {
            return failed(NativeDistributionManager.Outcome.FETCH_FAILED, platform,
                    "native release lock failed: " + describe(e));
        }
    }

    private static String repository(NativeDistributionConfig distribution) {
        return distribution.repository().isEmpty()
                ? NativeDistributionConfig.DEFAULT_REPOSITORY
                : distribution.repository();
    }

    private static Map<String, ReleaseAsset> parseReleaseAssets(JsonObject release) throws IOException {
        if (!release.has("assets") || !release.get("assets").isJsonArray()) {
            throw new IOException("GitHub release metadata has no assets array");
        }
        var result = new HashMap<String, ReleaseAsset>();
        for (var value : release.getAsJsonArray("assets")) {
            if (!value.isJsonObject()) {
                throw new IOException("GitHub release metadata contains an invalid asset");
            }
            var object = value.getAsJsonObject();
            var name = string(object, "name");
            var url = URI.create(string(object, "browser_download_url"));
            var loopback = url.getHost() != null
                    && NativeDistributionTransport.isLoopbackAddress(url.getHost());
            var secure = "https".equalsIgnoreCase(url.getScheme());
            var localHttp = loopback && "http".equalsIgnoreCase(url.getScheme());
            if ((!secure && !localHttp)
                    || url.getHost() == null
                    || (!loopback && !url.getHost().equalsIgnoreCase("github.com")
                            && !url.getHost().toLowerCase(java.util.Locale.ROOT)
                                    .endsWith(".githubusercontent.com"))) {
                throw new IOException("release asset " + name + " has an untrusted download URL");
            }
            long size;
            try {
                size = object.get("size").getAsLong();
            } catch (RuntimeException e) {
                throw new IOException("release asset " + name + " has no valid size", e);
            }
            if (result.putIfAbsent(name, new ReleaseAsset(name, url, size)) != null) {
                throw new IOException("GitHub release metadata repeats asset " + name);
            }
        }
        return Map.copyOf(result);
    }

    private static ReleaseAsset requireAsset(Map<String, ReleaseAsset> assets, String name) throws IOException {
        var asset = assets.get(name);
        if (asset == null) {
            throw new IOException("GitHub release is missing " + name);
        }
        return asset;
    }

    private static String string(JsonObject object, String field) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()
                || object.get(field).getAsString().isBlank()) {
            throw new IOException("release metadata is missing " + field);
        }
        return object.get(field).getAsString();
    }

    private static byte[] readManifestCache(Path directory, PublicKey trustedKey, String commit, String tag) {
        var manifest = directory.resolve(NativeManifest.MANIFEST_ASSET);
        var signature = directory.resolve(NativeManifest.SIGNATURE_ASSET);
        try {
            if (!Files.isRegularFile(manifest) || !Files.isRegularFile(signature)
                    || Files.size(manifest) > NativeManifest.MAX_MANIFEST_BYTES
                    || Files.size(signature) > NativeManifest.MAX_MANIFEST_BYTES) {
                return null;
            }
            var bytes = Files.readAllBytes(manifest);
            var signed = Files.readAllBytes(signature);
            var parsed = NativeCommitManifest.verifyAndParse(bytes, signed, trustedKey);
            return commit.equals(parsed.commit()) && tag.equals(parsed.releaseTag()) ? bytes : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] readSignatureCache(Path directory) {
        try {
            var path = directory.resolve(NativeManifest.SIGNATURE_ASSET);
            return Files.isRegularFile(path) && Files.size(path) <= NativeManifest.MAX_MANIFEST_BYTES
                    ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeManifestCache(Path directory, byte[] manifest, byte[] signature) throws IOException {
        Files.createDirectories(directory);
        writeAtomically(directory.resolve(NativeManifest.MANIFEST_ASSET), manifest);
        writeAtomically(directory.resolve(NativeManifest.SIGNATURE_ASSET), signature);
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        var partial = target.resolveSibling(target.getFileName() + ".part");
        Files.write(partial, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        moveAtomically(partial, target);
    }

    private static boolean validCachedArchive(Path path, NativeCommitManifest.PlatformArtifact artifact) {
        try {
            return Files.isRegularFile(path) && Files.size(path) == artifact.sizeBytes()
                    && sha256(path).equals(artifact.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static FileLock tryLock(Path path) throws IOException {
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new ChannelLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private static final class ChannelLock extends FileLock {
        private final FileChannel channel;
        private final FileLock delegate;

        ChannelLock(FileChannel channel, FileLock delegate) {
            super(channel, delegate.position(), delegate.size(), false);
            this.channel = channel;
            this.delegate = delegate;
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public void release() throws IOException {
            try {
                delegate.release();
            } finally {
                channel.close();
            }
        }
    }

    private static Result failed(NativeDistributionManager.Outcome outcome, String platform, String detail) {
        return new Result(outcome, platform, detail, List.of());
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
