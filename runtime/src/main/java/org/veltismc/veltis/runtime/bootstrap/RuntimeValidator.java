package org.veltismc.veltis.runtime.bootstrap;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the runtime environment before bootstrap.
 *
 * <p>Checks:
 * <ul>
 *   <li>Java version meets minimum requirements (Java 26)</li>
 *   <li>Minecraft server JAR or classpath entries are available</li>
 *   <li>Required server directories exist or can be created</li>
 *   <li>Required runtime dependencies are present</li>
 * </ul>
 *
 * <p>All results are collected and returned as a single
 * {@link ValidationResult} — no early exit on first failure.
 */
public final class RuntimeValidator {

    private static final Logger LOG = System.getLogger(RuntimeValidator.class.getName());

    private static final int MINIMUM_JAVA_MAJOR_VERSION = 26;
    private static final String MINECRAFT_VERSION = "26.2";
    private static final String MINECRAFT_SERVER_CLASS = "net.minecraft.server.MinecraftServer";

    private final Path serverDirectory;
    private final List<String> errors;
    private final List<String> warnings;

    /**
     * Creates a validator for the given server directory.
     *
     * @param serverDirectory the root directory where the server runs
     */
    public RuntimeValidator(Path serverDirectory) {
        this.serverDirectory = serverDirectory.toAbsolutePath();
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    /**
     * Runs all validations and returns the result.
     */
    public ValidationResult validate() {
        var start = System.nanoTime();
        errors.clear();
        warnings.clear();

        validateJavaVersion();
        validateServerClasspath();
        validateServerDirectories();
        validateRuntimeEnvironment();

        var durationMs = (System.nanoTime() - start) / 1_000_000;
        var success = errors.isEmpty();

        if (success) {
            LOG.log(Level.INFO, "Runtime validation passed in {0}ms", durationMs);
        } else {
            LOG.log(Level.ERROR, "Runtime validation failed with {0} error(s): {1}",
                errors.size(), String.join("; ", errors));
        }

        return new ValidationResult(success, List.copyOf(errors), List.copyOf(warnings), durationMs);
    }

    private void validateJavaVersion() {
        var version = Runtime.version();
        var major = version.feature();
        if (major < MINIMUM_JAVA_MAJOR_VERSION) {
            errors.add("Java %d or later required, found Java %d"
                .formatted(MINIMUM_JAVA_MAJOR_VERSION, major));
        } else {
            LOG.log(Level.DEBUG, "Java version validated: {0}", version);
        }

        var vendor = System.getProperty("java.vendor", "unknown");
        if (vendor.toLowerCase().contains("oracle") || vendor.toLowerCase().contains("openjdk")) {
            LOG.log(Level.DEBUG, "Java vendor: {0}", vendor);
        } else {
            warnings.add("Untested Java vendor: " + vendor);
        }
    }

    private void validateServerClasspath() {
        try {
            var finder = ModuleFinder.of(serverDirectory);
            var allModules = finder.findAll();

            var hasServerModule = allModules.stream()
                .anyMatch(m -> m.descriptor().packages().contains("net.minecraft.server"));

            if (hasServerModule) {
                LOG.log(Level.DEBUG, "Minecraft server module found on classpath");
                return;
            }

            try {
                Class.forName(MINECRAFT_SERVER_CLASS, false, getClass().getClassLoader());
                LOG.log(Level.DEBUG, "Minecraft server class found on classpath: {0}",
                    MINECRAFT_SERVER_CLASS);
                return;
            } catch (ClassNotFoundException e) {
                LOG.log(Level.DEBUG, "Minecraft server class not found via Class.forName");
            }

            var serverJar = serverDirectory.resolve("server.jar");
            if (Files.exists(serverJar)) {
                LOG.log(Level.DEBUG, "Minecraft server JAR found: {0}", serverJar);
                return;
            }

            warnings.add("Minecraft server classes not found on classpath or as server.jar. "
                + "Runtime will attempt reflective discovery at startup.");
        } catch (Exception e) {
            warnings.add("Could not verify Minecraft server classpath: " + e.getMessage());
        }
    }

    private void validateServerDirectories() {
        var dirs = List.of(
            serverDirectory,
            serverDirectory.resolve("worlds"),
            serverDirectory.resolve("logs"),
            serverDirectory.resolve("config")
        );

        for (var dir : dirs) {
            if (!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                    LOG.log(Level.DEBUG, "Created directory: {0}", dir);
                } catch (Exception e) {
                    errors.add("Cannot create directory " + dir + ": " + e.getMessage());
                }
            } else if (!Files.isDirectory(dir)) {
                errors.add("Path exists but is not a directory: " + dir);
            } else if (!Files.isWritable(dir)) {
                errors.add("Directory is not writable: " + dir);
            }
        }
    }

    private void validateRuntimeEnvironment() {
        var availableProcessors = Runtime.getRuntime().availableProcessors();
        if (availableProcessors < 2) {
            warnings.add("Only %d processor(s) available; performance may be degraded"
                .formatted(availableProcessors));
        }

        var maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory < 512_000_000) {
            warnings.add("Maximum heap memory (%d MB) may be insufficient for production use"
                .formatted(maxMemory / 1_000_000));
        }

        var threadType = "virtual";
        try {
            var thread = Thread.ofVirtual().name("validation-test").unstarted(() -> {});
            thread.start();
            thread.join();
            LOG.log(Level.DEBUG, "Virtual threads are available");
        } catch (Exception e) {
            warnings.add("Virtual threads not available: " + e.getMessage());
            threadType = "platform";
        }

        LOG.log(Level.DEBUG, "Runtime environment: {0} processors, {1} MB heap, {2} threads",
            availableProcessors, maxMemory / 1_000_000, threadType);
    }

    /**
     * Result of running environment validations.
     *
     * @param success    true if no errors were found
     * @param errors     list of error messages (fatal)
     * @param warnings   list of warning messages (non-fatal)
     * @param durationMs time taken to run validations
     */
    public record ValidationResult(
        boolean success,
        List<String> errors,
        List<String> warnings,
        long durationMs
    ) {

        /**
         * Returns a formatted summary of all errors and warnings.
         */
        public String summary() {
            var sb = new StringBuilder();
            if (success) {
                sb.append("Validation passed");
            } else {
                sb.append("Validation failed: ").append(errors.size()).append(" error(s)");
            }
            if (!warnings.isEmpty()) {
                sb.append(", ").append(warnings.size()).append(" warning(s)");
            }
            sb.append(" (").append(durationMs).append("ms)");
            return sb.toString();
        }
    }
}


