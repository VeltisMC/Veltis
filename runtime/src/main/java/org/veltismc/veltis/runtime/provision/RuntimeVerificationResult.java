package org.veltismc.veltis.runtime.provision;

import java.util.List;

public record RuntimeVerificationResult(
    boolean valid,
    String algorithm,
    String expectedHash,
    String actualHash,
    long expectedSize,
    long actualSize,
    List<String> errors
) {

    public static RuntimeVerificationResult valid(String algorithm, String hash, long size) {
        return new RuntimeVerificationResult(true, algorithm, hash, hash, size, size, List.of());
    }

    public static RuntimeVerificationResult hashMismatch(String algorithm, String expected, String actual) {
        return new RuntimeVerificationResult(false, algorithm, expected, actual, 0, 0,
            List.of("SHA-256 mismatch: expected " + expected + ", got " + actual));
    }

    public static RuntimeVerificationResult sizeMismatch(long expected, long actual) {
        return new RuntimeVerificationResult(false, "size", "", "", expected, actual,
            List.of("Size mismatch: expected " + expected + " bytes, got " + actual + " bytes"));
    }

    public static RuntimeVerificationResult notFound(String path) {
        return new RuntimeVerificationResult(false, "", "", "", 0, 0,
            List.of("File not found: " + path));
    }
}


