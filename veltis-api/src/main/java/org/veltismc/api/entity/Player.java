package org.veltismc.api.entity;

import java.net.InetSocketAddress;

import org.veltismc.api.game.Gamemode;
import org.veltismc.api.world.Location;

public interface Player {
    String getName();
    boolean isOnline();
    void sendMessage(String message);
    void sendJsonMessage(String json);
    void sendActionBar(String message);
    void kick(String reason);
    double getHealth();
    void setHealth(double health);
    double getFoodLevel();
    void setFoodLevel(double food);
    boolean isCrouched();
    void makeCrouched(boolean crouched);
    boolean isSprinting();
    Gamemode getGamemode();
    void setGamemode(Gamemode gamemode);
    Location getLocation();
    void teleport(Location location);
    InetSocketAddress getAddress();
}