package org.veltismc.nativeengine;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;

/**
 * The selective native distribution: fetch <em>one</em> verified artifact for
 * the platform this JVM actually runs on, install it under
 * {@code <home>/native/<platform>}, and never touch any other platform's
 * binary.
 *
 * <p>The whole check is one decision per server start, in this order:
 * <ol>
 *   <li><b>Is distribution active?</b> {@code veltis.native.enabled},
 *       {@code veltis.native.autoDownload} and an unpinned
 *       {@code veltis.native.path} all have to say yes; otherwise the result
 *       is {@link Outcome#DISABLED} and no request is made.</li>
 *   <li><b>Is this platform one of the six?</b> The key comes from
 *       {@link NativeLoader#platformKey()}, which detects OS and architecture
 *       independently, so a 64-bit JVM on ARM reports {@code linux-aarch64}
 *       even on an x86-64-named user agent — the architecture is never
 *       assumed from the operating system.</li>
 *   <li><b>Is a verified library already installed?</b> With the default
 *       {@code if-missing} policy a warm installation performs zero requests,
 *       which is what keeps restarts cheap and makes
 *       {@code updatePolicy=never} / {@code offline=true} honest.</li>
 *   <li><b>Read the signed manifest</b> (cache first when the policy allows),
 *       verify its Ed25519 signature against the embedded key, and look up
 *       <em>this</em> platform's entry. A release that skipped this platform
 *       has no entry — that is the graceful-skip path, not an error.</li>
 *   <li><b>Download that one archive</b>, check its digest against the signed
 *       manifest, unpack it through the allowlist in
 *       {@link NativeDistributionInstaller}, probe the binary, and move it
 *       into place atomically.</li>
 * </ol>
 *
 * <p>Nothing in this class can prevent a server from starting. Every failure
 * is a {@link Result} with a reason; the bootstrap logs the reason and the
 * server continues on its Java implementation. Downloads are attempted once —
 * there is no retry loop — and a second process that finds the lock held
 * reports {@link Outcome#LOCKED} instead of downloading alongside the first.
 */
public final class NativeDistributionManager {

    private static final System.Logger LOG = System.getLogger(NativeDistributionManager.class.getName());

    /** Where the manifest cache lives, under the server home. */
    private static final String MANIFEST_DIRECTORY = "manifests";

    private NativeDistributionManager() {}

    /** What one distribution check decided. */
    public enum Outcome {
        /** Not active: a switch is off or a library path is pinned. */
        DISABLED,
        /** A verified library is already in place; nothing was downloaded. */
        REUSED,
        /** The published artifact was downloaded, verified and installed. */
        INSTALLED,
        /** This OS/architecture is outside the six keys the loader supports. */
        UNSUPPORTED_PLATFORM,
        /** The release publishes no verified artifact for this platform. */
        PLATFORM_ABSENT,
        /** The configured repository URL is not acceptable. */
        INVALID_REPOSITORY,
        /** The update policy forbids network use and nothing is installed. */
        NO_NETWORK,
        /** Offline mode and nothing usable is installed. */
        OFFLINE,
        /** The manifest could not be fetched or its signature verified. */
        FETCH_FAILED,
        /** The archive could not be downloaded. */
        DOWNLOAD_FAILED,
        /** The archive arrived but did not verify, unpack or probe. */
        INSTALL_FAILED,
        /** Another process holds the distribution lock. */
        LOCKED
    }

    /**
     * The outcome of the check, the platform it was made for, and a
     * human-readable reason. Never null, never blank.
     */
    public record Result(Outcome outcome, String platform, String detail) {

        /**
         * Whether the bootstrap should tell the operator that native
         * acceleration is unavailable and the server is continuing on Java.
         * A disabled distribution says nothing here: the ordinary
         * "native support unavailable" line already covers it.
         */
        public boolean shouldReportSkip() {
            return switch (outcome) {
                case DISABLED, REUSED, INSTALLED -> false;
                default -> true;
            };
        }

        public boolean installed() {
            return outcome == Outcome.INSTALLED;
        }
    }

