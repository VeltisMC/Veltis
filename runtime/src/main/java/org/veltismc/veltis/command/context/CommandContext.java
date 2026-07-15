package org.veltismc.veltis.command.context;

import org.veltismc.veltis.command.Command;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable context for a single command execution.
 *
 * <p>Carries the {@link CommandSource}, the {@link Command}
 * definition, and the parsed arguments.
 *
 * @param source  who executed the command
 * @param command the command definition
 * @param args    positional arguments (split by whitespace)
 */
public record CommandContext(CommandSource source, Command command, String[] args) {

    /**
     * Returns the argument at the given index.
     *
     * @param index zero-based argument index
     * @return the argument, or empty string if out of bounds
     */
    public String arg(int index) {
        return index >= 0 && index < args.length ? args[index] : "";
    }

    /**
     * Returns the argument at the given index as an integer.
     *
     * @param index zero-based argument index
     * @return the parsed integer
     * @throws NumberFormatException if the argument is not a valid integer
     */
    public int argAsInt(int index) {
        return Integer.parseInt(arg(index));
    }

    /**
     * Returns true if there are at least {@code count} arguments.
     */
    public boolean hasArgs(int count) {
        return args.length >= count;
    }

    /**
     * Returns the number of arguments.
     */
    public int argCount() {
        return args.length;
    }

    /**
     * Joins all arguments starting from the given index into a single string.
     */
    public String joinArgs(int fromIndex) {
        if (fromIndex >= args.length) return "";
        return String.join(" ", Arrays.copyOfRange(args, fromIndex, args.length));
    }

    /**
     * Returns all arguments as an immutable list.
     */
    public List<String> argsList() {
        return List.of(args);
    }

    /**
     * Sends a message to the command source.
     */
    public void reply(String message) {
        source.sendMessage(message);
    }

    /**
     * Sends an error message to the command source.
     */
    public void error(String message) {
        source.sendMessage("§c" + message);
    }

    /**
     * Sends a success message to the command source.
     */
    public void success(String message) {
        source.sendMessage("§a" + message);
    }
}


