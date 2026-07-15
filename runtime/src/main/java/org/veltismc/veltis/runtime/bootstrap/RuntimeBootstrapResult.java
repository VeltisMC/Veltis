package org.veltismc.veltis.runtime.bootstrap;

import org.veltismc.veltis.runtime.RuntimeConfiguration;
import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.minecraft.MinecraftServerController;

/**
 * Immutable result of the runtime bootstrap process.
 *
 * <p>Captures the outcome of initialization, including the
 * configuration used, the server controller, any validation
 * failures, and timing information.
 *
 * @param success        whether bootstrap completed successfully
 * @param configuration  the runtime configuration used
 * @param controller     the server controller (null if bootstrap failed)
 * @param adapter        the runtime adapter (null if bootstrap failed)
 * @param errorMessage   description of failure (null on success)
 * @param durationMs     time taken to bootstrap in milliseconds
 * @param validatedJava  whether Java version validation passed
 * @param validatedMinecraft whether Minecraft version validation passed
 */
public record RuntimeBootstrapResult(
    boolean success,
    RuntimeConfiguration configuration,
    MinecraftServerController controller,
    MinecraftRuntimeAdapter adapter,
    String errorMessage,
    long durationMs,
    boolean validatedJava,
    boolean validatedMinecraft
) {

    private static final RuntimeBootstrapResult EMPTY = new RuntimeBootstrapResult(
        false, null, null, null, "Not bootstrapped", 0, false, false
    );

    /**
     * Creates a successful bootstrap result.
     */
    public static RuntimeBootstrapResult success(
        RuntimeConfiguration configuration,
        MinecraftServerController controller,
        MinecraftRuntimeAdapter adapter,
        long durationMs
    ) {
        return new RuntimeBootstrapResult(
            true, configuration, controller, adapter, null, durationMs, true, true
        );
    }

    /**
     * Creates a failed bootstrap result.
     */
    public static RuntimeBootstrapResult failure(
        RuntimeConfiguration configuration,
        String errorMessage,
        long durationMs
    ) {
        return new RuntimeBootstrapResult(
            false, configuration, null, null, errorMessage, durationMs, false, false
        );
    }

    /**
     * Returns an empty result representing a pre-bootstrap state.
     */
    public static RuntimeBootstrapResult empty() {
        return EMPTY;
    }
}


