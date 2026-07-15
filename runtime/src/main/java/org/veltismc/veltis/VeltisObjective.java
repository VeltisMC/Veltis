package org.veltismc.veltis;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VeltisObjective implements Objective {

    private final String name;
    private final Criteria criteria;
    private Component displayName;
    private RenderType renderType;
    private DisplaySlot displaySlot;
    private boolean autoUpdateDisplay;
    private NumberFormat numberFormat;

    public VeltisObjective(String name, Criteria criteria, String displayName, RenderType renderType) {
        this.name = name;
        this.criteria = criteria;
        this.displayName = Component.text(displayName);
        this.renderType = renderType;
    }

    @Override public @NotNull String getName() { return name; }
    @Override public @NotNull Component displayName() { return displayName; }
    @Override public void displayName(@Nullable Component displayName) { this.displayName = displayName != null ? displayName : Component.text(name); }
    @Override public @Deprecated @NotNull String getDisplayName() { return toLegacy(displayName); }
    @Override public @Deprecated void setDisplayName(@NotNull String displayName) { this.displayName = Component.text(displayName); }
    @Override public @Deprecated @NotNull String getCriteria() { return criteria.getName(); }
    @Override public @NotNull Criteria getTrackedCriteria() { return criteria; }
    @Override public @NotNull RenderType getRenderType() { return renderType; }
    @Override public void setRenderType(@NotNull RenderType renderType) { this.renderType = renderType; }
    @Override public @Nullable DisplaySlot getDisplaySlot() { return displaySlot; }
    @Override public void setDisplaySlot(@Nullable DisplaySlot slot) { this.displaySlot = slot; }
    @Override public boolean isModifiable() { return true; }
    @Override public @Nullable Scoreboard getScoreboard() { return null; }
    @Override public void unregister() {}
    @Override public @NotNull Score getScore(@NotNull OfflinePlayer player) { return new VeltisScore(this, player.getName()); }
    @Override public @NotNull Score getScore(@NotNull String entry) { return new VeltisScore(this, entry); }
    @Override public @NotNull Score getScoreFor(@NotNull Entity entity) { return new VeltisScore(this, entity.getUniqueId().toString()); }
    @Override public boolean willAutoUpdateDisplay() { return autoUpdateDisplay; }
    @Override public void setAutoUpdateDisplay(boolean autoUpdateDisplay) { this.autoUpdateDisplay = autoUpdateDisplay; }
    @Override public @Nullable NumberFormat numberFormat() { return numberFormat; }
    @Override public void numberFormat(@Nullable NumberFormat format) { this.numberFormat = format; }

    private static String toLegacy(Component c) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c);
    }
}
