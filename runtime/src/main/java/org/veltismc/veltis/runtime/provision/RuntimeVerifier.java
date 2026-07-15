package org.veltismc.veltis.runtime.provision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RuntimeVerifier {

    private static final String SHA256 = "SHA-256";
    private static final String SHA1 = "SHA-1";

    public RuntimeVerificationResult verify(Path jarPath, MinecraftVersionManifest manifest) {
        return verify(jarPath, SHA256, manifest.sha256(), manifest.fileSize());
    }

    public RuntimeVerificationResult verify(Path jarPath, String algorithm, String expectedHash, long expectedSize) {
        if (!Files.exists(jarPath)) {
            return RuntimeVerificationResult.notFound(jarPath.toString());
        }
        try {
            var actualHash = hash(jarPath, algorithm);
            var actualSize = Files.size(jarPath);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                return RuntimeVerificationResult.hashMismatch(algorithm, expectedHash, actualHash);
            }
            if (expectedSize > 0 && actualSize != expectedSize) {
                return RuntimeVerificationResult.sizeMismatch(expectedSize, actualSize);
            }
            return RuntimeVerificationResult.valid(algorithm, actualHash, actualSize);
        } catch (Exception e) {
            return RuntimeVerificationResult.notFound(jarPath.toString());
        }
    }

    public static String sha1(Path path) throws Exception {
        return hash(path, SHA1);
    }

    public static String sha256(Path path) throws Exception {
        return hash(path, SHA256);
    }

    public static String hash(Path path, String algorithm) throws Exception {
        var digest = MessageDigest.getInstance(algorithm);
        try (var fis = Files.newInputStream(path);
             var dis = new DigestInputStream(fis, digest)) {
            var buffer = new byte[8192];
            while (dis.read(buffer) != -1) {
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}


