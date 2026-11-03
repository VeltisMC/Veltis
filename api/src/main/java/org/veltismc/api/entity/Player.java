package org.veltismc.api.entity;

import java.util.UUID;

import org.veltismc.api.command.CommandSender;

public interface Player extends CommandSender {
    String getUsername();
    UUID getUUID();
    boolean isOnline();
    void kick();
    void kick(String reason);
    void kickJson(String json);
    void sendMessage(String message);
    void sendMessageJson(String json);

    /**
     * @return 0 = not operator, 1-4 op level
     */
    int getOperatorLevel();

    /**
     * 0 removes operator status, 1-4 sets the op level
     */
    void setOperatorLevel(int level);
}