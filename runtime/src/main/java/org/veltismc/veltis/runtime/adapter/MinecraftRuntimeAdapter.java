package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.runtime.RuntimeConfiguration;

/**
 * Adapts the Mojang Minecraft server classes to the VeltisMC runtime.
 *
 * <p>This is the sole bridge between VeltisMC and Mojang's server
 * implementation. All access to Mojang classes goes through this
 * interface to prevent leakage into public VeltisMC APIs.
 *
 * <p>Implementations use reflection or a separately-compiled
 * dependency to interact with {@code net.minecraft.server.MinecraftServer}
 * and related classes.
 *
 * <p>No Mojang types appear in this interface or any VeltisMC API.
 */
public interface MinecraftRuntimeAdapter {

    /**
     * Starts the Minecraft server with the given configuration.
     *
     * <p>This method blocks until the server is fully started or
     * fails. The implementation should:
     * <ol>
     *   <li>Resolve the Minecraft server class via reflection</li>
     *   <li>Configure the server (port, online-mode, etc.)</li>
     *   <li>Invoke the server's start method</li>
     *   <li>Wait for the server to reach a running state</li>
     * </ol>
     *
     * @param configuration the runtime configuration
     * @throws Exception if the server fails to start
     */
    void start(RuntimeConfiguration configuration) throws Exception;

    /**
     * Stops the Minecraft server gracefully.
     *
     * <p>Blocks until the server has fully stopped.
     *
     * @throws Exception if the server fails to stop
     */
    void stop() throws Exception;

    /**
     * Returns true if the Minecraft server is currently running.
     */
    boolean isRunning();

    /**
     * Returns true if the server has finished loading and is
     * accepting connections.
     */
    boolean isReady();

    /**
     * Returns the server's current tick count, or -1 if not available.
     */
    long tickCount();

    /**
     * Returns the maximum player count configured on the server.
     */
    int maxPlayers();

    /**
     * Returns the number of currently connected players.
     */
    int playerCount();

    /**
     * Returns the port the server is bound to.
     */
    int port();

    /**
     * Returns the server's MOTD string.
     */
    String motd();

    /**
     * Returns the raw Mojang server instance if available, or null.
     * Used by internal adapters to extract players, worlds, and entities.
     */
    Object serverInstance();
}



