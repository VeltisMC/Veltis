package org.veltismc.veltis.runtime.provision;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MojangVersionManifestClient {

    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    public List<MojangVersionMetadata> fetchManifest() throws IOException {
        var json = downloadJson(URI.create(MANIFEST_URL).toURL());
        var versions = json.getAsJsonArray("versions");
        var result = new ArrayList<MojangVersionMetadata>(versions.size());
        for (var element : versions) {
            var obj = element.getAsJsonObject();
            result.add(new MojangVersionMetadata(
                stringOrThrow(obj, "id"),
                stringOrThrow(obj, "type"),
                stringOrThrow(obj, "url"),
                stringOrNull(obj, "releaseTime")
            ));
        }
        return result;
    }

    public Optional<MojangVersionMetadata> findVersion(String versionId) throws IOException {
        return fetchManifest().stream()
            .filter(v -> v.id().equals(versionId))
            .findFirst();
    }

    public ServerDownloadInfo fetchServerDownload(MojangVersionMetadata version) throws IOException {
        var json = downloadJson(URI.create(version.url()).toURL());
        var downloads = json.getAsJsonObject("downloads");
        if (downloads == null) {
            throw new IOException("No 'downloads' object in version metadata for " + version.id());
        }
        var server = downloads.getAsJsonObject("server");
        if (server == null) {
            throw new IOException("No 'server' download entry for " + version.id());
        }
        var sha1 = stringOrThrow(server, "sha1");
        var size = server.get("size").getAsLong();
        var url = stringOrThrow(server, "url");
        return new ServerDownloadInfo(url, sha1, size);
    }

    private JsonObject downloadJson(URL url) throws IOException {
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

    private static String stringOrThrow(JsonObject obj, String member) {
        var el = obj.get(member);
        if (el == null || el.isJsonNull()) {
            throw new IllegalArgumentException("Missing required field '" + member + "' in JSON object");
        }
        return el.getAsString();
    }

    private static String stringOrNull(JsonObject obj, String member) {
        var el = obj.get(member);
        return (el == null || el.isJsonNull()) ? "" : el.getAsString();
    }

    public record ServerDownloadInfo(String url, String sha1, long size) {
    }
}


