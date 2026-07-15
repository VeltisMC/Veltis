package org.veltismc.veltis.command.context;

import org.veltismc.veltis.command.permission.Permission;

import java.util.UUID;

/**
 * Command source for internal system operations.
 *
 * <p>Used when VeltisMC itself executes commands programmatically,
 * rather than a player or console operator. Has all permissions.
 */
public final class SystemCommandSource implements CommandSource {

    private static final UUID SYSTEM_UUID = new UUID(0, 1);
    private static final SystemCommandSource INSTANCE = new SystemCommandSource();

    private SystemCommandSource() {
    }

    /**
     * Returns the singleton system source instance.
     */
    public static SystemCommandSource instance() {
        return INSTANCE;
    }

    @Override
    public UUID uniqueId() {
        return SYSTEM_UUID;
    }

    @Override
    public String name() {
        return "System";
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
        return false;
    }

    @Override
    public void sendMessage(String message) {
        // System commands log rather than display
        System.getLogger("nova.command.system").log(System.Logger.Level.DEBUG, message);
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



