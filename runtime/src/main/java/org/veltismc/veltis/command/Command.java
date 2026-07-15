package org.veltismc.veltis.command;

import org.veltismc.veltis.command.execution.CommandExecutor;
import org.veltismc.veltis.command.permission.Permission;

import java.util.Optional;

/**
 * Definition of a single command.
 *
 * <p>Immutable command descriptor containing the command name,
 * aliases, permission requirement, description, and the executor
 * logic. Commands are registered with the {@link CommandRegistry}
 * and dispatched by the {@link CommandManager}.
 *
 * @param name        primary command name (lowercase, no spaces)
 * @param aliases     alternative names that invoke this command
 * @param permission  optional permission required to execute
 * @param description human-readable description
 * @param usage       usage string (e.g. "&lt;player&gt; [reason]")
 * @param executor    the logic run when this command is executed
 */
public record Command(
    String name,
    java.util.Set<String> aliases,
    Optional<Permission> permission,
    String description,
    String usage,
    CommandExecutor executor
) {

    /**
     * Creates a command with no aliases or permission.
     */
    public static Command of(String name, String description, CommandExecutor executor) {
        return new Command(
            name, java.util.Set.of(),
            Optional.empty(), description, "", executor
        );
    }

    /**
     * Creates a command with a permission requirement.
     */
    public static Command guarded(
        String name, String description,
        Permission permission, CommandExecutor executor
    ) {
        return new Command(
            name, java.util.Set.of(),
            Optional.of(permission), description, "", executor
        );
    }

    /**
     * Returns a new Command with the given aliases added.
     */
    public Command withAliases(String... aliases) {
        var merged = new java.util.HashSet<>(this.aliases);
        java.util.Collections.addAll(merged, aliases);
        return new Command(name, java.util.Set.copyOf(merged), permission, description, usage, executor);
    }

    /**
     * Returns a new Command with the given usage string.
     */
    public Command withUsage(String usage) {
        return new Command(name, aliases, permission, description, usage, executor);
    }

    /**
     * Returns a new Command with the given description.
     */
    public Command withDescription(String description) {
        return new Command(name, aliases, permission, description, usage, executor);
    }
}


