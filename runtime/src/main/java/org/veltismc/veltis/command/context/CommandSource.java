package org.veltismc.veltis.command.context;

import org.veltismc.veltis.command.permission.Permission;

import java.util.UUID;

/**
 * Base interface for who or what executed a command.
 *
 * <p>Provides identity, messaging, and permission checking
 * regardless of whether the source is a player, the console,
 * or an internal system component.
 */
public interface CommandSource {

    /**
     * A unique identifier for this source.
     */
    UUID uniqueId();

    /**
     * Human-readable name for this source (e.g. "Console", player username).
     */
    String name();

    /**
     * Whether this source has operator privileges.
     */
    boolean operator();

    /**
     * Whether this source is a player.
     */
    boolean isPlayer();

    /**
     * Whether this source is the console.
     */
    boolean isConsole();

    /**
     * Sends a message to this source.
     */
    void sendMessage(String message);

    /**
     * Checks whether this source has the given permission.
     *
     * @param permission the permission node to check
     * @return true if the source has this permission
     */
    boolean hasPermission(Permission permission);

    /**
     * Checks whether this source has the given permission string.
     *
     * @param permission the permission string (e.g. "VeltisMC.admin")
     * @return true if the source has this permission
     */
    boolean hasPermission(String permission);
}



