package org.bukkit.craftbukkit.command;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;

public class CraftConsoleCommandSender extends ServerCommandSender implements ConsoleCommandSender {

    protected final MinecraftServer server;

    public CraftConsoleCommandSender(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
    }

    @Override
    public String getName() {
        return "CONSOLE";
    }

    @Override
    public net.kyori.adventure.text.Component name() {
        return net.kyori.adventure.text.Component.text("CONSOLE");
    }

    @Override
    public void sendMessage(String message) {
        this.server.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }

    @Override
    public void sendMessage(String... messages) {
        for (String msg : messages) {
            this.sendMessage(msg);
        }
    }

    @Override
    public void sendRawMessage(String message) {
        this.sendMessage(message);
    }

    @Override
    public void sendRawMessage(UUID sender, String message) {
        this.sendMessage(message);
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
}
