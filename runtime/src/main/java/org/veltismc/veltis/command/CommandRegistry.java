package org.veltismc.veltis.command;

import org.veltismc.veltis.command.tree.CommandNode;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry for command definitions.
 *
 * <p>Provides thread-safe registration and lookup of commands
 * by name. All {@link Command} instances are immutable.
 *
 * <p>Commands can be registered individually or as part of a
 * command tree via {@link CommandNode}.
 */
public interface CommandRegistry {

    /**
     * Registers a single command.
     *
     * @param command the command to register
     * @throws IllegalStateException if a command with the same name is already registered
     */
    void register(Command command);

    /**
     * Registers a command tree node and all its children recursively.
     *
     * @param node the root node of the tree to register
     */
    void register(CommandNode node);

    /**
     * Unregisters a command by name.
     *
     * @param name the command name
     * @return the unregistered command, or empty if not found
     */
    Optional<Command> unregister(String name);

    /**
     * Looks up a command by name or alias.
     *
     * @param name the command name or alias
     * @return the command, or empty if not found
     */
    Optional<Command> get(String name);

    /**
     * Returns true if a command with the given name is registered.
     */
    boolean contains(String name);

    /**
     * Returns an immutable snapshot of all registered commands.
     */
    Collection<Command> all();

    /**
     * Returns the number of registered commands.
     */
    int count();

    /**
     * Removes all registered commands.
     */
    void clear();
}


