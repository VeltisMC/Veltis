package org.veltismc.runtime.network;

import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization; // Modern 1.26+ text pipeline
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import org.veltismc.api.entity.Player;
import org.veltismc.api.game.Gamemode;
import org.veltismc.api.world.Location;

import com.google.gson.JsonParser;

public final class VeltisPlayer implements Player {
    private final ServerPlayer mcPlayer;
    private final Channel socket;

    public VeltisPlayer(ServerPlayer mcPlayer, Channel socket) {
        this.mcPlayer = mcPlayer;
        this.socket = socket;
    }

    @Override
    public String getName() {
        return mcPlayer.getGameProfile().name();
    }

    @Override
    public boolean isOnline() {
        return (mcPlayer.connection != null) && (socket != null) && socket.isActive();
    }

    @Override
    public void sendMessage(String message) {
        if (!isOnline())
            return;
        if (message == null)
            return;
        mcPlayer.connection.send(new ClientboundSystemChatPacket(Component.literal(message), false));
    }

    @Override
    public void sendJsonMessage(String jsonMsg) {
        if (!isOnline())
            return;
        if (jsonMsg == null)
            return;

        try {
            var jsonElement = JsonParser.parseString(jsonMsg);
            Component jsonComponent = ComponentSerialization.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, jsonElement)
                    .getOrThrow(msg -> new IllegalArgumentException("Invalid component JSON: " + msg));

            if (jsonComponent == null)
                return;
            mcPlayer.connection.send(new ClientboundSystemChatPacket(jsonComponent, false));
        } catch (Exception _) {
        }
    }

    @Override
    public void kick(String reason) {
        if (!isOnline())
            return;
        if (reason == null)
            reason = "";
        mcPlayer.connection.disconnect(Component.literal(reason));
    }

    @Override
    public double getHealth() {
        return mcPlayer.getHealth();
    }

    @Override
    public void setHealth(double health) {
        float clampedHealth = (float) Math.max(0.0, Math.min(health, mcPlayer.getMaxHealth()));
        mcPlayer.setHealth(clampedHealth);
    }

    @Override
    public double getFoodLevel() {
        return mcPlayer.getFoodData().getFoodLevel();
    }

    @Override
    public void setFoodLevel(double food) {
        int clampedFood = (int) Math.max(0, Math.min(food, 20));
        mcPlayer.getFoodData().setFoodLevel(clampedFood);
    }

    @Override
    public boolean isCrouched() {
        return mcPlayer.isCrouching();
    }

    @Override
    public void makeCrouched(boolean crouched) {
        mcPlayer.setShiftKeyDown(crouched);
    }

    @Override
    public boolean isSprinting() {
        return mcPlayer.isSprinting();
    }

    @Override
    public Gamemode getGamemode() {
        GameType type = mcPlayer.gameMode.getGameModeForPlayer();
        return switch (type) {
            case SURVIVAL -> Gamemode.SURVIVAL;
            case CREATIVE -> Gamemode.CREATIVE;
            case ADVENTURE -> Gamemode.ADVENTURE;
            case SPECTATOR -> Gamemode.SPECTATOR;
        };
    }

    @Override
    public void setGamemode(Gamemode gamemode) {
        GameType type = switch (gamemode) {
            case SURVIVAL -> GameType.SURVIVAL;
            case CREATIVE -> GameType.CREATIVE;
            case ADVENTURE -> GameType.ADVENTURE;
            case SPECTATOR -> GameType.SPECTATOR;
        };
        mcPlayer.setGameMode(type);
    }

    @Override
    public Location getLocation() {
        String rawKeyStr = mcPlayer.level().dimension().toString();
        String worldId = "minecraft:overworld"; // Safe default
        if (rawKeyStr.contains("/")) {
            worldId = rawKeyStr.substring(rawKeyStr.lastIndexOf("/") + 1, rawKeyStr.length() - 1).trim();
        }

        return new Location(
                worldId,
                mcPlayer.getX(),
                mcPlayer.getY(),
                mcPlayer.getZ(),
                mcPlayer.getYRot(),
                mcPlayer.getXRot());
    }

    @Override
    public void teleport(Location location) {
        if (!(mcPlayer.level() instanceof ServerLevel currentLevel))
            return;

        MinecraftServer serverInstance = currentLevel.getServer();

        ServerLevel targetLevel = null;
        for (ServerLevel level : serverInstance.getAllLevels()) {
            String rawLevelKey = level.dimension().toString();
            String activeWorldId = "minecraft:overworld";
            if (rawLevelKey.contains("/")) {
                activeWorldId = rawLevelKey.substring(rawLevelKey.lastIndexOf("/") + 1, rawLevelKey.length() - 1)
                        .trim();
            }

            if (activeWorldId.equals(location.worldName())) {
                targetLevel = level;
                break;
            }
        }

        if (targetLevel == null)
            targetLevel = currentLevel;

        mcPlayer.teleportTo(
                targetLevel,
                location.x(),
                location.y(),
                location.z(),
                Set.of(),
                location.yaw(),
                location.pitch(),
                true);
    }

    @Override
    public InetSocketAddress getAddress() {
        if (!isOnline() || socket == null) return null;
        SocketAddress remoteAddress = socket.remoteAddress();

        if (remoteAddress instanceof InetSocketAddress inetAddress)
            return inetAddress;

        return null;
    }

    public Channel getSocket() {
        return this.socket;
    }

    public ServerPlayer getHandle() {
        return this.mcPlayer;
    }
}