package org.veltismc.veltis.command.execution;

import org.veltismc.veltis.command.CommandResult;
import org.veltismc.veltis.command.context.CommandContext;

/**
 * Functional interface for command execution logic.
 *
 * <p>Implementations receive a {@link CommandContext} with the
 * source, command definition, and parsed arguments, and return
 * a {@link CommandResult}.
 */
@FunctionalInterface
public interface CommandExecutor {

    /**
     * Executes the command.
     *
     * @param context the execution context
     * @return the result of execution
     */
    CommandResult execute(CommandContext context);
}


