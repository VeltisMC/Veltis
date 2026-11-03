package org.veltismc.api;

import java.util.UUID;

import org.veltismc.api.entity.Player;

public interface Server {
    String getVersion();
    void broadcast(String message);
    void broadcastJson(String json);
    Player getPlayer(String username);
    Player getPlayer(UUID uuid);
    int getTotalPlayers();
}