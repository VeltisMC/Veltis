package org.veltismc.patchengine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads vanilla Minecraft server jar from Mojang's servers.
 */
public class VanillaJarDownloader {

    private static final String MANIFEST_URL = "https://launcher.mojang.com/v1/objects/%s/manifest.json";
    private static final String VERSION_MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private final HttpClient client;

    public VanillaJarDownloader() {
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public VanillaJarDownloader(HttpClient client) {
        this.client = client;
    }

    /**
     * Downloads the vanilla server jar for the specified version.
     * Handles Mojang's bundler jars by extracting the actual server jar
     * from inside META-INF/versions/.
     *
     * @param version the Minecraft version (e.g., "26.2")
     * @param destination the path where the jar should be saved
     * @return the path to the downloaded jar
     * @throws PatchEngineException if download fails
     */
    public Path download(String version, Path destination) throws PatchEngineException {
        try {
            var versionUrl = resolveVersionUrl(version);
            var serverJarUrl = resolveServerJarUrl(versionUrl);

            var tempFile = destination.resolveSibling(destination.getFileName() + ".tmp");
            downloadDirect(serverJarUrl, tempFile);

            var extracted = extractFromBundler(tempFile, version, destination);
            if (!extracted) {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(tempFile);
            return destination;
        } catch (Exception e) {
            throw new PatchEngineException(
                "Failed to download vanilla server jar for version " + version, e);
        }
    }

    /**
     * Extracts the actual server jar from a Mojang bundler jar.
     * Bundler jars contain the real server classes inside
     * META-INF/versions/{version}/server-{version}.jar
     *
     * @param bundlerJar the downloaded bundler jar
     * @param version the Minecraft version
     * @param output where to write the extracted server jar
     * @return true if extraction succeeded, false if not a bundler
     * @throws PatchEngineException if extraction fails
     */
    public boolean extractFromBundler(Path bundlerJar, String version, Path output) throws PatchEngineException {
        var innerPath = "META-INF/versions/" + version + "/server-" + version + ".jar";
        try (var fs = FileSystems.newFileSystem(bundlerJar, (ClassLoader) null)) {
            var entry = fs.getPath("/" + innerPath);
            if (Files.exists(entry)) {
                Files.createDirectories(output.getParent());
                Files.copy(entry, output, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (Exception e) {
            // Not a valid zip filesystem — not a bundler jar
        }
        return false;
    }

    /**
     * Downloads a file directly from a URL.
     *
     * @param url the direct download URL
     * @param destination the path where the file should be saved
     * @return the path to the downloaded file
     * @throws PatchEngineException if download fails
     */
    public Path downloadDirect(String url, Path destination) throws PatchEngineException {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                Files.createDirectories(destination.getParent());
                Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (Exception e) {
            throw new PatchEngineException("Failed to download from " + url, e);
        }
    }

    /**
     * Verifies the SHA-256 hash of a downloaded file.
     *
     * @param file the file to verify
     * @param expectedSha256 the expected SHA-256 hash (hex string)
     * @return true if the hash matches
     * @throws PatchEngineException if verification fails
     */
    public boolean verifySha256(Path file, String expectedSha256) throws PatchEngineException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            try (var stream = Files.newInputStream(file)) {
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            var computed = HexFormat.of().formatHex(digest.digest());
            return computed.equalsIgnoreCase(expectedSha256);
        } catch (Exception e) {
            throw new PatchEngineException("Failed to verify SHA-256 of " + file, e);
        }
    }

    private String resolveVersionUrl(String version) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(VERSION_MANIFEST_URL))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var gson = new Gson();
        var root = gson.fromJson(response.body(), JsonObject.class);
        var versions = root.getAsJsonArray("versions");

        for (var versionElement : versions) {
            var versionObj = versionElement.getAsJsonObject();
            if (versionObj.get("id").getAsString().equals(version)) {
                return versionObj.get("url").getAsString();
            }
        }

        throw new PatchEngineException("Version not found: " + version);
    }

    private String resolveServerJarUrl(String versionUrl) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(versionUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        var gson = new Gson();
        var root = gson.fromJson(response.body(), JsonObject.class);
        var downloads = root.getAsJsonObject("downloads");
        var server = downloads.getAsJsonObject("server");

        return server.get("url").getAsString();
    }
}
