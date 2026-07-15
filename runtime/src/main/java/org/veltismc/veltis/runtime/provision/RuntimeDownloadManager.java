package org.veltismc.veltis.runtime.provision;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RuntimeDownloadManager {

    private final MinecraftVersionManifest manifest;

    public RuntimeDownloadManager(MinecraftVersionManifest manifest) {
        this.manifest = manifest;
    }

    public Path download(ProgressCallback callback) throws IOException {
        var url = URI.create(manifest.downloadUrl()).toURL();
        var connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);

        var responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Download failed with HTTP " + responseCode + " for " + manifest.downloadUrl());
        }

        var totalBytes = connection.getContentLengthLong();
        var tempFile = Files.createTempFile("VeltisMC-runtime-", ".jar.tmp");

        try (var input = connection.getInputStream()) {
            if (totalBytes > 0) {
                downloadWithProgress(input, tempFile, totalBytes, callback);
            } else {
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Download interrupted: " + e.getMessage(), e);
        }

        return tempFile;
    }

    private void downloadWithProgress(InputStream input, Path target, long totalBytes,
                                       ProgressCallback callback) throws IOException {
        try (var output = Files.newOutputStream(target)) {
            var buffer = new byte[8192];
            long downloaded = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloaded += read;
                callback.onProgress(downloaded, totalBytes);
            }
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long downloaded, long totalBytes);
    }
}



