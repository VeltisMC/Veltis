package org.veltismc.veltis.command.brigadier;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VeltisCommandSourceStack implements CommandSourceStack {

    private final net.minecraft.commands.CommandSourceStack nms;
    @Nullable
    private final CommandSender sender;
    @Nullable
    private Location location;
    @Nullable
    private Entity executor;

    public VeltisCommandSourceStack(final net.minecraft.commands.CommandSourceStack nms) {
        this.nms = nms;
        this.sender = resolveSender(nms);
        this.executor = resolveExecutor(nms);
    }

    private VeltisCommandSourceStack(
        final net.minecraft.commands.CommandSourceStack nms,
        @Nullable final CommandSender sender,
        @Nullable final Location location,
        @Nullable final Entity executor
    ) {
        this.nms = nms;
        this.sender = sender;
        this.location = location;
        this.executor = executor;
    }

    @Override
    public @NotNull Location getLocation() {
        if (this.location == null) {
            try {
                final var vec3 = this.nms.getPosition();
                final var level = this.nms.getLevel();
                if (level != null) {
                    final var worldMethod = level.getClass().getMethod("getWorld");
                    final var world = (World) worldMethod.invoke(level);
                    this.location = new Location(world, vec3.x, vec3.y, vec3.z);
                }
            } catch (final Exception ignored) {}
            if (this.location == null) {
                this.location = new Location(null, 0, 0, 0);
            }
        }
        return this.location.clone();
    }

    @Override
    public @NotNull CommandSender getSender() {
        return this.sender != null ? this.sender : Bukkit.getConsoleSender();
    }

    @Override
    public @Nullable Entity getExecutor() {
        return this.executor;
    }

    @Override
    public @NotNull CommandSourceStack withLocation(final Location location) {
        return new VeltisCommandSourceStack(this.nms, this.sender, location, this.executor);
    }

    @Override
    public @NotNull CommandSourceStack withExecutor(final Entity executor) {
        return new VeltisCommandSourceStack(this.nms, this.sender, this.location, executor);
    }

    @Nullable
    private static CommandSender resolveSender(final net.minecraft.commands.CommandSourceStack nms) {
        try {
            final var method = nms.getClass().getMethod("getBukkitSender");
            return (CommandSender) method.invoke(nms);
        } catch (final Exception ignored) {}
        try {
            final var entity = nms.getEntity();
            if (entity != null) {
                final var getBukkitEntity = entity.getClass().getMethod("getBukkitEntity");
                final var bukkitEntity = getBukkitEntity.invoke(entity);
                if (bukkitEntity instanceof CommandSender cs) return cs;
            }
        } catch (final Exception ignored) {}
        return Bukkit.getConsoleSender();
    }

    @Nullable
    private static Entity resolveExecutor(final net.minecraft.commands.CommandSourceStack nms) {
        try {
            final var entity = nms.getEntity();
            if (entity != null) {
                final var getBukkitEntity = entity.getClass().getMethod("getBukkitEntity");
                final var bukkitEntity = getBukkitEntity.invoke(entity);
                if (bukkitEntity instanceof Entity e) return e;
            }
        } catch (final Exception ignored) {}
        return null;
    }
}
