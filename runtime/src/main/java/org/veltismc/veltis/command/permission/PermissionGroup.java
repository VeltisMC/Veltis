package org.veltismc.veltis.command.permission;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A named group of permissions assigned to members.
 *
 * <p>Groups provide a way to manage permissions for multiple
 * sources at once. Members are referenced by their source UUID.
 *
 * @param name        the group name (e.g. "admin", "moderator")
 * @param permissions the permissions granted to all members
 * @param members     the UUIDs of sources in this group
 * @param priority    sort priority for conflict resolution (higher = more priority)
 */
public record PermissionGroup(
    String name,
    List<Permission> permissions,
    Set<UUID> members,
    int priority
) {

    /**
     * Creates a named group with no initial members.
     */
    public static PermissionGroup named(String name, Permission... permissions) {
        return new PermissionGroup(name, List.of(permissions), Set.of(), 0);
    }

    /**
     * Creates a named group with a priority.
     */
    public static PermissionGroup ranked(String name, int priority, Permission... permissions) {
        return new PermissionGroup(name, List.of(permissions), Set.of(), priority);
    }

    /**
     * Adds a member to this group.
     */
    public PermissionGroup withMember(UUID uuid) {
        var newMembers = new java.util.HashSet<>(members);
        newMembers.add(uuid);
        return new PermissionGroup(name, permissions, Set.copyOf(newMembers), priority);
    }

    /**
     * Adds multiple members.
     */
    public PermissionGroup withMembers(UUID... uuids) {
        var newMembers = new java.util.HashSet<>(members);
        java.util.Collections.addAll(newMembers, uuids);
        return new PermissionGroup(name, permissions, Set.copyOf(newMembers), priority);
    }

    /**
     * Returns a new group with an additional permission.
     */
    public PermissionGroup withPermission(Permission permission) {
        var newPerms = new java.util.ArrayList<>(permissions);
        newPerms.add(permission);
        return new PermissionGroup(name, List.copyOf(newPerms), members, priority);
    }

    /**
     * Returns a new group with additional permissions.
     */
    public PermissionGroup withPermissions(Permission... extra) {
        var newPerms = new java.util.ArrayList<>(permissions);
        java.util.Collections.addAll(newPerms, extra);
        return new PermissionGroup(name, List.copyOf(newPerms), members, priority);
    }
}


