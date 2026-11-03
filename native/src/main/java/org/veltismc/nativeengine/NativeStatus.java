package org.veltismc.nativeengine;

import java.util.Objects;
import java.util.Set;

/**
 * The outcome of the most recent native-engine initialization.
 *
 * <p>{@code available} means a compatible library is loaded, initialized and
 * usable. {@code loaded} separates the two failure shapes an operator needs to
 * tell apart: a library that was never found ({@code loaded == false}) and one
 * that was found but rejected as incompatible or failed to initialize
 * ({@code loaded == true}). The latter is always logged as a warning.
 *
 * <p>{@code reason} is empty for a fully available engine and otherwise names
 * the fallback reason in a form suitable for a log line.
 */
public record NativeStatus(
        boolean available,
        boolean loaded,
        int abiVersion,
        String engineVersion,
        Set<NativeCapabilities> capabilities,
        String reason) {

    public NativeStatus {
        engineVersion = engineVersion == null || engineVersion.isBlank() ? "0.0.0" : engineVersion;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        reason = reason == null ? "" : reason;
    }

    /** Whether Java fallback is the active path. */
    public boolean fallbackActive() {
        return !available;
    }

    public boolean supports(NativeCapabilities capability) {
        return capabilities.contains(capability);
    }

    public static NativeStatus disabled(String reason) {
        return new NativeStatus(false, false, 0, "0.0.0", Set.of(), reason);
    }

    public static NativeStatus unavailable(boolean loaded, int abiVersion, String engineVersion, String reason) {
        return new NativeStatus(false, loaded, abiVersion, engineVersion, Set.of(), reason);
    }

    public static NativeStatus available(
            int abiVersion,
            String engineVersion,
            Set<NativeCapabilities> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return new NativeStatus(true, true, abiVersion, engineVersion, capabilities, "");
    }

    /** One-line form used by server startup and diagnostics. */
    public String summary() {
        if (available) {
            return "native engine " + engineVersion + " (ABI " + abiVersion + ") capabilities=" + capabilities;
        }
        return "native support unavailable"
                + (loaded ? " (library loaded but rejected)" : " (library not loaded)")
                + ": " + reason;
    }
}
