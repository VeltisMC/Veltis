package org.veltismc.veltis.runtime.provision;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class MinecraftRuntimeProvisioner {

    private static final Logger LOG = System.getLogger(MinecraftRuntimeProvisioner.class.getName());
    private static final long MB = 1_000_000L;

    private final Path serverDirectory;
    private final MojangRuntimeResolver resolver;
    private int lastReportedPercent = -1;
    private long startTime;

    public MinecraftRuntimeProvisioner(Path serverDirectory) {
        this.serverDirectory = Objects.requireNonNull(serverDirectory, "serverDirectory");
        this.resolver = new MojangRuntimeResolver();
    }

    public MinecraftRuntimeProvisioner(Path serverDirectory, MojangRuntimeResolver resolver) {
        this.serverDirectory = Objects.requireNonNull(serverDirectory, "serverDirectory");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public ProvisioningResult provision() {
        var installer = new RuntimeInstaller(runtimeDirectory());

        if (installer.isInstalled("26.2")) {
            var installedJar = installer.installedJar("26.2");
            var verifier = new RuntimeVerifier();
            long size;
            try { size = Files.size(installedJar); } catch (Exception _) { size = 0; }
            var result = verifier.verify(installedJar, "SHA-256", computeSha256(installedJar), size);
            if (result.valid()) {
                LOG.log(Level.INFO, "Minecraft 26.2 runtime found at {0}", installedJar);
                return ProvisioningResult.found(installedJar, false);
            }
            LOG.log(Level.WARNING, "Runtime corrupted, re-downloading: {0}",
                String.join("; ", result.errors()));
        } else {
            LOG.log(Level.INFO, "Minecraft 26.2 runtime not found");
        }

        var resolution = resolver.resolve("26.2");
        resolution.print(System.out);
        if (!resolution.success()) {
            LOG.log(Level.ERROR, "Cannot provision Minecraft 26.2: version not available from Mojang");
            return ProvisioningResult.failed("Version 26.2 not available from Mojang manifest");
        }

        var manifest = new MinecraftVersionManifest(
            resolution.version(),
            resolution.downloadUrl(),
            resolution.sha1(),
            resolution.fileSize(),
            Instant.now()
        );

        var tempJar = downloadRuntime(manifest);
        if (tempJar == null) {
            return ProvisioningResult.failed("Download failed");
        }

        if (!verifyRuntime(tempJar, manifest)) {
            return ProvisioningResult.failed("Verification failed");
        }

        return installRuntime(installer, tempJar, manifest);
    }

    private Path downloadRuntime(MinecraftVersionManifest manifest) {
        lastReportedPercent = -1;
        startTime = System.nanoTime();
        System.out.println("Downloading Minecraft runtime...");
        var downloadManager = new RuntimeDownloadManager(manifest);
        try {
            var path = downloadManager.download(this::logProgress);
            double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
            double totalMb = (double) manifest.fileSize() / (double) MB;
            System.out.printf("  Downloaded in %.1f seconds%n", elapsed);
            System.out.printf("  Average speed: %.1f MB/s%n", totalMb / elapsed);
            System.out.println("  [PASS] Runtime downloaded");
            return path;
        } catch (Exception e) {
            System.out.println("  [FAIL] Runtime download failed: " + e.getMessage());
            LOG.log(Level.ERROR, "Failed to download Minecraft runtime: {0}", e.getMessage());
            return null;
        }
    }

    private boolean verifyRuntime(Path jarPath, MinecraftVersionManifest manifest) {
        System.out.println("Verifying SHA-1 checksum...");
        var verifier = new RuntimeVerifier();
        var result = verifier.verify(jarPath, "SHA-1", manifest.sha256(), manifest.fileSize());
        if (!result.valid()) {
            System.out.println("  [FAIL] SHA-1 verification failed: " + String.join("; ", result.errors()));
            LOG.log(Level.ERROR, "Runtime verification failed: {0}",
                String.join("; ", result.errors()));
            try {
                Files.deleteIfExists(jarPath);
            } catch (Exception ignored) {
            }
            return false;
        }
        System.out.println("  [PASS] SHA-1 verified (" + result.actualHash() + ")");
        return true;
    }

    private ProvisioningResult installRuntime(RuntimeInstaller installer, Path tempJar,
                                               MinecraftVersionManifest manifest) {
        System.out.println("Installing runtime...");
        try {
            var installedJar = installer.install(tempJar, manifest);
            System.out.println("  [PASS] Runtime installed");
            LOG.log(Level.INFO, "Runtime installed at {0}", installedJar);
            return ProvisioningResult.found(installedJar, true);
        } catch (Exception e) {
            System.out.println("  [FAIL] Installation failed: " + e.getMessage());
            LOG.log(Level.ERROR, "Failed to install runtime: {0}", e.getMessage());
            try {
                Files.deleteIfExists(tempJar);
            } catch (Exception ignored) {
            }
            return ProvisioningResult.failed("Installation failed: " + e.getMessage());
        }
    }

    private void logProgress(long downloaded, long total) {
        if (total <= 0) return;
        var percent = (int) ((downloaded * 100) / total);
        if (percent == lastReportedPercent && downloaded != total) return;
        lastReportedPercent = percent;

        double elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0;
        double speedBps = elapsed > 0.0 ? (double) downloaded / elapsed : 0.0;
        long eta = speedBps > 0.0 ? (long) ((double) (total - downloaded) / speedBps) : 0L;
        long downloadedMb = downloaded / MB;
        long totalMb = total / MB;
        double speedMbps = speedBps / (double) MB;

        if (LOG.isLoggable(Level.DEBUG)) {
            LOG.log(Level.DEBUG,
                "progress: speed={0} eta={1} downloaded={2} total={3}",
                speedBps, eta, downloaded, total);
        }

        System.out.printf("  %d%% (%d MB / %d MB)  %.1f MB/s  ETA %ds%n",
            percent, downloadedMb, totalMb, speedMbps, eta);
    }

    private Path runtimeDirectory() {
        return serverDirectory.resolve("runtime").resolve("minecraft");
    }

    private static String computeSha256(Path path) {
        try {
            return RuntimeVerifier.sha256(path);
        } catch (Exception e) {
            return "";
        }
    }

    public record ProvisioningResult(boolean success, Path jarPath, boolean downloaded, String error) {

        public static ProvisioningResult found(Path jarPath, boolean downloaded) {
            return new ProvisioningResult(true,
                Objects.requireNonNull(jarPath, "jarPath"), downloaded, null);
        }

        public static ProvisioningResult failed(String error) {
            return new ProvisioningResult(false, null, false,
                Objects.requireNonNull(error, "error"));
        }
    }
}


