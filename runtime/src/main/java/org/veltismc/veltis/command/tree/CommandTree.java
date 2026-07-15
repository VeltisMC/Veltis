package org.veltismc.veltis.command.tree;

import org.veltismc.veltis.command.Command;
import org.veltismc.veltis.command.CommandRegistry;
import org.veltismc.veltis.command.execution.CommandExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A hierarchical command tree built from {@link CommandNode}s.
 *
 * <p>Provides path-based lookup and auto-completion. The tree
 * can be registered with a {@link CommandRegistry} in one call.
 *
 * <p>Usage:
 * <pre>{@code
 * var tree = CommandTree.create("VeltisMC")
 *     .then(CommandNode.leaf("status", ctx -> ...))
 *     .then(CommandNode.leaf("tps", ctx -> ...));
 * registry.register(tree.root());
 * }</pre>
 */
public final class CommandTree {

    private final CommandNode root;

    private CommandTree(CommandNode root) {
        this.root = root;
    }

    /**
     * Creates a new tree with the given root name.
     */
    public static CommandTree create(String rootName) {
        return new CommandTree(CommandNode.branch(rootName));
    }

    /**
     * Creates a tree from an existing root node.
     */
    public static CommandTree from(CommandNode root) {
        return new CommandTree(root);
    }

    /**
     * Adds a child node to the root.
     */
    public CommandTree then(CommandNode child) {
        var newRoot = root.withChildren(child);
        return new CommandTree(newRoot);
    }

    /**
     * Adds children to the root.
     */
    public CommandTree then(CommandNode... children) {
        var newRoot = root.withChildren(children);
        return new CommandTree(newRoot);
    }

    /**
     * Adds a nested chain under a path.
     * E.g. tree.path("admin", "whitelist", "add") -> VeltisMC admin whitelist add
     */
    public CommandTree path(CommandExecutor executor, String... path) {
        if (path.length == 0) return this;

        var node = CommandNode.leaf(path[path.length - 1], executor);
        for (int i = path.length - 2; i >= 0; i--) {
            node = CommandNode.branch(path[i], node);
        }
        return then(node);
    }

    /**
     * Returns the root node of this tree.
     */
    public CommandNode root() {
        return root;
    }

    /**
     * Returns the root name.
     */
    public String rootName() {
        return root.name();
    }

    /**
     * Finds a command node by path.
     * E.g. {@code tree.find("status")} returns the status child.
     */
    public Optional<CommandNode> find(String... path) {
        CommandNode current = root;
        for (var segment : path) {
            var found = current.findChild(segment);
            if (found.isEmpty()) return Optional.empty();
            current = found.get();
        }
        return Optional.of(current);
    }

    /**
     * Registers this entire tree with the given registry.
     */
    public void register(CommandRegistry registry) {
        registry.register(root);
    }

    /**
     * Flattens the tree into a list of {@link Command} objects.
     */
    public List<Command> flatten() {
        var commands = new ArrayList<Command>();
        flattenNode(root, commands);
        return List.copyOf(commands);
    }

    private void flattenNode(CommandNode node, List<Command> commands) {
        if (node.executor() != null) {
            var fullName = node.name();
            commands.add(Command.of(fullName, node.description(), node.executor()));
        }
        for (var child : node.children()) {
            flattenNode(child, commands);
        }
    }
}



