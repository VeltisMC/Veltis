package org.veltismc.veltis.runtime.minecraft;

import org.veltismc.veltis.runtime.RuntimeState;

import java.util.concurrent.CompletableFuture;

/**
 * Controls the lifecycle of the Minecraft server process.
 *
 * <p>Provides asynchronous start, stop, and restart operations.
 * All operations return {@link CompletableFuture} for non-blocking
 * orchestration.
 *
 * <p>State transitions are validated internally — illegal transitions
 * complete the future exceptionally with {@link IllegalStateException}.
 */
public interface MinecraftServerController {

    /**
     * Starts the Minecraft server asynchronously.
     *
     * <p>The returned future completes when the server reaches
     * the RUNNING state, or exceptionally on failure.
     *
     * @return a future that completes when the server is running
     */
    CompletableFuture<Void> startServer();

    /**
     * Stops the Minecraft server asynchronously.
     *
     * <p>The returned future completes when the server reaches
     * the STOPPED state, or exceptionally on failure.
     *
     * @return a future that completes when the server has stopped
     */
    CompletableFuture<Void> stopServer();

    /**
     * Restarts the server by stopping and starting again.
     *
     * @return a future that completes when the server is running again
     */
    CompletableFuture<Void> restartServer();

    /**
     * Returns the current runtime state.
     */
    RuntimeState state();

    /**
     * Returns true if the server is in an active state (STARTING or RUNNING).
     */
    boolean isRunning();

    /**
     * Returns the server tick count, or zero if not running.
     */
    long tickCount();

    /**
     * Returns the server uptime in milliseconds, or zero if not running.
     */
    long uptimeMs();

    /**
     * Returns true if the server has finished loading and is ready.
     */
    boolean isReady();
}


