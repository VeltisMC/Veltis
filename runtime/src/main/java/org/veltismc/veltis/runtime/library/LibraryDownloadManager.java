package org.veltismc.veltis.runtime.library;

import org.veltismc.veltis.runtime.provision.RuntimeVerifier;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class LibraryDownloadManager {

    private final Path librariesDir;

    public LibraryDownloadManager(Path librariesDir) {
        this.librariesDir = librariesDir;
    }

    public LibraryDownloadResult downloadAll(List<LibraryDescriptor> libraries) {
        var results = new ArrayList<LibraryVerificationResult>();
        int downloaded = 0;

        for (var lib : libraries) {
            var targetPath = librariesDir.resolve(lib.path());
            var result = downloadLibrary(lib, targetPath);
            results.add(result);
            if (result.passed()) downloaded++;
        }

        return new LibraryDownloadResult(downloaded, results);
    }

    private LibraryVerificationResult downloadLibrary(LibraryDescriptor lib, Path targetPath) {
        if (Files.exists(targetPath)) {
            if (verifySha1(targetPath, lib.sha1())) {
                return LibraryVerificationResult.passed(lib.name());
            }
            try { Files.delete(targetPath); } catch (Exception ignored) { }
        }

        try {
            Files.createDirectories(targetPath.getParent());
            var url = URI.create(lib.url()).toURL();
            var connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);

            var rc = connection.getResponseCode();
            if (rc != HttpURLConnection.HTTP_OK) {
                return LibraryVerificationResult.failed(lib.name(),
                    "HTTP " + rc + " for " + lib.url());
            }

            try (var input = connection.getInputStream()) {
                Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                try { Files.deleteIfExists(targetPath); } catch (Exception ignored) { }
                return LibraryVerificationResult.failed(lib.name(),
                    "download failed: " + e.getMessage());
            }

            if (!verifySha1(targetPath, lib.sha1())) {
                try { Files.delete(targetPath); } catch (Exception ignored) { }
                return LibraryVerificationResult.failed(lib.name(), "SHA-1 mismatch");
            }

            return LibraryVerificationResult.passed(lib.name());
        } catch (Exception e) {
            return LibraryVerificationResult.failed(lib.name(),
                "error: " + e.getMessage());
        }
    }

    private boolean verifySha1(Path path, String expectedSha1) {
        try {
            var actual = RuntimeVerifier.sha1(path);
            return actual.equalsIgnoreCase(expectedSha1);
        } catch (Exception e) {
            return false;
        }
    }

    public record LibraryDownloadResult(int downloaded, List<LibraryVerificationResult> results) {
    }
}


