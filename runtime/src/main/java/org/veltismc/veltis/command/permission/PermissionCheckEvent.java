package org.veltismc.veltis.command.permission;

import org.veltismc.veltis.command.context.CommandSource;
import org.veltismc.veltis.server.event.Event;

/**
 * Fired when a permission check is performed.
 *
 * <p>Listeners can modify the result by calling
 * {@link #withResult(boolean)}.
 *
 * @param source     the source being checked
 * @param permission the permission being checked
 * @param result     the current check result
 */
public record PermissionCheckEvent(
    CommandSource source,
    Permission permission,
    boolean result
) implements Event {

    /**
     * Creates a new event with an updated result.
     */
    public PermissionCheckEvent withResult(boolean result) {
        return new PermissionCheckEvent(source, permission, result);
    }
}


