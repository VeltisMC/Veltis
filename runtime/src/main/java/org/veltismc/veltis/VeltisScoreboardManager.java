package org.veltismc.veltis;

import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.jetbrains.annotations.NotNull;

public class VeltisScoreboardManager implements ScoreboardManager {

    private final VeltisScoreboard mainScoreboard = new VeltisScoreboard();

    @Override
    public @NotNull Scoreboard getMainScoreboard() {
        return mainScoreboard;
    }

    @Override
    public @NotNull Scoreboard getNewScoreboard() {
        return new VeltisScoreboard();
    }
}
