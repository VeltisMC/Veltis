package org.veltismc.veltis;

import org.bukkit.BanEntry;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.Statistic;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.papermc.paper.persistence.PersistentDataContainerView;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VeltisOfflinePlayer implements OfflinePlayer {

    private final UUID uuid;
    private final String name;
    private final Server server;

    public VeltisOfflinePlayer(UUID uuid, String name, Server server) {
        this.uuid = uuid;
        this.name = name;
        this.server = server;
    }

    @Override public boolean isOnline() { return server.getPlayer(uuid) != null; }
    @Override public boolean isConnected() { return isOnline(); }
    @Override public @Nullable String getName() { return name; }
    @Override public @NotNull UUID getUniqueId() { return uuid; }
    @Override public @NotNull com.destroystokyo.paper.profile.PlayerProfile getPlayerProfile() {
        var online = server.getPlayer(uuid);
        if (online != null) return online.getPlayerProfile();
        return (com.destroystokyo.paper.profile.PlayerProfile) server.createPlayerProfile(uuid, name);
    }
    @Override public boolean isBanned() { return false; }
    @Override public boolean isWhitelisted() { return false; }
    @Override public void setWhitelisted(boolean value) {}
    @Override public @Nullable Player getPlayer() { return server.getPlayer(uuid); }
    @Override public long getFirstPlayed() { return 0; }
    @Override public @Deprecated long getLastPlayed() { return 0; }
    @Override public boolean hasPlayedBefore() { return true; }
    @Override public long getLastLogin() { return 0; }
    @Override public long getLastSeen() { return 0; }
    @Override public @Nullable Location getRespawnLocation(boolean loadLocationAndValidate) { return null; }
    @Override public @Nullable Location getLastDeathLocation() { return null; }
    @Override public @Nullable Location getLocation() { var p = getPlayer(); return p != null ? p.getLocation() : null; }

    @Override public void incrementStatistic(Statistic statistic) {}
    @Override public void decrementStatistic(Statistic statistic) {}
    @Override public void incrementStatistic(Statistic statistic, int amount) {}
    @Override public void decrementStatistic(Statistic statistic, int amount) {}
    @Override public void setStatistic(Statistic statistic, int newValue) {}
    @Override public int getStatistic(Statistic statistic) { return 0; }
    @Override public void incrementStatistic(Statistic statistic, Material material) {}
    @Override public void decrementStatistic(Statistic statistic, Material material) {}
    @Override public int getStatistic(Statistic statistic, Material material) { return 0; }
    @Override public void incrementStatistic(Statistic statistic, Material material, int amount) {}
    @Override public void decrementStatistic(Statistic statistic, Material material, int amount) {}
    @Override public void setStatistic(Statistic statistic, Material material, int newValue) {}
    @Override public void incrementStatistic(Statistic statistic, EntityType entityType) {}
    @Override public void decrementStatistic(Statistic statistic, EntityType entityType) {}
    @Override public int getStatistic(Statistic statistic, EntityType entityType) { return 0; }
    @Override public void incrementStatistic(Statistic statistic, EntityType entityType, int amount) {}
    @Override public void decrementStatistic(Statistic statistic, EntityType entityType, int amount) {}
    @Override public void setStatistic(Statistic statistic, EntityType entityType, int newValue) {}

    @Override public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> @Nullable E ban(@Nullable String reason, @Nullable Date expires, @Nullable String source) { return null; }
    @Override public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> @Nullable E ban(@Nullable String reason, @Nullable Instant expires, @Nullable String source) { return null; }
    @Override public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> @Nullable E ban(@Nullable String reason, @Nullable Duration duration, @Nullable String source) { return null; }

    @Override public boolean isOp() { return false; }
    @Override public void setOp(boolean value) {}
    private final PersistentDataContainerView emptyPDC = new PersistentDataContainerView() {
        @Override public <P, C> boolean has(org.bukkit.NamespacedKey key, org.bukkit.persistence.PersistentDataType<P, C> type) { return false; }
        @Override public boolean has(org.bukkit.NamespacedKey key) { return false; }
        @Override public <P, C> @Nullable C get(org.bukkit.NamespacedKey key, org.bukkit.persistence.PersistentDataType<P, C> type) { return null; }
        @Override public <P, C> C getOrDefault(org.bukkit.NamespacedKey key, org.bukkit.persistence.PersistentDataType<P, C> type, C defaultValue) { return defaultValue; }
        @Override public java.util.Set<org.bukkit.NamespacedKey> getKeys() { return java.util.Set.of(); }
        @Override public boolean isEmpty() { return true; }
        @Override public void copyTo(org.bukkit.persistence.PersistentDataContainer other, boolean replace) {}
        @Override public org.bukkit.persistence.PersistentDataAdapterContext getAdapterContext() { return null; }
        @Override public byte[] serializeToBytes() { return new byte[0]; }
        @Override public int getSize() { return 0; }
    };
    @Override public @NotNull PersistentDataContainerView getPersistentDataContainer() { return emptyPDC; }
    @Override public @NotNull Map<String, Object> serialize() { return Map.of("uuid", uuid.toString(), "name", name != null ? name : ""); }
}
