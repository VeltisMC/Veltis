package org.veltismc.veltis.command.tree;

/**
 * A command node that accepts a typed argument value.
 *
 * <p>Used for commands that take parameters (e.g. names, numbers).
 * The argument is parsed at dispatch time and available in the
 * context.
 *
 * @param name        the argument name (for help/usage display)
 * @param type        the argument type identifier
 * @param required    whether the argument is mandatory
 * @param description human-readable description
 */
public record ArgumentNode(
    String name,
    String type,
    boolean required,
    String description
) {

    public static final String TYPE_STRING = "string";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_FLOAT = "float";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_PLAYER = "player";
    public static final String TYPE_WORLD = "world";

    /**
     * Creates a required string argument.
     */
    public static ArgumentNode required(String name) {
        return new ArgumentNode(name, TYPE_STRING, true, "");
    }

    /**
     * Creates an optional argument.
     */
    public static ArgumentNode optional(String name) {
        return new ArgumentNode(name, TYPE_STRING, false, "");
    }

    /**
     * Creates a typed required argument.
     */
    public static ArgumentNode typed(String name, String type) {
        return new ArgumentNode(name, type, true, "");
    }

    /**
     * Creates a required integer argument.
     */
    public static ArgumentNode integer(String name) {
        return new ArgumentNode(name, TYPE_INTEGER, true, "");
    }

    /**
     * Creates a required player argument.
     */
    public static ArgumentNode player(String name) {
        return new ArgumentNode(name, TYPE_PLAYER, true, "");
    }

    /**
     * Returns a new node with the given description.
     */
    public ArgumentNode withDescription(String description) {
        return new ArgumentNode(name, type, required, description);
    }

    /**
     * Returns the usage string for this argument.
     */
    public String usageString() {
        return required ? "<" + name + ">" : "[" + name + "]";
    }
}


