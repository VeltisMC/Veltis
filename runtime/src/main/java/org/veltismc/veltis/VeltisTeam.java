package org.veltismc.veltis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class VeltisTeam implements Team {

    private final String name;
    private Component displayName;
    private Component prefix = Component.empty();
    private Component suffix = Component.empty();
    private NamedTextColor color;
    private boolean allowFriendlyFire;
    private boolean canSeeFriendlyInvisibles;
    private NameTagVisibility nameTagVisibility = NameTagVisibility.ALWAYS;
    private final Set<String> entries = new HashSet<>();

    public VeltisTeam(String name, Component displayName) {
        this.name = name;
        this.displayName = displayName;
    }

    @Override public @NotNull String getName() { return name; }
    @Override public @NotNull Component displayName() { return displayName; }
    @Override public void displayName(@Nullable Component displayName) { this.displayName = displayName != null ? displayName : Component.text(name); }
    @Override public @NotNull Component prefix() { return prefix; }
    @Override public void prefix(@Nullable Component prefix) { this.prefix = prefix != null ? prefix : Component.empty(); }
    @Override public @NotNull Component suffix() { return suffix; }
    @Override public void suffix(@Nullable Component suffix) { this.suffix = suffix != null ? suffix : Component.empty(); }
    @Override public boolean hasColor() { return color != null; }
    @Override public @NotNull TextColor color() { return color != null ? color : NamedTextColor.WHITE; }
    @Override public void color(@Nullable NamedTextColor color) { this.color = color; }

    @Override public @Deprecated @NotNull String getDisplayName() { return toLegacy(displayName); }
    @Override public @Deprecated void setDisplayName(@NotNull String displayName) { this.displayName = Component.text(displayName); }
    @Override public @Deprecated @NotNull String getPrefix() { return toLegacy(prefix); }
    @Override public @Deprecated void setPrefix(@NotNull String prefix) { this.prefix = Component.text(prefix); }
    @Override public @Deprecated @NotNull String getSuffix() { return toLegacy(suffix); }
    @Override public @Deprecated void setSuffix(@NotNull String suffix) { this.suffix = Component.text(suffix); }
    @Override public @Deprecated @NotNull ChatColor getColor() { return ChatColor.WHITE; }
    @Override public @Deprecated void setColor(@NotNull ChatColor color) {}

    @Override public boolean allowFriendlyFire() { return allowFriendlyFire; }
    @Override public void setAllowFriendlyFire(boolean enabled) { this.allowFriendlyFire = enabled; }
    @Override public boolean canSeeFriendlyInvisibles() { return canSeeFriendlyInvisibles; }
    @Override public void setCanSeeFriendlyInvisibles(boolean enabled) { this.canSeeFriendlyInvisibles = enabled; }
    @Override public @Deprecated @NotNull NameTagVisibility getNameTagVisibility() { return nameTagVisibility; }
    @Override public @Deprecated void setNameTagVisibility(@NotNull NameTagVisibility visibility) { this.nameTagVisibility = visibility; }
    @Override public @Deprecated @NotNull Set<OfflinePlayer> getPlayers() { return Set.of(); }
    @Override public @NotNull Set<String> getEntries() { return Collections.unmodifiableSet(entries); }
    @Override public int getSize() { return entries.size(); }
    @Override public @Nullable Scoreboard getScoreboard() { return null; }

    @Override public void addPlayer(@NotNull OfflinePlayer player) { if (player.getName() != null) entries.add(player.getName()); }
    @Override public void addEntry(@NotNull String entry) { entries.add(entry); }
    @Override public void addEntities(@NotNull Collection<Entity> entities) { for (var e : entities) entries.add(e.getUniqueId().toString()); }
    @Override public void addEntries(@NotNull Collection<String> entries) { this.entries.addAll(entries); }
    @Override public boolean removePlayer(@NotNull OfflinePlayer player) { return player.getName() != null && entries.remove(player.getName()); }
    @Override public boolean removeEntry(@NotNull String entry) { return entries.remove(entry); }
    @Override public boolean removeEntities(@NotNull Collection<Entity> entities) { boolean changed = false; for (var e : entities) changed |= entries.remove(e.getUniqueId().toString()); return changed; }
    @Override public boolean removeEntries(@NotNull Collection<String> entries) { return this.entries.removeAll(entries); }
    @Override public void unregister() {}
    @Override public boolean hasPlayer(@NotNull OfflinePlayer player) { return player.getName() != null && entries.contains(player.getName()); }
    @Override public boolean hasEntry(@NotNull String entry) { return entries.contains(entry); }
    @Override public void addEntity(@NotNull Entity entity) throws IllegalStateException, IllegalArgumentException { entries.add(entity.getUniqueId().toString()); }
    @Override public boolean removeEntity(@NotNull Entity entity) throws IllegalStateException, IllegalArgumentException { return entries.remove(entity.getUniqueId().toString()); }
    @Override public boolean hasEntity(@NotNull Entity entity) throws IllegalStateException, IllegalArgumentException { return entries.contains(entity.getUniqueId().toString()); }

    @Override public @NotNull OptionStatus getOption(@NotNull Option option) { return OptionStatus.ALWAYS; }
    @Override public void setOption(@NotNull Option option, @NotNull OptionStatus status) {}

    @Override public @NotNull Iterable<? extends net.kyori.adventure.audience.Audience> audiences() { return java.util.List.of(); }

    private static String toLegacy(Component c) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c);
    }
}
