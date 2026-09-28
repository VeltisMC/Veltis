package org.veltismc.buildtools.version;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class VersionManifest {

    private static final String MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final List<VersionEntry> versions;
    private final String latestRelease;
    private final String latestSnapshot;

    private VersionManifest(
        List<VersionEntry> versions,
        String latestRelease,
        String latestSnapshot
    ) {
        this.versions = List.copyOf(versions);
        this.latestRelease = latestRelease;
        this.latestSnapshot = latestSnapshot;
    }

    public static VersionManifest fetch() {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(MANIFEST_URL))
                .timeout(TIMEOUT)
                .GET()
                .build();
            var response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to fetch version manifest", e);
        }
    }

    public static VersionManifest parse(String json) {
        var gson = new Gson();
        var root = gson.fromJson(json, JsonObject.class);
        var latest = root.getAsJsonObject("latest");
        var latestRelease = latest.get("release").getAsString();
        var latestSnapshot = latest.get("snapshot").getAsString();
        var entries = new ArrayList<VersionEntry>();
        var versionsArray = root.getAsJsonArray("versions");
        for (var element : versionsArray) {
            var obj = element.getAsJsonObject();
            entries.add(new VersionEntry(
                obj.get("id").getAsString(),
                obj.get("type").getAsString(),
                obj.get("url").getAsString(),
                obj.get("time").getAsString(),
                obj.get("releaseTime").getAsString()
            ));
        }
        Collections.reverse(entries);
        return new VersionManifest(entries, latestRelease, latestSnapshot);
    }

    public Collection<VersionEntry> versions() {
        return versions;
    }

    public String latestRelease() {
        return latestRelease;
    }

    public String latestSnapshot() {
        return latestSnapshot;
    }

    public VersionEntry find(String versionId) {
        return versions.stream()
            .filter(v -> v.id().equals(versionId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown version: " + versionId));
    }

    public record VersionEntry(
        String id,
        String type,
        String url,
        String time,
        String releaseTime
    ) {

        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        public boolean isOldAlpha() {
            return "old_alpha".equals(type);
        }

        public boolean isOldBeta() {
            return "old_beta".equals(type);
        }
    }
}


