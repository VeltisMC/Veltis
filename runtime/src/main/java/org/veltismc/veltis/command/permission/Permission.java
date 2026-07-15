package org.veltismc.veltis.command.permission;

/**
 * Immutable permission node.
 *
 * <p>Permissions follow a hierarchical dot-separated format:
 * <pre>
 * VeltisMC.admin
 * VeltisMC.command.status
 * VeltisMC.command.*
 * *
 * </pre>
 *
 * <p>Wildcards ({@code *}) match any sub-node at their level.
 *
 * @param node  the permission node string (e.g. "VeltisMC.command.status")
 * @param description human-readable description
 */
public record Permission(String node, String description) {

    /**
     * Creates a permission from a node string.
     */
    public static Permission of(String node) {
        return new Permission(node, "");
    }

    /**
     * Creates a permission from a node string with description.
     */
    public static Permission of(String node, String description) {
        return new Permission(node, description);
    }

    /**
     * The wildcard permission that grants everything.
     */
    public static final Permission ALL = new Permission("*", "All permissions");

    /**
     * Returns true if this permission implies the given target permission.
     *
     * <p>{@code VeltisMC.command.*} implies {@code VeltisMC.command.status}.
     * {@code *} implies everything.
     * {@code VeltisMC.admin} does NOT imply {@code VeltisMC.command.status}.
     */
    public boolean implies(Permission target) {
        if (this.node.equals("*")) return true;

        var parts = node.split("\\.");
        var targetParts = target.node.split("\\.");

        if (parts.length > targetParts.length) return false;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("*")) return true;
            if (!parts[i].equals(targetParts[i])) return false;
        }

        return parts.length == targetParts.length;
    }

    /**
     * Returns true if this permission matches the given node string exactly.
     */
    public boolean matches(String node) {
        return this.node.equals(node);
    }
}



