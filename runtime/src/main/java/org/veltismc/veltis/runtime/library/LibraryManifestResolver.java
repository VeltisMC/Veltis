package org.veltismc.veltis.runtime.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LibraryManifestResolver {

    private static final String CURRENT_OS = normalizeOs(System.getProperty("os.name", ""));
    private static final String CURRENT_VERSION = System.getProperty("os.version", "");

    public LibraryResolution resolve(String versionUrl) throws IOException {
        var checks = new ArrayList<DiagnosticCheck>();
        JsonObject versionJson;
        try {
            versionJson = downloadJson(URI.create(versionUrl).toURL());
            checks.add(new DiagnosticCheck("Library manifest loaded", true, ""));
        } catch (Exception e) {
            checks.add(new DiagnosticCheck("Library manifest loaded", false, e.getMessage()));
            return new LibraryResolution(List.of(), checks);
        }

        var libraries = parseLibraries(versionJson);
        checks.add(new DiagnosticCheck(
            libraries.size() + " libraries discovered", true,
            libraries.size() + " total"));

        return new LibraryResolution(libraries, checks);
    }

    private List<LibraryDescriptor> parseLibraries(JsonObject versionJson) {
        var librariesArray = versionJson.getAsJsonArray("libraries");
        if (librariesArray == null || librariesArray.isEmpty()) {
            return List.of();
        }

        var result = new ArrayList<LibraryDescriptor>();
        for (var element : librariesArray) {
            var lib = element.getAsJsonObject();
            if (!isAllowedByRules(lib)) continue;
            var artifact = lib.getAsJsonObject("downloads");
            if (artifact == null) continue;
            var artifactObj = artifact.getAsJsonObject("artifact");
            if (artifactObj == null) continue;

            var path = getString(artifactObj, "path");
            var url = getString(artifactObj, "url");
            var sha1 = getString(artifactObj, "sha1");
            var size = artifactObj.get("size") != null ? artifactObj.get("size").getAsLong() : 0;

            if (path == null || url == null || sha1 == null) continue;

            var name = getString(lib, "name");
            if (name == null) name = path;

            result.add(new LibraryDescriptor(name, path, url, sha1, size));
        }
        return result;
    }

    private boolean isAllowedByRules(JsonObject lib) {
        var rules = lib.getAsJsonArray("rules");
        if (rules == null || rules.isEmpty()) return true;

        boolean allowed = false;
        for (var element : rules) {
            var rule = element.getAsJsonObject();
            var action = getString(rule, "action");
            var os = rule.getAsJsonObject("os");

            if (os == null) {
                if ("allow".equals(action)) allowed = true;
                else if ("disallow".equals(action)) allowed = false;
                continue;
            }

            var osName = getString(os, "name");
            var osVersion = getString(os, "version");

            boolean matches = true;
            if (osName != null && !osName.isEmpty()) {
                matches = CURRENT_OS.equals(normalizeOs(osName));
            }
            if (matches && osVersion != null && !osVersion.isEmpty()) {
                matches = CURRENT_VERSION.matches(osVersion);
            }

            if (matches) {
                if ("allow".equals(action)) allowed = true;
                else if ("disallow".equals(action)) allowed = false;
            }
        }
        return allowed;
    }

    private static String normalizeOs(String os) {
        var lower = os.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) return "windows";
        if (lower.contains("mac") || lower.contains("os x") || lower.contains("darwin")) return "osx";
        if (lower.contains("linux")) return "linux";
        if (lower.contains("sunos") || lower.contains("solaris")) return "solaris";
        return lower;
    }

    static String getString(JsonObject obj, String member) {
        var el = obj.get(member);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }

    private JsonObject downloadJson(java.net.URL url) throws IOException {
        var connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/json");
        var rc = connection.getResponseCode();
        if (rc != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + rc + " for " + url);
        }
        try (var reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    public record DiagnosticCheck(String name, boolean passed, String message) {

        public String formatted() {
            var status = passed ? "PASS" : "FAIL";
            if (message.isEmpty()) return "[" + status + "] " + name;
            return "[" + status + "] " + name + " \u2500 " + message;
        }
    }

    public record LibraryResolution(
        List<LibraryDescriptor> libraries,
        List<DiagnosticCheck> diagnostics
    ) {
    }
}


