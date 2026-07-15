package org.veltismc.veltis.runtime.bootstrap;

import org.veltismc.veltis.runtime.RuntimeConfiguration;
import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.adapter.ReflectiveMinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.minecraft.DefaultMinecraftServerController;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Bootstraps the Minecraft runtime environment.
 *
 * <p>Orchestrates validation, adapter creation, and controller
 * initialization. Produces a {@link RuntimeBootstrapResult} that
 * captures the outcome.
 *
 * <p>Usage:
 * <pre>{@code
 * var bootstrap = new MinecraftBootstrap(configuration);
 * var result = bootstrap.bootstrap();
 * if (result.success()) {
 *     var runtime = result.controller();
 * }
 * }</pre>
 */
public final class MinecraftBootstrap {

    private static final Logger LOG = System.getLogger(MinecraftBootstrap.class.getName());

    private final RuntimeConfiguration configuration;
    private final RuntimeValidator validator;

    /**
     * Creates a new bootstrap for the given configuration.
     *
     * @param configuration the runtime configuration
     */
    public MinecraftBootstrap(RuntimeConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.validator = new RuntimeValidator(configuration.serverDirectory());
    }

    /**
     * Creates a bootstrap from a server directory using default configuration.
     *
     * @param serverDirectory the root server directory
     * @return a new bootstrap with default configuration
     */
    public static MinecraftBootstrap withDefaults(Path serverDirectory) {
        return new MinecraftBootstrap(RuntimeConfiguration.defaults(serverDirectory));
    }

    /**
     * Runs the full bootstrap process.
     *
     * <p>Steps:
     * <ol>
     *   <li>Validate the runtime environment</li>
     *   <li>Create the runtime adapter</li>
     *   <li>Create the server controller</li>
     *   <li>Return the bootstrap result</li>
     * </ol>
     *
     * @return the bootstrap result
     */
    public RuntimeBootstrapResult bootstrap() {
        var start = System.nanoTime();
        LOG.log(Level.INFO, "Starting Minecraft runtime bootstrap for {0}",
            configuration.minecraftVersion());

        var validation = validator.validate();
        if (!validation.success()) {
            var durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.log(Level.ERROR, "Bootstrap validation failed: {0}", validation.summary());
            return RuntimeBootstrapResult.failure(
                configuration,
                "Validation failed: " + String.join("; ", validation.errors()),
                durationMs
            );
        }

        try {
            var adapter = createAdapter();
            var controller = new DefaultMinecraftServerController(adapter, configuration);

            var durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.log(Level.INFO, "Bootstrap completed in {0}ms", durationMs);

            return RuntimeBootstrapResult.success(configuration, controller, adapter, durationMs);
        } catch (Exception e) {
            var durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.log(Level.ERROR, "Bootstrap failed: {0}", e.getMessage());
            return RuntimeBootstrapResult.failure(
                configuration,
                "Bootstrap error: " + e.getMessage(),
                durationMs
            );
        }
    }

    /**
     * Creates the runtime adapter. Subclasses can override to provide
     * a custom adapter implementation.
     */
    public MinecraftRuntimeAdapter createAdapter() {
        return new ReflectiveMinecraftRuntimeAdapter(configuration);
    }

    /**
     * Returns the runtime configuration used by this bootstrap.
     */
    public RuntimeConfiguration configuration() {
        return configuration;
    }

    /**
     * Returns the validator used by this bootstrap.
     */
    public RuntimeValidator validator() {
        return validator;
    }
}


