package org.veltismc.veltis.runtime.provision;

import java.util.ArrayList;
import java.util.List;

public final class MojangRuntimeResolver {

    private final MojangVersionManifestClient client;

    public MojangRuntimeResolver() {
        this.client = new MojangVersionManifestClient();
    }

    public MojangRuntimeResolver(MojangVersionManifestClient client) {
        this.client = client;
    }

    public ResolutionResult resolve(String versionId) {
        var checks = new ArrayList<DiagnosticCheck>();

        var versions = fetchManifest(checks);
        if (versions == null) {
            return new ResolutionResult(false, versionId, null, null, 0, checks);
        }

        var version = findVersion(versionId, versions, checks);
        if (version == null) {
            return new ResolutionResult(false, versionId, null, null, 0, checks);
        }

        var download = fetchDownload(version, checks);
        if (download == null) {
            return new ResolutionResult(false, versionId, null, null, 0, checks);
        }

        return new ResolutionResult(true, versionId, download.url(), download.sha1(), download.size(), checks);
    }

    private List<MojangVersionMetadata> fetchManifest(List<DiagnosticCheck> checks) {
        try {
            var versions = client.fetchManifest();
            checks.add(new DiagnosticCheck("Version manifest downloaded", true, versions.size() + " versions"));
            return versions;
        } catch (Exception e) {
            checks.add(new DiagnosticCheck("Version manifest downloaded", false, e.getMessage()));
            return null;
        }
    }

    private MojangVersionMetadata findVersion(String versionId, List<MojangVersionMetadata> versions,
                                                List<DiagnosticCheck> checks) {
        var found = versions.stream()
            .filter(v -> v.id().equals(versionId))
            .findFirst();
        if (found.isPresent()) {
            checks.add(new DiagnosticCheck("Version " + versionId + " located", true,
                "type=" + found.get().type()));
            return found.get();
        }
        var releases = versions.stream()
            .filter(v -> "release".equals(v.type()))
            .map(MojangVersionMetadata::id)
            .limit(10)
            .toList();
        checks.add(new DiagnosticCheck("Version " + versionId + " located", false,
            "Not found. Latest releases: " + String.join(", ", releases)));
        return null;
    }

    private MojangVersionManifestClient.ServerDownloadInfo fetchDownload(MojangVersionMetadata version,
                                                                           List<DiagnosticCheck> checks) {
        try {
            var download = client.fetchServerDownload(version);
            checks.add(new DiagnosticCheck("Server URL resolved", true, download.url()));
            checks.add(new DiagnosticCheck("SHA1 resolved", true, download.sha1()));
            return download;
        } catch (Exception e) {
            checks.add(new DiagnosticCheck("Server URL resolved", false, e.getMessage()));
            return null;
        }
    }

    public record DiagnosticCheck(String name, boolean passed, String message) {

        public String formatted() {
            var status = passed ? "PASS" : "FAIL";
            if (message.isEmpty()) {
                return "[" + status + "] " + name;
            }
            return "[" + status + "] " + name + " \u2500 " + message;
        }
    }

    public record ResolutionResult(
        boolean success,
        String version,
        String downloadUrl,
        String sha1,
        long fileSize,
        List<DiagnosticCheck> diagnostics
    ) {

        public void print(java.io.PrintStream out) {
            out.println("=== Runtime Resolution Report ===");
            for (var check : diagnostics) {
                out.println("  " + check.formatted());
            }
            if (success) {
                out.println("  Resolved URL: " + downloadUrl);
                out.println("  SHA1: " + sha1);
                if (fileSize > 0) {
                    var mb = fileSize / 1_000_000;
                    out.println("  Size: " + mb + " MB (" + fileSize + " bytes)");
                }
            }
        }
    }
}


