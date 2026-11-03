package org.veltismc.nativeengine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Native engine settings, read from the same places every other VeltisMC
 * runtime setting is read from.
 *
 * <p>VeltisMC's runtime knobs are configuration-file values for an operator and
 * system properties for a launcher or a test. This record reads
 * {@code server.properties} from the server home first and overlays the
 * {@code veltis.native.*} system properties on top, so an administrator can set
 * a permanent value in the file and a `-D` override for one run without a second
 * configuration framework.
 *
 * <pre>
 * veltis.native.enabled=true
 * veltis.native.autoload=true
 * veltis.native.fallback=true
 * veltis.native.stats=false
 * veltis.native.path=              # optional file or directory
 * </pre>
 *
 * <p>{@code enabled} is the master switch; with it false no library is looked
 * for at all. {@code autoload} controls whether {@link NativeEngine#initialize}
 * performs the load immediately; {@code fallback} controls whether operations
 * fall back to Java when native support is unavailable. {@code stats} enables
 * cheap invocation counters for diagnostics.
 */
public record NativeConfig(
        boolean enabled,
        boolean autoload,
        boolean fallback,
        boolean stats,
        String libraryPath) {

    public static final String ENABLED_PROPERTY = "veltis.native.enabled";
    public static final String AUTOLOAD_PROPERTY = "veltis.native.autoload";
    public static final String FALLBACK_PROPERTY = "veltis.native.fallback";
    public static final String STATS_PROPERTY = "veltis.native.stats";
    public static final String PATH_PROPERTY = "veltis.native.path";

    private static final System.Logger LOG = System.getLogger(NativeConfig.class.getName());

    public NativeConfig {
        libraryPath = libraryPath == null ? "" : libraryPath.trim();
    }

    /** Defaults for a normal server: attempt a load, fall back quietly if absent. */
    public static NativeConfig defaults() {
        return new NativeConfig(true, true, true, false, "");
    }

    /** Whether an explicit library file or directory was configured. */
    public boolean hasLibraryPath() {
        return !libraryPath.isEmpty();
    }

    /**
     * Reads {@code <home>/server.properties} and overlays system properties.
     *
     * <p>An unreadable or malformed file is reported and ignored: configuration
     * must never make the server fail to start, and the defaults are safe.
     */
    public static NativeConfig load(Path home) {
        var properties = new Properties();
        var file = home.resolve("server.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Could not read native settings from " + file + "; using defaults", e);
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
    public static NativeConfig fromProperties(Properties properties) {
        var defaults = defaults();
        return new NativeConfig(
                booleanProperty(properties, ENABLED_PROPERTY, defaults.enabled()),
                booleanProperty(properties, AUTOLOAD_PROPERTY, defaults.autoload()),
                booleanProperty(properties, FALLBACK_PROPERTY, defaults.fallback()),
                booleanProperty(properties, STATS_PROPERTY, defaults.stats()),
                properties.getProperty(PATH_PROPERTY, defaults.libraryPath()));
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
}
