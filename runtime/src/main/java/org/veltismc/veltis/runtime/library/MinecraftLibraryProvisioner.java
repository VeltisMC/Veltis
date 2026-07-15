package org.veltismc.veltis.runtime.library;

import org.veltismc.veltis.runtime.provision.MojangVersionManifestClient;

import java.lang.System.Logger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MinecraftLibraryProvisioner {

    private static final Logger LOG = System.getLogger(MinecraftLibraryProvisioner.class.getName());

    private final Path serverDirectory;
    private final String version;
    private final MojangVersionManifestClient manifestClient;

    public MinecraftLibraryProvisioner(Path serverDirectory, String version) {
        this.serverDirectory = Objects.requireNonNull(serverDirectory, "serverDirectory");
        this.version = Objects.requireNonNull(version, "version");
        this.manifestClient = new MojangVersionManifestClient();
    }

    public ProvisionResult provision() {
        var diagnostics = new ArrayList<String>();

        var versionUrl = resolveVersionUrl(diagnostics);
        if (versionUrl == null) {
            return new ProvisionResult(false, List.of(), diagnostics);
        }

        var resolver = new LibraryManifestResolver();
        LibraryManifestResolver.LibraryResolution resolution;
        try {
            resolution = resolver.resolve(versionUrl);
        } catch (Exception e) {
            diagnostics.add("[FAIL] Library manifest resolved \u2500 " + e.getMessage());
            return new ProvisionResult(false, List.of(), diagnostics);
        }

        for (var check : resolution.diagnostics()) {
            diagnostics.add("  " + check.formatted());
        }

        if (resolution.libraries().isEmpty()) {
            return new ProvisionResult(false, List.of(), diagnostics);
        }

        var librariesDir = serverDirectory.resolve("runtime")
            .resolve("minecraft").resolve(version).resolve("libraries");

        var downloadManager = new LibraryDownloadManager(librariesDir);
        var downloadResult = downloadManager.downloadAll(resolution.libraries());

        var allPassed = true;
        for (var result : downloadResult.results()) {
            var status = result.passed() ? "PASS" : "FAIL";
            diagnostics.add("  [" + status + "] " + result.name() + " \u2500 " + result.message());
            if (!result.passed()) allPassed = false;
        }

        var totalDiagnostic = allPassed
            ? "[PASS] " + downloadResult.downloaded() + " libraries downloaded"
            : "[FAIL] " + downloadResult.downloaded() + "/" + resolution.libraries().size() + " libraries downloaded";
        diagnostics.add("  " + totalDiagnostic);

        var classpathBuilder = new LibraryClasspathBuilder(librariesDir);
        var classpathUrls = classpathBuilder.build();
        diagnostics.add("  [PASS] Classpath assembled");

        return new ProvisionResult(allPassed, classpathUrls, diagnostics);
    }

    private String resolveVersionUrl(List<String> diagnostics) {
        try {
            var versionOpt = manifestClient.findVersion(version);
            if (versionOpt.isEmpty()) {
                diagnostics.add("[FAIL] Version " + version + " located \u2500 Not found in manifest");
                return null;
            }
            var url = versionOpt.get().url();
            diagnostics.add("  [PASS] Library manifest loaded");
            return url;
        } catch (Exception e) {
            diagnostics.add("[FAIL] Library manifest loaded \u2500 " + e.getMessage());
            return null;
        }
    }

    public record ProvisionResult(
        boolean success,
        List<java.net.URL> classpathUrls,
        List<String> diagnostics
    ) {

        public void print(java.io.PrintStream out) {
            out.println("=== Library Provisioning Report ===");
            for (var line : diagnostics) {
                out.println(line);
            }
            if (success) {
                out.println("  " + classpathUrls.size() + " library URLs on classpath");
            }
        }
    }
}


