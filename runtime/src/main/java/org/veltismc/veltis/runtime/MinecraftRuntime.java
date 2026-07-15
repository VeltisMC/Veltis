package org.veltismc.veltis.runtime;

import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.bootstrap.RuntimeBootstrapResult;
import org.veltismc.veltis.runtime.minecraft.MinecraftServerController;

/**
 * Central interface representing the Minecraft runtime within VeltisMC.
 *
 * <p>Wraps the underlying Mojang server process and exposes its state,
 * configuration, and lifecycle through controlled abstractions.
 * Mojang classes are never exposed — all access goes through
 * {@link MinecraftRuntimeAdapter}.
 *
 * <p>Every {@code MinecraftRuntime} provides:
 * <ul>
 *   <li>Lifecycle state via {@link #state()} and {@link #isRunning()}</li>
 *   <li>Immutable point-in-time snapshots via {@link #context()}</li>
 *   <li>Server control via {@link #controller()}</li>
 *   <li>Configuration via {@link #configuration()}</li>
 * </ul>
 */
public interface MinecraftRuntime {

    /**
     * Returns the current runtime state.
     */
    RuntimeState state();

    /**
     * Returns an immutable snapshot of the current runtime context.
     */
    RuntimeContext context();

    /**
     * Returns the runtime configuration used at startup.
     */
    RuntimeConfiguration configuration();

    /**
     * Returns the controller for start / stop / restart operations.
     */
    MinecraftServerController controller();

    /**
     * Returns the adapter that bridges to Mojang classes.
     */
    MinecraftRuntimeAdapter adapter();

    /**
     * Returns the bootstrap result from initialization.
     */
    RuntimeBootstrapResult bootstrapResult();

    /**
     * Initializes and starts the runtime. Blocks until the server
     * reaches the RUNNING state or fails.
     *
     * @throws IllegalStateException if already running or failed
     */
    void start();

    /**
     * Stops the runtime gracefully. Blocks until STOPPED.
     */
    void stop();

    /**
     * Returns true if the runtime is in an active state (STARTING or RUNNING).
     */
    boolean isRunning();

    /**
     * Returns true if the server has finished loading and is
     * accepting connections.
     */
    boolean isReady();

    /**
     * Returns the runtime uptime in milliseconds, or zero if not started.
     */
    long uptimeMs();

    /**
     * Returns the current tick count from the Minecraft server,
     * or zero if not available.
     */
    long tickCount();
}



