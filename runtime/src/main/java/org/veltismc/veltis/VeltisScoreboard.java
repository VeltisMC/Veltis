package org.veltismc.veltis;

import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class VeltisScoreboard implements Scoreboard {

    private final Map<String, Objective> objectives = new HashMap<>();
    private final Map<String, Team> teams = new HashMap<>();

    @Override
    public @Deprecated @NotNull Objective registerNewObjective(@NotNull String name, @NotNull String criteria, @Nullable Component displayName, @NotNull RenderType renderType) {
        var obj = new VeltisObjective(name, Criteria.create(criteria), displayName != null ? toLegacy(displayName) : name, renderType);
        objectives.put(name, obj);
        return obj;
    }

    @Override
    public @Deprecated @NotNull Objective registerNewObjective(@NotNull String name, @NotNull String criteria, @NotNull String displayName, @NotNull RenderType renderType) {
        var obj = new VeltisObjective(name, Criteria.create(criteria), displayName, renderType);
        objectives.put(name, obj);
        return obj;
    }

    @Override
    public @NotNull Objective registerNewObjective(@NotNull String name, @NotNull Criteria criteria, @Nullable Component displayName, @NotNull RenderType renderType) {
        var obj = new VeltisObjective(name, criteria, displayName != null ? toLegacy(displayName) : name, renderType);
        objectives.put(name, obj);
        return obj;
    }

    @Override
    public @Nullable Objective getObjective(@NotNull String name) {
        return objectives.get(name);
    }

    @Override
    public @Deprecated @NotNull Set<Objective> getObjectivesByCriteria(@NotNull String criteria) {
        var result = new HashSet<Objective>();
        for (var obj : objectives.values()) {
            if (obj.getCriteria().equals(criteria)) result.add(obj);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NotNull Set<Objective> getObjectivesByCriteria(@NotNull Criteria criteria) {
        var result = new HashSet<Objective>();
        for (var obj : objectives.values()) {
            if (obj.getTrackedCriteria().equals(criteria)) result.add(obj);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NotNull Set<Objective> getObjectives() {
        return Set.copyOf(objectives.values());
    }

    @Override
    public @Nullable Objective getObjective(@NotNull DisplaySlot slot) {
        for (var obj : objectives.values()) {
            if (obj.getDisplaySlot() == slot) return obj;
        }
        return null;
    }

    @Override
    public @NotNull Set<Score> getScores(@NotNull OfflinePlayer player) {
        return Set.of();
    }

    @Override
    public @NotNull Set<Score> getScores(@NotNull String entry) {
        return Set.of();
    }

    @Override
    public @NotNull Set<Score> getScoresFor(@NotNull Entity entity) {
        return Set.of();
    }

    @Override
    public void resetScores(@NotNull OfflinePlayer player) {}
    @Override
    public void resetScores(@NotNull String entry) {}
    @Override
    public void resetScoresFor(@NotNull Entity entity) {}

    @Override
    public @Nullable Team getPlayerTeam(@NotNull OfflinePlayer player) {
        for (var team : teams.values()) {
            if (team.hasPlayer(player)) return team;
        }
        return null;
    }

    @Override
    public @Nullable Team getEntryTeam(@NotNull String entry) {
        for (var team : teams.values()) {
            if (team.hasEntry(entry)) return team;
        }
        return null;
    }

    @Override
    public @Nullable Team getEntityTeam(@NotNull Entity entity) {
        for (var team : teams.values()) {
            if (team.hasEntity(entity)) return team;
        }
        return null;
    }

    @Override
    public @NotNull Team registerNewTeam(@NotNull String name) {
        var team = new VeltisTeam(name, Component.text(name));
        teams.put(name, team);
        return team;
    }

    @Override
    public @Nullable Team getTeam(@NotNull String name) {
        return teams.get(name);
    }

    @Override
    public @NotNull Set<Team> getTeams() {
        return Set.copyOf(teams.values());
    }

    @Override
    public @Deprecated @NotNull Set<OfflinePlayer> getPlayers() { return Set.of(); }

    @Override
    public @NotNull Set<String> getEntries() { return Set.of(); }

    @Override
    public void clearSlot(@NotNull DisplaySlot slot) {}

    private static String toLegacy(Component c) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c);
    }
}
