package org.veltismc.veltis.buildtools.download;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

public final class ServerDownloader {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2";

    private static final Map<String, String> MAVEN_URLS = Map.of(
        "org.jetbrains:annotations:24.0.1",
        MAVEN_CENTRAL + "/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar",
        "com.google.code.findbugs:jsr305:3.0.2",
        MAVEN_CENTRAL + "/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar"
    );

    private final HttpClient client;

    public ServerDownloader() {
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public ServerDownloader(HttpClient client) {
        this.client = client;
    }

    public Path download(String versionUrl, Path destination) {
        try {
            var serverJarUrl = resolveServerJarUrl(versionUrl);
            return downloadDirect(serverJarUrl, destination);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to download server jar from " + versionUrl, e);
        }
    }

    public Path downloadDirect(String url, Path destination) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();
            var response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                Files.createDirectories(destination.getParent());
                Files.copy(stream, destination,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to download from " + url, e);
        }
    }

    public static Map<String, String> mavenUrls() {
        return MAVEN_URLS;
    }

    public static String mavenCentralUrl(String group, String artifact, String version) {
        return MAVEN_CENTRAL + "/" + group.replace('.', '/')
            + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
    }

    public String resolveServerJarUrl(String versionUrl) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(versionUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            var response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
            var gson = new Gson();
            var root = gson.fromJson(response.body(), JsonObject.class);
            var downloads = root.getAsJsonObject("downloads");
            var server = downloads.getAsJsonObject("server");
            return server.get("url").getAsString();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to resolve server jar URL from " + versionUrl, e);
        }
    }
}


