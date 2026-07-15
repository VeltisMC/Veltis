package org.veltismc.veltis.command;

import org.veltismc.veltis.command.context.CommandContext;
import org.veltismc.veltis.command.context.CommandSource;
import org.veltismc.veltis.command.execution.CommandExecuteEvent;
import org.veltismc.veltis.command.execution.CommandRegisteredEvent;
import org.veltismc.veltis.command.permission.Permission;
import org.veltismc.veltis.command.permission.PermissionManager;
import org.veltismc.veltis.command.tree.CommandNode;
import org.veltismc.veltis.server.event.EventBus;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central command dispatcher and manager.
 *
 * <p>Handles registration, dispatch, permission checking, and
 * event publishing for all commands. Thread-safe.
 */
public final class CommandManager implements CommandRegistry {

    private static final Logger LOG = System.getLogger(CommandManager.class.getName());

    private final ConcurrentHashMap<String, Command> byName;
    private final CopyOnWriteArrayList<Command> allCommands;
    private final EventBus eventBus;
    private final PermissionManager permissionManager;

    /**
     * Creates a new command manager.
     *
     * @param eventBus          the event bus for publishing command events
     * @param permissionManager the permission manager for access control
     */
    public CommandManager(EventBus eventBus, PermissionManager permissionManager) {
        this.byName = new ConcurrentHashMap<>();
        this.allCommands = new CopyOnWriteArrayList<>();
        this.eventBus = eventBus;
        this.permissionManager = permissionManager;
    }

    @Override
    public void register(Command command) {
        var existing = byName.putIfAbsent(command.name(), command);
        if (existing != null) {
            throw new IllegalStateException(
                "Command already registered: " + command.name());
        }
        allCommands.add(command);

        for (var alias : command.aliases()) {
            byName.putIfAbsent(alias, command);
        }

        eventBus.publish(new CommandRegisteredEvent(command, this));
        LOG.log(Level.DEBUG, "Registered command: {0}", command.name());
    }

    @Override
    public void register(CommandNode node) {
        registerFromNode(node);
    }

    private void registerFromNode(CommandNode node) {
        if (node.executor() != null) {
            var perm = node.permission() != null
                ? Optional.of(Permission.of(node.permission(), ""))
                : Optional.<Permission>empty();
            var cmd = new Command(
                node.name(), node.aliases(),
                perm, node.description(),
                "", node.executor()
            );
            try {
                register(cmd);
            } catch (IllegalStateException e) {
                LOG.log(Level.WARNING, "Duplicate command registration: {0}", node.name());
            }
        }
        for (var child : node.children()) {
            registerFromNode(child);
        }
    }

    @Override
    public Optional<Command> unregister(String name) {
        var cmd = byName.remove(name);
        if (cmd != null) {
            allCommands.remove(cmd);
            for (var alias : cmd.aliases()) {
                byName.remove(alias, cmd);
            }
            return Optional.of(cmd);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Command> get(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase()));
    }

    @Override
    public boolean contains(String name) {
        return byName.containsKey(name.toLowerCase());
    }

    @Override
    public Collection<Command> all() {
        return List.copyOf(allCommands);
    }

    @Override
    public int count() {
        return byName.size();
    }

    @Override
    public void clear() {
        byName.clear();
        allCommands.clear();
    }

    /**
     * Executes a command from raw input string.
     */
    public CommandResult execute(CommandSource source, String input) {
        if (input == null || input.isBlank()) {
            return CommandResult.INVALID_SYNTAX;
        }
        var parts = input.trim().split("\\s+");
        var commandName = parts[0].toLowerCase();
        var args = parts.length > 1
            ? java.util.Arrays.copyOfRange(parts, 1, parts.length)
            : new String[0];
        return execute(source, commandName, args);
    }

    /**
     * Executes a command by name with arguments.
     */
    public CommandResult execute(CommandSource source, String name, String... args) {
        var cmd = get(name);
        if (cmd.isEmpty()) {
            return CommandResult.NOT_FOUND;
        }
        var command = cmd.get();

        if (command.permission().isPresent()) {
            var perm = command.permission().get();
            if (!permissionManager.hasPermission(source, perm)) {
                return CommandResult.NO_PERMISSION;
            }
        }

        var context = new CommandContext(source, command, args);

        var executeEvent = new CommandExecuteEvent(command, context, CommandExecuteEvent.Phase.PRE_EXECUTE);
        eventBus.publish(executeEvent);
        if (executeEvent.cancelled()) {
            return CommandResult.failure("Command execution cancelled");
        }

        try {
            var result = command.executor().execute(context);
            eventBus.publish(new CommandExecuteEvent(command, context, CommandExecuteEvent.Phase.POST_EXECUTE));
            return result;
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Command execution failed", e);
            return CommandResult.INTERNAL_ERROR;
        }
    }

    /**
     * Returns the permission manager used by this command manager.
     */
    public PermissionManager permissionManager() {
        return permissionManager;
    }
}


