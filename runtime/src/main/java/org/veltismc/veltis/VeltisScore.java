package org.veltismc.veltis;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VeltisScore implements Score {

    private final Objective objective;
    private final String entry;
    private int score;
    private boolean scoreSet;
    private boolean triggerable = true;
    private Component customName;
    private NumberFormat numberFormat;

    public VeltisScore(Objective objective, String entry) {
        this.objective = objective;
        this.entry = entry;
    }

    @Override public @Deprecated @NotNull OfflinePlayer getPlayer() { return null; }
    @Override public @NotNull String getEntry() { return entry; }
    @Override public @NotNull Objective getObjective() { return objective; }
    @Override public int getScore() { return score; }
    @Override public void setScore(int score) { this.score = score; this.scoreSet = true; }
    @Override public boolean isScoreSet() { return scoreSet; }
    @Override public @Nullable Scoreboard getScoreboard() { return null; }
    @Override public void resetScore() { score = 0; scoreSet = false; }
    @Override public boolean isTriggerable() { return triggerable; }
    @Override public void setTriggerable(boolean triggerable) { this.triggerable = triggerable; }
    @Override public @Nullable Component customName() { return customName; }
    @Override public void customName(@Nullable Component customName) { this.customName = customName; }
    @Override public @Nullable NumberFormat numberFormat() { return numberFormat; }
    @Override public void numberFormat(@Nullable NumberFormat format) { this.numberFormat = format; }
}
