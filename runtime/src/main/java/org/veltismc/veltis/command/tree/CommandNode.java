package org.veltismc.veltis.command.tree;

import org.veltismc.veltis.command.execution.CommandExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A node in the command tree hierarchy.
 *
 * <p>Each node has a name, optional aliases, optional executor,
 * optional permission string, and a list of child nodes.
 * Nodes form a tree structure for hierarchical commands.
 *
 * @param name        the node name
 * @param aliases     alternative names for this node
 * @param executor    optional executor (null if this is a branch node)
 * @param permission  optional permission string required
 * @param description human-readable description
 * @param children    child nodes
 */
public record CommandNode(
    String name,
    Set<String> aliases,
    CommandExecutor executor,
    String permission,
    String description,
    List<CommandNode> children
) {

    /**
     * Creates a branch node (no executor) with the given children.
     */
    public static CommandNode branch(String name, CommandNode... children) {
        return new CommandNode(name, Set.of(), null, null, "", List.of(children));
    }

    /**
     * Creates a leaf node (with executor).
     */
    public static CommandNode leaf(String name, CommandExecutor executor) {
        return new CommandNode(name, Set.of(), executor, null, "", List.of());
    }

    /**
     * Creates a leaf node with a permission requirement.
     */
    public static CommandNode guarded(String name, String permission, CommandExecutor executor) {
        return new CommandNode(name, Set.of(), executor, permission, "", List.of());
    }

    /**
     * Returns a new node with the given aliases.
     */
    public CommandNode withAliases(String... aliases) {
        var merged = new java.util.HashSet<>(this.aliases);
        Collections.addAll(merged, aliases);
        return new CommandNode(name, Set.copyOf(merged), executor, permission, description, children);
    }

    /**
     * Returns a new node with the given description.
     */
    public CommandNode withDescription(String description) {
        return new CommandNode(name, aliases, executor, permission, description, children);
    }

    /**
     * Returns a new node with additional children appended.
     */
    public CommandNode withChildren(CommandNode... extra) {
        var merged = new ArrayList<CommandNode>(children);
        Collections.addAll(merged, extra);
        return new CommandNode(name, aliases, executor, permission, description, List.copyOf(merged));
    }

    /**
     * Finds a child node by name or alias.
     */
    public Optional<CommandNode> findChild(String name) {
        for (var child : children) {
            if (child.name().equalsIgnoreCase(name)) return Optional.of(child);
            for (var alias : child.aliases()) {
                if (alias.equalsIgnoreCase(name)) return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns true if this node has an executor (i.e. is a leaf).
     */
    public boolean isExecutable() {
        return executor != null;
    }
}


