package org.veltismc.veltis.command.tree;

import org.veltismc.veltis.command.execution.CommandExecutor;

/**
 * A literal (fixed-string) command node.
 *
 * <p>Matches a specific literal string. Used for hierarchical
 * command branches like {@code /VeltisMC status}.
 *
 * @param text the literal text to match
 */
public record LiteralNode(String text) {

    /**
     * Creates a literal node from the given text.
     */
    public static LiteralNode literal(String text) {
        return new LiteralNode(text);
    }

    /**
     * Returns true if the given input matches this literal (case-insensitive).
     */
    public boolean matches(String input) {
        return text.equalsIgnoreCase(input);
    }

    /**
     * Creates a command node wrapper for this literal.
     */
    public CommandNode toNode(CommandNode... children) {
        return CommandNode.branch(text, children);
    }

    /**
     * Creates an executable command node for this literal.
     */
    public CommandNode toNode(CommandExecutor executor) {
        return CommandNode.leaf(text, executor);
    }
}



