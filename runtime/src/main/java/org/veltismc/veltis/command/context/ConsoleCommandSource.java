package org.veltismc.veltis.command.context;

import org.veltismc.veltis.command.permission.Permission;

import java.lang.System.Logger;
import java.util.UUID;

/**
 * Command source representing the server console.
 *
 * <p>The console has all permissions and is always an operator.
 * Messages are routed to {@link System#out}.
 */
public final class ConsoleCommandSource implements CommandSource {

    private static final Logger LOG = System.getLogger(ConsoleCommandSource.class.getName());

    private static final UUID CONSOLE_UUID = new UUID(0, 0);
    private static final ConsoleCommandSource INSTANCE = new ConsoleCommandSource();

    private ConsoleCommandSource() {
    }

    /**
     * Returns the singleton console source instance.
     */
    public static ConsoleCommandSource instance() {
        return INSTANCE;
    }

    @Override
    public UUID uniqueId() {
        return CONSOLE_UUID;
    }

    @Override
    public String name() {
        return "Console";
    }

    @Override
    public boolean operator() {
        return true;
    }

    @Override
    public boolean isPlayer() {
        return false;
    }

    @Override
    public boolean isConsole() {
        return true;
    }

    @Override
    public void sendMessage(String message) {
        LOG.log(Logger.Level.INFO, message);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return true;
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }
}