    /**
     * Ensures the current platform's library is installed under
     * {@code <home>/native/<platform>}, if distribution is active and the
     * release publishes one.
     *
     * <p>Never throws: a distribution problem is reported as a {@link Result}
     * so the caller can log it and start on the Java implementation anyway.
     */
    public static Result ensureInstalled(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution) {
        final PublicKey trustedKey;
        try {
            trustedKey = NativeManifest.trustedPublicKey();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "The embedded manifest key could not be loaded; native distribution is unavailable", e);
            return new Result(Outcome.FETCH_FAILED, NativeLoader.platformKey(),
                    "the embedded manifest public key could not be loaded");
        }
        return ensureInstalled(home, nativeConfig, distribution, trustedKey);
    }

    /** Test seam: the same check against a caller-supplied trust anchor. */
    static Result ensureInstalled(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution, PublicKey trustedKey) {
        return ensureInstalled(home, nativeConfig, distribution, trustedKey, NativeLoader.platformKey());
    }

    /**
     * Test seam: the same check for an explicit platform key, so the six-key
     * selection matrix can be exercised without pretending this JVM runs on
     * six operating systems.
     */
    static Result ensureInstalled(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution, PublicKey trustedKey, String platform) {
        final Result result;
        try {
            result = run(home, nativeConfig, distribution, trustedKey, platform);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING,
                    "Native distribution check failed; continuing without a downloaded library", t);
            return new Result(Outcome.FETCH_FAILED, platform, describe(t));
        }
        LOG.log(System.Logger.Level.DEBUG, "Native distribution for " + platform + ": "
                + result.outcome() + " (" + result.detail() + ")");
        return result;
    }

    private static Result run(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution, PublicKey trustedKey, String platform) {

        if (!nativeConfig.enabled()) {
            return new Result(Outcome.DISABLED, platform, NativeConfig.ENABLED_PROPERTY + " is false");
        }
        if (nativeConfig.hasLibraryPath()) {
            return new Result(Outcome.DISABLED, platform,
                    NativeConfig.PATH_PROPERTY + " names a library, so the distribution leaves it alone");
        }
        if (!distribution.autoDownload()) {
            return new Result(Outcome.DISABLED, platform,
                    NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY + " is false");
        }
        if (!NativeLoader.isRecognizedPlatform(platform)) {
            return new Result(Outcome.UNSUPPORTED_PLATFORM, platform,
                    platform + " is not one of the platforms this server builds for: "
                            + NativeLoader.recognizedPlatformList());
        }

        var installDir = home.resolve("native").resolve(platform);
        var installed = NativeDistributionInstaller.validate(installDir, platform);

        // The default policy is satisfied by a verified local library: no
        // manifest, no archive, no request.
        if (installed.valid() && !distribution.policyRequiresRefresh()) {
            return new Result(Outcome.REUSED, platform, "a verified library is already installed");
        }
        if (distribution.offline()) {
            return installed.valid()
                    ? new Result(Outcome.REUSED, platform, "offline; keeping the verified local library")
                    : new Result(Outcome.OFFLINE, platform,
                            "offline and no verified library is installed");
        }
        if (!distribution.policyAllowsNetwork()) {
            // A valid installation was already handled above; reaching here
            // with nothing installed is exactly what "never" means.
            return new Result(Outcome.NO_NETWORK, platform,
                    "update policy 'never' and no verified library is installed");
        }

        var repository = distribution.repository().isEmpty()
                ? NativeDistributionConfig.DEFAULT_REPOSITORY
                : distribution.repository();
        final NativeDistributionTransport transport;
        try {
            transport = new NativeDistributionTransport(repository, distribution.downloadTimeoutSeconds());
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.INVALID_REPOSITORY, platform, describe(e));
        }

        try (transport) {
            final NativeDistributionInstaller.DistributionLock lock;
            try {
                lock = NativeDistributionInstaller.tryLock(home);
            } catch (IOException e) {
                return new Result(Outcome.FETCH_FAILED, platform,
                        "cannot take the distribution lock: " + describe(e));
            }
            if (lock == null) {
                return new Result(Outcome.LOCKED, platform,
                        "another Veltis process is installing native libraries");
            }
            try (lock) {
                return refresh(home, distribution, trustedKey, platform, installDir, installed, transport);
            }
        }
    }

    /** The manifest, the entry for this platform, and the single download. */
    private static Result refresh(Path home, NativeDistributionConfig distribution,
            PublicKey trustedKey, String platform, Path installDir,
            NativeDistributionInstaller.InstallState installed,
            NativeDistributionTransport transport) {

        final ManifestBytes source;
        try {
            source = obtainManifest(home, distribution, trustedKey, transport);
        } catch (IOException e) {
            return new Result(Outcome.FETCH_FAILED, platform, describe(e));
        }

        final NativeManifest manifest;
        try {
            manifest = NativeManifest.verifyAndParse(source.manifest(), source.signature(), trustedKey);
        } catch (IOException e) {
            return new Result(Outcome.FETCH_FAILED, platform, describe(e));
        }

        var entry = manifest.artifact(platform).orElse(null);
        if (entry == null) {
            // The graceful-skip path: an optional platform with no runner, no
            // build or no verified artifact is simply not in this release.
            return new Result(Outcome.PLATFORM_ABSENT, platform,
                    "the " + manifest.release() + " release does not publish an artifact for " + platform);
        }
        if (!entry.verified() || !"host".equals(entry.verification())) {
            return new Result(Outcome.PLATFORM_ABSENT, platform,
                    "the " + manifest.release() + " release marks " + platform
                            + " as '" + entry.verification() + "' and not runtime-verified");
        }

        var decision = decide(installDir, platform, entry, installed, distribution);
        if (decision != null) {
            return decision;
        }

        // One download, of this platform's archive, and nothing else.
        var staging = installDir.resolve(entry.library() + ".zip.part");
        try {
            Files.createDirectories(installDir);
            transport.download(entry.asset(), staging, entry.sizeBytes(), NativeManifest.MAX_ARCHIVE_BYTES);
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.DOWNLOAD_FAILED, platform, describe(e));
        }
        try {
            NativeDistributionInstaller.install(installDir, platform, staging, entry);
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.INSTALL_FAILED, platform, describe(e));
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // The staging file is inside the install directory and is
                // overwritten by the next attempt anyway.
            }
        }
        return new Result(Outcome.INSTALLED, platform,
                "installed " + entry.library() + " " + entry.nativeVersion()
                        + " from " + entry.asset());
    }

    /**
     * Decides whether anything needs to be downloaded, and returns the result
     * to report when it does not. Null means "download and install".
     */
    private static Result decide(Path installDir, String platform, NativeManifest.Artifact entry,
            NativeDistributionInstaller.InstallState installed, NativeDistributionConfig distribution) {
        var libraryFile = installDir.resolve(entry.library());
        try {
            if (!Files.isRegularFile(libraryFile)) {
                return null;
            }
            var currentSha = NativeDistributionInstaller.sha256(libraryFile);
            if (currentSha.equals(entry.librarySha256())) {
                if (!installed.valid()) {
                    // Present, published-identical, but unrecorded (a library
                    // an operator copied in): record it so later runs can
                    // verify it without downloading it again.
                    NativeDistributionInstaller.record(installDir, platform, entry, currentSha);
                    return new Result(Outcome.REUSED, platform,
                            "the installed library matches the published artifact and was recorded");
                }
                return new Result(Outcome.REUSED, platform,
                        "the installed library already matches the published artifact");
            }
            if (!installed.valid()) {
                // Not the published artifact and not a verifiable installation:
                // replace it, which is what auto-download opted in for.
                return null;
            }
            var comparison = compareVersions(installed.nativeVersion(), entry.nativeVersion());
            if (comparison == VERSION_INCOMPARABLE) {
                return new Result(Outcome.REUSED, platform,
                        "the installed version '" + installed.nativeVersion()
                                + "' cannot be compared with the published " + entry.nativeVersion()
                                + "; leaving the installed library in place");
            }
            if (comparison > 0) {
                return new Result(Outcome.REUSED, platform,
                        "installed version " + installed.nativeVersion() + " is newer than the published "
                                + entry.nativeVersion() + "; refusing to downgrade");
            }
            if (comparison == 0 && !distribution.policyRequiresRefresh()) {
                return new Result(Outcome.REUSED, platform,
                        "the published " + entry.nativeVersion() + " differs from the installed build"
                                + " of the same version; set " + NativeDistributionConfig.UPDATE_POLICY_PROPERTY
                                + "=" + NativeDistributionConfig.UPDATE_POLICY_ALWAYS + " to replace it");
            }
            // Older than the published version — or the same version under
            // `always`, which explicitly asks for the published build.
            return null;
        } catch (IOException e) {
            return new Result(Outcome.DOWNLOAD_FAILED, platform,
                    "cannot read the installed library: " + describe(e));
        }
    }

    /** The manifest bytes to use: cache when the policy allows, else network. */
    private static ManifestBytes obtainManifest(Path home, NativeDistributionConfig distribution,
            PublicKey trustedKey, NativeDistributionTransport transport) throws IOException {
        var manifestFile = home.resolve("native").resolve(MANIFEST_DIRECTORY)
                .resolve(NativeManifest.MANIFEST_ASSET);
        var signatureFile = home.resolve("native").resolve(MANIFEST_DIRECTORY)
                .resolve(NativeManifest.SIGNATURE_ASSET);

        // A signature-valid cache answers `if-missing` with no request at all.
        if (!distribution.policyRequiresRefresh()) {
            var cached = readCache(manifestFile, signatureFile, trustedKey);
            if (cached != null) {
                return cached;
            }
        }

        try {
            var manifestBytes = transport.fetch(NativeManifest.MANIFEST_ASSET,
                    NativeManifest.MAX_MANIFEST_BYTES);
            var signatureBytes = transport.fetch(NativeManifest.SIGNATURE_ASSET,
                    NativeManifest.MAX_MANIFEST_BYTES);
            // Verify before caching: a cache that failed verification would
            // otherwise be reused on the next start with no way to tell.
            NativeManifest.verifyAndParse(manifestBytes, signatureBytes, trustedKey);
            try {
                writeCache(manifestFile, signatureFile, manifestBytes, signatureBytes);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "The manifest was fetched but could not be cached", e);
            }
            return new ManifestBytes(manifestBytes, signatureBytes);
        } catch (IOException fetchProblem) {
            var cached = readCache(manifestFile, signatureFile, trustedKey);
            if (cached != null) {
                LOG.log(System.Logger.Level.DEBUG, "Using the cached native manifest because the"
                        + " refresh failed: " + fetchProblem.getMessage());
                return cached;
            }
            throw new IOException("the native manifest could not be fetched or verified: "
                    + fetchProblem.getMessage(), fetchProblem);
        }
    }

    private record ManifestBytes(byte[] manifest, byte[] signature) {}

    /** The cached manifest, but only if its signature still verifies. */
    private static ManifestBytes readCache(Path manifestFile, Path signatureFile, PublicKey trustedKey) {
        try {
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(signatureFile)) {
                return null;
            }
            var manifestBytes = Files.readAllBytes(manifestFile);
            var signatureBytes = Files.readAllBytes(signatureFile);
            NativeManifest.verifyAndParse(manifestBytes, signatureBytes, trustedKey);
            return new ManifestBytes(manifestBytes, signatureBytes);
        } catch (IOException | RuntimeException e) {
            // Missing, oversized, tampered with or malformed: all three mean
            // "no usable cache", which the caller answers with a fresh fetch.
            return null;
        }
    }

    private static void writeCache(Path manifestFile, Path signatureFile,
            byte[] manifestBytes, byte[] signatureBytes) throws IOException {
        Files.createDirectories(manifestFile.getParent());
        writeAtomically(manifestFile, manifestBytes);
        writeAtomically(signatureFile, signatureBytes);
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        var staging = target.resolveSibling(target.getFileName() + ".part");
        Files.write(staging, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(target);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static final int VERSION_INCOMPARABLE = Integer.MIN_VALUE;

    /**
     * Numeric comparison of {@code major[.minor[.patch[.build]]]} versions, or
     * {@link #VERSION_INCOMPARABLE} when either side is not one. Missing
     * components count as zero, so {@code 1.0} and {@code 1.0.0} are equal.
     */
    static int compareVersions(String left, String right) {
        var leftParts = splitVersion(left);
        var rightParts = splitVersion(right);
        if (leftParts == null || rightParts == null) {
            return VERSION_INCOMPARABLE;
        }
        for (int i = 0; i < leftParts.length; i++) {
            if (leftParts[i] != rightParts[i]) {
                return Integer.compare(leftParts[i], rightParts[i]);
            }
        }
        return 0;
    }

    private static int[] splitVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        var parts = version.trim().split("\\.");
        if (parts.length > 4) {
            return null;
        }
        var values = new int[4];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (values[i] < 0) {
                return null;
            }
        }
        return values;
    }

    private static String describe(Throwable t) {
        var message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
