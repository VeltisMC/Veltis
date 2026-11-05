package org.veltismc.nativeengine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Settings for the selective native distribution: whether this server may
 * fetch a native library at all, and how eagerly it looks for one.
 *
 * <p>It is read exactly like {@link NativeConfig} — {@code server.properties}
 * from the server home first, then the {@code veltis.native.*} system
 * properties on top — and it is a separate record for the same reason the
 * loader has no opinion about download policy: the five distribution knobs are
 * meaningless to {@link NativeLoader}, and widening {@link NativeConfig} would
 * have changed every constructor call that only describes how a library is
 * loaded.
 *
 * <pre>
 * veltis.native.autoDownload=true      # the default; false forbids every fetch
 * veltis.native.offline=false          # hard "no network" for this run
 * veltis.native.updatePolicy=if-missing   # never | if-missing | always
 * veltis.native.downloadTimeout=30     # seconds, per request
 * veltis.native.repository=            # empty means the official release line
 * </pre>
 *
 * <p>Nothing here is fatal. A value that cannot be parsed is reported and
 * replaced by the default, because a typo in a properties file must not stop a
 * server from starting.
 */
public record NativeDistributionConfig(
        boolean autoDownload,
        boolean offline,
        String updatePolicy,
        int downloadTimeoutSeconds,
        String repository) {

    public static final String AUTO_DOWNLOAD_PROPERTY = "veltis.native.autoDownload";
    public static final String OFFLINE_PROPERTY = "veltis.native.offline";
    public static final String UPDATE_POLICY_PROPERTY = "veltis.native.updatePolicy";
    public static final String DOWNLOAD_TIMEOUT_PROPERTY = "veltis.native.downloadTimeout";
    public static final String REPOSITORY_PROPERTY = "veltis.native.repository";

    /** Never touch the network; use only what is already installed. */
    public static final String UPDATE_POLICY_NEVER = "never";

    /**
     * Fetch only when this platform has no verified library installed. A warm
     * installation therefore performs zero requests, which is what makes a
     * restarted server cheap.
     */
    public static final String UPDATE_POLICY_IF_MISSING = "if-missing";

    /** Always consult the manifest and install the published version. */
    public static final String UPDATE_POLICY_ALWAYS = "always";

    /**
     * The release line this build downloads from when no repository is
     * configured. It names the native release tag rather than
     * {@code releases/latest}, because the same repository also publishes the
     * server distribution and "latest" would resolve to whichever of the two
     * was created last. Kept in step with the header's version string by
     * {@code verifyNativeArtifact}.
     */
    public static final String DEFAULT_REPOSITORY =
            "https://github.com/VeltisMC/Veltis/releases/download/veltis-native-v1.0.0";

    public static final int DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 30;

    /** Values an unparseable field falls back to, with a warning. */
    public static final String DEFAULT_UPDATE_POLICY = UPDATE_POLICY_IF_MISSING;

    private static final System.Logger LOG = System.getLogger(NativeDistributionConfig.class.getName());

    public NativeDistributionConfig {
        updatePolicy = normalizePolicy(updatePolicy);
        repository = repository == null ? "" : repository.trim();
        if (downloadTimeoutSeconds <= 0) {
            downloadTimeoutSeconds = DEFAULT_DOWNLOAD_TIMEOUT_SECONDS;
        }
    }

    /**
     * The shipped default: the server looks for its own library unless told
     * otherwise.
     *
     * <p>Fetching executable code without being asked is a real choice, and it
     * is made safe by what happens instead of by an operator's vigilance: only
     * the official release line is contacted, only one platform's archive is
     * ever fetched, the manifest must carry a signature from the key embedded in
     * {@link NativeManifest}, and both the archive and the library inside it are
     * digested before anything is installed. A failure at any of those points
     * leaves the server on its Java implementations, which is also what
     * {@code autoDownload=false} gives, just without the acceleration.
     */
    public static NativeDistributionConfig defaults() {
        return new NativeDistributionConfig(true, false, DEFAULT_UPDATE_POLICY,
                DEFAULT_DOWNLOAD_TIMEOUT_SECONDS, "");
    }

    /** The configured repository, or the official release line when unset. */
    public static String defaultRepository() {
        return DEFAULT_REPOSITORY;
    }

    public boolean policyRequiresRefresh() {
        return UPDATE_POLICY_ALWAYS.equals(updatePolicy);
    }

    public boolean policyAllowsNetwork() {
        return !UPDATE_POLICY_NEVER.equals(updatePolicy);
    }

    /**
     * Reads {@code <home>/server.properties} and overlays system properties,
     * exactly as {@link NativeConfig#load} does for the loader's settings.
     */
    public static NativeDistributionConfig load(Path home) {
        var properties = new Properties();
        var file = home.resolve("server.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not read native distribution settings from " + file + "; using defaults", e);
            }
        }
        for (var name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("veltis.native.")) {
                properties.setProperty(name, System.getProperty(name, ""));
            }
        }
        return fromProperties(properties);
    }

    /** Builds a configuration from an already loaded property set. */
    public static NativeDistributionConfig fromProperties(Properties properties) {
        var defaults = defaults();
        var timeout = intProperty(properties, DOWNLOAD_TIMEOUT_PROPERTY, defaults.downloadTimeoutSeconds());
        return new NativeDistributionConfig(
                booleanProperty(properties, AUTO_DOWNLOAD_PROPERTY, defaults.autoDownload()),
                booleanProperty(properties, OFFLINE_PROPERTY, defaults.offline()),
                properties.getProperty(UPDATE_POLICY_PROPERTY, defaults.updatePolicy()),
                timeout,
                properties.getProperty(REPOSITORY_PROPERTY, defaults.repository()));
    }

    private static String normalizePolicy(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_UPDATE_POLICY;
        }
        var value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case UPDATE_POLICY_NEVER, UPDATE_POLICY_IF_MISSING, UPDATE_POLICY_ALWAYS -> value;
            default -> {
                LOG.log(System.Logger.Level.WARNING,
                        "Ignoring invalid " + UPDATE_POLICY_PROPERTY + "=" + raw + "; using "
                                + DEFAULT_UPDATE_POLICY);
                yield DEFAULT_UPDATE_POLICY;
            }
        };
    }

    private static boolean booleanProperty(Properties properties, String name, boolean fallback) {
        var value = properties.getProperty(name);
        if (value == null) {
            return fallback;
        }
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        LOG.log(System.Logger.Level.WARNING,
                "Ignoring invalid boolean " + name + "=" + trimmed + "; using " + fallback);
        return fallback;
    }

    private static int intProperty(Properties properties, String name, int fallback) {
        var value = properties.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            var parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive: " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Ignoring invalid " + name + "=" + value + "; using " + fallback);
            return fallback;
        }
    }
}
