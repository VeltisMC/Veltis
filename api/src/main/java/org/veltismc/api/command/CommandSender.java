package org.veltismc.api.command;

public interface CommandSender {
    String getUsername();
    void sendMessage(String message);
}