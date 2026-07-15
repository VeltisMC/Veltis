package org.veltismc.veltis.command.permission;

import org.veltismc.veltis.command.context.CommandSource;
import org.veltismc.veltis.server.event.EventBus;

import java.lang.System.Logger;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe manager for permission checking and assignment.
 *
 * <p>Supports:
 * <ul>
 *   <li>Direct permission assignments per source UUID</li>
 *   <li>Group-based permissions</li>
 *   <li>Temporary permission attachments</li>
 *   <li>Wildcard matching via {@link Permission#implies(Permission)}</li>
 *   <li>Permission check events via {@link EventBus}</li>
 * </ul>
 */
public final class PermissionManager {

    private static final Logger LOG = System.getLogger(PermissionManager.class.getName());

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<Permission>> directPermissions;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<PermissionAttachment>> attachments;
    private final CopyOnWriteArrayList<PermissionGroup> groups;
    private final EventBus eventBus;

    /**
     * Creates a new permission manager.
     *
     * @param eventBus the event bus for publishing permission events
     */
    public PermissionManager(EventBus eventBus) {
        this.directPermissions = new ConcurrentHashMap<>();
        this.attachments = new ConcurrentHashMap<>();
        this.groups = new CopyOnWriteArrayList<>();
        this.eventBus = eventBus;
    }

    /**
     * Grants a permission to a source.
     *
     * @param source     the command source
     * @param permission the permission to grant
     */
    public void grant(CommandSource source, Permission permission) {
        directPermissions.computeIfAbsent(
            source.uniqueId(), k -> new CopyOnWriteArrayList<>()
        ).add(permission);
    }

    /**
     * Revokes a permission from a source.
     *
     * @param source     the command source
     * @param permission the permission to revoke
     * @return true if the permission was found and removed
     */
    public boolean revoke(CommandSource source, Permission permission) {
        var perms = directPermissions.get(source.uniqueId());
        return perms != null && perms.remove(permission);
    }

    /**
     * Adds a temporary permission attachment to a source.
     *
     * @param source     the command source
     * @param attachment the permission attachment
     */
    public void attach(CommandSource source, PermissionAttachment attachment) {
        attachments.computeIfAbsent(
            source.uniqueId(), k -> new CopyOnWriteArrayList<>()
        ).add(attachment);
    }

    /**
     * Removes a permission attachment.
     *
     * @param source     the command source
     * @param attachment the attachment to remove
     * @return true if found and removed
     */
    public boolean detach(CommandSource source, PermissionAttachment attachment) {
        var sourceAttachments = attachments.get(source.uniqueId());
        return sourceAttachments != null && sourceAttachments.remove(attachment);
    }

    /**
     * Registers a permission group.
     */
    public void registerGroup(PermissionGroup group) {
        groups.add(group);
    }

    /**
     * Unregisters a permission group.
     */
    public boolean unregisterGroup(PermissionGroup group) {
        return groups.remove(group);
    }

    /**
     * Returns all registered groups.
     */
    public Collection<PermissionGroup> groups() {
        return List.copyOf(groups);
    }

    /**
     * Checks whether a source has a specific permission.
     *
     * <p>Checks in order:
     * <ol>
     *   <li>Direct permissions for wildcard/implicit match</li>
     *   <li>Group permissions</li>
     *   <li>Attachment permissions</li>
     *   <li>Operator status (operators get all)</li>
     *   <li>Console/system always have all permissions</li>
     * </ol>
     *
     * @param source     the command source
     * @param permission the permission to check
     * @return true if the source has this permission
     */
    public boolean hasPermission(CommandSource source, Permission permission) {
        if (source.isConsole()) return true;

        // Fire permission check event
        var event = new PermissionCheckEvent(source, permission, false);
        eventBus.publish(event);

        // Check direct permissions
        var perms = directPermissions.get(source.uniqueId());
        if (perms != null) {
            for (var p : perms) {
                if (p.implies(permission)) return true;
            }
        }

        // Check group permissions
        for (var group : groups) {
            if (group.members().contains(source.uniqueId())) {
                for (var p : group.permissions()) {
                    if (p.implies(permission)) return true;
                }
            }
        }

        // Check attachments
        var sourceAttachments = attachments.get(source.uniqueId());
        if (sourceAttachments != null) {
            for (var attachment : sourceAttachments) {
                if (!attachment.expired() && attachment.permission().implies(permission)) {
                    return !attachment.negated();
                }
            }
        }

        // Operators have all permissions
        if (source.operator()) return true;

        return false;
    }

    /**
     * Checks a permission by node string.
     *
     * @param source     the command source
     * @param permission the permission node string
     * @return true if the source has this permission
     */
    public boolean hasPermission(CommandSource source, String permission) {
        return hasPermission(source, Permission.of(permission));
    }

    /**
     * Returns all direct permissions for a source.
     */
    public List<Permission> permissions(CommandSource source) {
        var perms = directPermissions.get(source.uniqueId());
        return perms != null ? List.copyOf(perms) : List.of();
    }

    /**
     * Removes all permissions and attachments for a source.
     */
    public void clearSource(CommandSource source) {
        directPermissions.remove(source.uniqueId());
        attachments.remove(source.uniqueId());
    }
}


