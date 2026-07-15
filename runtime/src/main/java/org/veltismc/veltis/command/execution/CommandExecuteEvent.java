package org.veltismc.veltis.command.execution;

import org.veltismc.veltis.command.Command;
import org.veltismc.veltis.command.context.CommandContext;
import org.veltismc.veltis.server.event.Event;

import java.util.Objects;

/**
 * Fired before and after a command executes.
 *
 * <p>Cancelling the event during the {@link Phase#PRE_EXECUTE}
 * phase prevents the command from running.
 */
public final class CommandExecuteEvent implements Event {

    private final Command command;
    private final CommandContext context;
    private final Phase phase;
    private boolean cancelled;

    /**
     * Creates a new execution event.
     *
     * @param command the command being executed
     * @param context the execution context
     * @param phase   whether this is pre or post execution
     */
    public CommandExecuteEvent(Command command, CommandContext context, Phase phase) {
        this.command = Objects.requireNonNull(command, "command");
        this.context = Objects.requireNonNull(context, "context");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.cancelled = false;
    }

    /**
     * The command being executed.
     */
    public Command command() {
        return command;
    }

    /**
     * The execution context.
     */
    public CommandContext context() {
        return context;
    }

    /**
     * Whether this is pre or post execution.
     */
    public Phase phase() {
        return phase;
    }

    /**
     * Whether this event has been cancelled (PRE_EXECUTE only).
     */
    public boolean cancelled() {
        return cancelled;
    }

    /**
     * Sets the cancelled flag. Only meaningful during {@link Phase#PRE_EXECUTE}.
     */
    public void cancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * Execution phase relative to the command handler.
     */
    public enum Phase {
        PRE_EXECUTE,
        POST_EXECUTE
    }
}


