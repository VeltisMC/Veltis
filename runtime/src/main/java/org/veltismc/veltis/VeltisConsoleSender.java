package org.veltismc.veltis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

public class VeltisConsoleSender implements ConsoleCommandSender {

    private static final Logger LOGGER = LogManager.getLogger("VeltisMC.Console");

    private final Server server;
    private PermissibleBase permBase;

    public VeltisConsoleSender(Server server) {
        this.server = server;
    }

    private PermissibleBase permBase() {
        if (permBase == null) permBase = new PermissibleBase(this);
        return permBase;
    }

    @Override
    public void sendMessage(String message) {
        LOGGER.info(message);
    }

    @Override
    public void sendMessage(String... messages) {
        for (var msg : messages) sendMessage(msg);
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        sendMessage(messages);
    }

    @Override
    public void sendMessage(Component message) {
        sendMessage(LegacyComponentSerializer.legacySection().serialize(message));
    }

    @Override
    public void sendMessage(net.kyori.adventure.identity.Identity identity, Component message, net.kyori.adventure.audience.MessageType type) {
        sendMessage(message);
    }

    @Override
    public void sendRawMessage(String message) {
        sendMessage(message);
    }

    @Override
    public void sendRawMessage(UUID sender, String message) {
        sendMessage(message);
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public String getName() {
        return "CONSOLE";
    }

    @Override
    public Component name() {
        return Component.text("CONSOLE");
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
    }

    @Override
    public boolean isPermissionSet(String name) {
        return permBase().isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return permBase().isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return true; // Console has all permissions
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return true; // Console has all permissions
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return permBase().addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return permBase().addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return permBase().addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return permBase().addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        permBase().removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        permBase().recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return permBase().getEffectivePermissions();
    }

    @Override
    public boolean isConversing() {
        return false;
    }

    @Override
    public void acceptConversationInput(String input) {
    }

    @Override
    public boolean beginConversation(Conversation conversation) {
        return false;
    }

    @Override
    public void abandonConversation(Conversation conversation) {
    }

    @Override
    public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
    }

    @Override
    public Spigot spigot() {
        return new Spigot();
    }

    @Override
    public String toString() {
        return "VeltisConsole";
    }
}
