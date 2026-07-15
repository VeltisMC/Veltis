package org.veltismc.veltis.command.context;

import org.veltismc.veltis.command.permission.Permission;
import org.veltismc.veltis.command.permission.PermissionManager;
import org.veltismc.veltis.server.model.player.InternalPlayer;

import java.lang.System.Logger;
import java.util.Objects;
import java.util.UUID;


/**
 * Command source representing a connected player.
 *
 * <p>Delegates permission checks to the {@link PermissionManager}
 * and routes messages through the player's {@link InternalPlayer}
 * interface.
 */
public final class PlayerCommandSource implements CommandSource {

    private static final Logger LOG = System.getLogger(PlayerCommandSource.class.getName());

    private final InternalPlayer player;
    private final PermissionManager permissionManager;

    /**
     * Creates a player command source.
     *
     * @param player            the backing player model
     * @param permissionManager the permission manager for checks
     */
    public PlayerCommandSource(InternalPlayer player, PermissionManager permissionManager) {
        this.player = Objects.requireNonNull(player, "player");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager");
    }

    @Override
    public UUID uniqueId() {
        return player.uniqueId();
    }

    @Override
    public String name() {
        return player.profile().name();
    }

    @Override
    public boolean operator() {
        return player.serverPlayer()
            .map(p -> {
                try {
                    var method = p.getClass().getMethod("operator");
                    return (boolean) method.invoke(p);
                } catch (Exception e) {
                    return false;
                }
            })
            .orElse(false);
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public boolean isConsole() {
        return false;
    }

    @Override
    public void sendMessage(String message) {
        var maybeServerPlayer = player.serverPlayer();
        if (maybeServerPlayer.isEmpty()) {
            LOG.log(Logger.Level.ERROR, "Cannot send message to player {0}: serverPlayer missing",
                    player.profile().name());
            return;
        }

        var mcPlayer = maybeServerPlayer.get();
        try {
            trySendSystemMessage(mcPlayer, message);
        } catch (Exception e) {
            LOG.log(Logger.Level.ERROR, "Failed to send message to player " + player.profile().name(), e);
        }
    }

    private void trySendSystemMessage(Object mcPlayer, String message) throws Exception {
        var componentClass = Class.forName("net.minecraft.network.chat.Component");
        var literal = componentClass.getMethod("literal", String.class);
        var component = literal.invoke(null, message);
        try {
            mcPlayer.getClass().getMethod("sendSystemMessage", componentClass).invoke(mcPlayer, component);
        } catch (NoSuchMethodException e) {
            var connection = getConnection(mcPlayer);
            if (connection != null) {
                var packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
                var packet = packetClass.getConstructor(componentClass, boolean.class).newInstance(component, false);
                connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
            }
        }
    }

    private static Object getConnection(Object mcPlayer) {
        try {
            var f = mcPlayer.getClass().getField("connection");
            return f.get(mcPlayer);
        } catch (Exception e1) {
            try {
                for (var f : mcPlayer.getClass().getDeclaredFields()) {
                    if (f.getName().equals("connection")) {
                        f.setAccessible(true);
                        return f.get(mcPlayer);
                    }
                }
            } catch (Exception e2) {}
        }
        return null;
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return permissionManager.hasPermission(this, permission);
    }

    @Override
    public boolean hasPermission(String permission) {
        return permissionManager.hasPermission(this, permission);
    }

    /**
     * Returns the backing player model.
     */
    public InternalPlayer player() {
        return player;
    }
}


