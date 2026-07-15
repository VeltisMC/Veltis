package org.veltismc.veltis.runtime;

import java.nio.file.Path;

/**
 * Configuration for the Minecraft runtime.
 *
 * <p>Immutable record holding all parameters needed to initialize
 * and start the underlying Minecraft server process.
 *
 * @param serverName       display name for this server
 * @param port             network port to bind (default 25565)
 * @param maxPlayers       maximum concurrent players
 * @param minecraftVersion target Minecraft version (e.g. "26.2")
 * @param protocolVersion  network protocol version for this release
 * @param serverDirectory  root directory for server files
 * @param worldDirectory   directory for world data
 * @param levelName        name of the default world / level
 * @param onlineMode       whether to authenticate players with Mojang
 * @param allowFlight      whether flight is permitted
 * @param maxBuildHeight   maximum Y height for building
 * @param viewDistance     chunk view distance (radius)
 * @param simulationDistance chunk simulation distance
 * @param motd             message of the day displayed in the server list
 */
public record RuntimeConfiguration(
    String serverName,
    int port,
    int maxPlayers,
    String minecraftVersion,
    int protocolVersion,
    Path serverDirectory,
    Path worldDirectory,
    String levelName,
    boolean onlineMode,
    boolean allowFlight,
    int maxBuildHeight,
    int viewDistance,
    int simulationDistance,
    String motd
) {

    /**
     * Creates a default configuration suitable for development and testing.
     *
     * @param serverDirectory the root server directory
     * @return a sensible default configuration
     */
    public static RuntimeConfiguration defaults(Path serverDirectory) {
        return new RuntimeConfiguration(
            "VeltisMC Server",
            25565,
            20,
            "26.2",
            768,
            serverDirectory,
            serverDirectory.resolve("worlds"),
            "world",
            false,
            false,
            320,
            10,
            10,
            "A VeltisMC Minecraft Server"
        );
    }

    /**
     * Returns the server directory as an absolute path.
     */
    public Path absoluteServerDirectory() {
        return serverDirectory.toAbsolutePath();
    }

    /**
     * Returns the world directory as an absolute path.
     */
    public Path absoluteWorldDirectory() {
        return worldDirectory.toAbsolutePath();
    }
}



