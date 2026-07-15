package org.veltismc.veltis.command;

/**
 * Result of executing a command.
 *
 * <p>Immutable record carrying the outcome, a message for the
 * sender, and an optional error code.
 *
 * @param success  whether execution succeeded
 * @param message  message to send back to the command source
 * @param errorCode optional numeric error code for programmatic handling
 */
public record CommandResult(boolean success, String message, int errorCode) {

    public static final CommandResult SUCCESS = new CommandResult(true, "", 0);
    public static final CommandResult NO_PERMISSION = new CommandResult(false, "No permission", -1);
    public static final CommandResult NOT_FOUND = new CommandResult(false, "Command not found", -2);
    public static final CommandResult INVALID_SYNTAX = new CommandResult(false, "Invalid syntax", -3);
    public static final CommandResult INTERNAL_ERROR = new CommandResult(false, "Internal error", -4);

    /**
     * Creates a success result with a message.
     */
    public static CommandResult success(String message) {
        return new CommandResult(true, message, 0);
    }

    /**
     * Creates a failure result with a message.
     */
    public static CommandResult failure(String message) {
        return new CommandResult(false, message, 1);
    }

    /**
     * Creates a failure result with a message and error code.
     */
    public static CommandResult failure(String message, int errorCode) {
        return new CommandResult(false, message, errorCode);
    }
}


