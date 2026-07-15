package org.bukkit.craftbukkit.command;

import net.minecraft.network.chat.Component;
import net.minecraft.server.rcon.RconConsoleSource;
import org.bukkit.command.RemoteConsoleCommandSender;

public class CraftRemoteConsoleCommandSender extends ServerCommandSender implements RemoteConsoleCommandSender {

    private final RconConsoleSource listener;

    public CraftRemoteConsoleCommandSender(RconConsoleSource listener) {
        this.listener = listener;
    }

    public RconConsoleSource getListener() {
        return this.listener;
    }

    @Override
    public java.net.SocketAddress getAddress() {
        return null;
    }

    @Override
    public void sendMessage(String message) {
        this.listener.sendSystemMessage(Component.literal(message + "\n"));
    }

    @Override
    public void sendMessage(String... messages) {
        for (String msg : messages) {
            this.sendMessage(msg);
        }
    }

    @Override
    public String getName() {
        return "Rcon";
    }

    @Override
    public net.kyori.adventure.text.Component name() {
        return net.kyori.adventure.text.Component.text(this.getName());
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
    }
}
