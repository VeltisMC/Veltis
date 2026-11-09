package org.veltismc.nativeengine;

import java.nio.file.Path;

/**
 * The commit-pinned native distribution: fetch <em>one</em> verified artifact
 * for the platform this JVM actually runs on, install it under
 * {@code <home>/native/<platform>}, and never touch any other platform's
 * binary.
 *
 * <p>Every download is resolved against the release published for the running
 * server's own Git commit (see {@link NativeCommitDistributionManager}), so a
 * server never installs a library built from a different tree. The decision is
 * one per server start, in this order:
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
 *   <li><b>Read the release manifest</b> for this commit (cache first when the
 *       policy allows) and look up <em>this</em> platform's entry. The manifest
 *       is fetched over the transport's trusted HTTPS GitHub source; a release
 *       that skipped this platform has no entry — that is the graceful-skip
 *       path, not an error.</li>
 *   <li><b>Download that one archive</b>, check its SHA-256 digest against the
 *       manifest and its size against the GitHub release metadata, and install
 *       it through {@link NativeCommitInstaller}.</li>
 * </ol>
 *
 * <p>Nothing in this class can prevent a server from starting. Every failure
 * is a {@link Result} with a reason; the bootstrap logs the reason and the
 * server continues on its Java implementation. Downloads are attempted once —
 * there is no retry loop — and a second process that finds the lock held
 * reports {@link Outcome#LOCKED} instead of downloading alongside the first.
 */
public final class NativeDistributionManager {

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
        /** The manifest could not be fetched or did not match this commit. */
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
    public record Result(Outcome outcome, String platform, String detail, java.util.List<String> modules) {
        public Result(Outcome outcome, String platform, String detail) {
            this(outcome, platform, detail, java.util.List.of());
        }

        public Result {
            modules = java.util.List.copyOf(modules);
        }

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
     * release named by {@code identity} publishes one.
     *
     * <p>Never throws: a distribution problem is reported as a {@link Result}
     * so the caller can log it once and start on the Java implementation
     * anyway. This method deliberately logs nothing itself; {@code
     * VeltisBootstrap} owns the single operator-facing diagnostic.
     */
    public static Result ensureInstalled(Path home, NativeConfig nativeConfig,
            NativeDistributionConfig distribution, NativeBuildMetadata.Identity identity) {
        var result = NativeCommitDistributionManager.ensureInstalled(home, nativeConfig,
                distribution, identity, NativeLoader.platformKey());
        return new Result(result.outcome(), result.platform(), result.detail(), result.modules());
    }
}
