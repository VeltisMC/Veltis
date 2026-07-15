package org.veltismc.veltis.runtime.provision;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class RuntimeInstaller {

    private static final String JAR_NAME = "server.jar";
    private static final String METADATA_NAME = "metadata.json";
    private static final String CHECKSUMS_NAME = "checksums.json";

    private final Path runtimeRoot;

    public RuntimeInstaller(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot;
    }

    public Path install(Path tempJar, MinecraftVersionManifest manifest) throws IOException {
        var dir = versionDirectory(manifest.version());
        Files.createDirectories(dir);

        var targetJar = dir.resolve(JAR_NAME);
        Files.copy(tempJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(tempJar);

        writeMetadata(dir, manifest);
        writeChecksums(dir, manifest, targetJar);

        return targetJar;
    }

    public boolean isInstalled(String version) {
        return Files.exists(versionDirectory(version).resolve(JAR_NAME));
    }

    public Path installedJar(String version) {
        return versionDirectory(version).resolve(JAR_NAME);
    }

    private void writeMetadata(Path dir, MinecraftVersionManifest manifest) throws IOException {
        var json = new JsonObject();
        json.addProperty("version", manifest.version());
        json.addProperty("downloadedAt", Instant.now().toString());
        json.addProperty("downloadUrl", manifest.downloadUrl());
        json.addProperty("fileSize", manifest.fileSize());
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(dir.resolve(METADATA_NAME), gson.toJson(json), StandardCharsets.UTF_8);
    }

    private void writeChecksums(Path dir, MinecraftVersionManifest manifest, Path jarPath) throws IOException {
        var jarChecksum = new JsonObject();
        try {
            jarChecksum.addProperty("sha256", RuntimeVerifier.sha256(jarPath));
        } catch (Exception e) {
            throw new IOException("Failed to compute SHA-256 during installation", e);
        }
        jarChecksum.addProperty("size", Files.size(jarPath));

        var checksums = new JsonObject();
        checksums.add(JAR_NAME, jarChecksum);

        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(dir.resolve(CHECKSUMS_NAME), gson.toJson(checksums), StandardCharsets.UTF_8);
    }

    private Path versionDirectory(String version) {
        return runtimeRoot.resolve(version);
    }
}


