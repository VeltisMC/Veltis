package org.veltismc.veltis.runtime.library;

public record LibraryVerificationResult(
    String name,
    boolean passed,
    String message
) {

    public static LibraryVerificationResult passed(String name) {
        return new LibraryVerificationResult(name, true, "verified");
    }

    public static LibraryVerificationResult failed(String name, String message) {
        return new LibraryVerificationResult(name, false, message);
    }
}


