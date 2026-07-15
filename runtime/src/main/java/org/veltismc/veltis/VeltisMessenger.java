package org.veltismc.veltis;

import io.papermc.paper.connection.PlayerConnection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.messaging.PluginMessageListenerRegistration;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class VeltisMessenger implements Messenger {

    private static final Set<String> RESERVED = Set.of("minecraft:register", "minecraft:unregister", "BungeeCord");

    private final ConcurrentMap<String, Set<PluginMessageListenerRegistration>> incoming = new ConcurrentHashMap<>();
    private final ConcurrentMap<Plugin, Set<String>> outgoing = new ConcurrentHashMap<>();

    @Override
    public boolean isReservedChannel(@NotNull String channel) {
        return RESERVED.contains(channel);
    }

    @Override
    public void registerOutgoingPluginChannel(@NotNull Plugin plugin, @NotNull String channel) {
        if (isReservedChannel(channel)) throw new IllegalArgumentException("Reserved channel: " + channel);
        outgoing.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    @Override
    public void unregisterOutgoingPluginChannel(@NotNull Plugin plugin, @NotNull String channel) {
        var channels = outgoing.get(plugin);
        if (channels != null) channels.remove(channel);
    }

    @Override
    public void unregisterOutgoingPluginChannel(@NotNull Plugin plugin) {
        outgoing.remove(plugin);
    }

    @Override
    public @NotNull PluginMessageListenerRegistration registerIncomingPluginChannel(@NotNull Plugin plugin, @NotNull String channel, @NotNull PluginMessageListener listener) {
        if (isReservedChannel(channel)) throw new IllegalArgumentException("Reserved channel: " + channel);
        var registration = new PluginMessageListenerRegistration(this, plugin, channel, listener);
        incoming.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(registration);
        return registration;
    }

    @Override
    public void unregisterIncomingPluginChannel(@NotNull Plugin plugin, @NotNull String channel, @NotNull PluginMessageListener listener) {
        var regs = incoming.get(channel);
        if (regs != null) regs.removeIf(r -> r.getPlugin().equals(plugin) && r.getListener().equals(listener));
    }

    @Override
    public void unregisterIncomingPluginChannel(@NotNull Plugin plugin, @NotNull String channel) {
        var regs = incoming.get(channel);
        if (regs != null) regs.removeIf(r -> r.getPlugin().equals(plugin));
    }

    @Override
    public void unregisterIncomingPluginChannel(@NotNull Plugin plugin) {
        incoming.values().forEach(regs -> regs.removeIf(r -> r.getPlugin().equals(plugin)));
    }

    @Override
    public @NotNull Set<String> getOutgoingChannels() {
        var all = new HashSet<String>();
        outgoing.values().forEach(all::addAll);
        return Collections.unmodifiableSet(all);
    }

    @Override
    public @NotNull Set<String> getOutgoingChannels(@NotNull Plugin plugin) {
        var channels = outgoing.get(plugin);
        return channels != null ? Collections.unmodifiableSet(channels) : Set.of();
    }

    @Override
    public @NotNull Set<String> getIncomingChannels() {
        return Collections.unmodifiableSet(incoming.keySet());
    }

    @Override
    public @NotNull Set<String> getIncomingChannels(@NotNull Plugin plugin) {
        var result = new HashSet<String>();
        incoming.forEach((channel, regs) -> {
            for (var r : regs) {
                if (r.getPlugin().equals(plugin)) result.add(channel);
            }
        });
        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NotNull Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(@NotNull Plugin plugin) {
        var result = new HashSet<PluginMessageListenerRegistration>();
        incoming.values().forEach(regs -> {
            for (var r : regs) {
                if (r.getPlugin().equals(plugin)) result.add(r);
            }
        });
        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NotNull Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(@NotNull String channel) {
        var regs = incoming.get(channel);
        return regs != null ? Collections.unmodifiableSet(regs) : Set.of();
    }

    @Override
    public @NotNull Set<PluginMessageListenerRegistration> getIncomingChannelRegistrations(@NotNull Plugin plugin, @NotNull String channel) {
        var result = new HashSet<PluginMessageListenerRegistration>();
        var regs = incoming.get(channel);
        if (regs != null) {
            for (var r : regs) {
                if (r.getPlugin().equals(plugin)) result.add(r);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public boolean isRegistrationValid(@NotNull PluginMessageListenerRegistration registration) {
        var regs = incoming.get(registration.getChannel());
        return regs != null && regs.contains(registration);
    }

    @Override
    public boolean isIncomingChannelRegistered(@NotNull Plugin plugin, @NotNull String channel) {
        var regs = incoming.get(channel);
        if (regs == null) return false;
        return regs.stream().anyMatch(r -> r.getPlugin().equals(plugin));
    }

    @Override
    public boolean isOutgoingChannelRegistered(@NotNull Plugin plugin, @NotNull String channel) {
        var channels = outgoing.get(plugin);
        return channels != null && channels.contains(channel);
    }

    @Override
    public @Deprecated void dispatchIncomingMessage(@NotNull Player source, @NotNull String channel, byte @NotNull [] message) {
        var regs = incoming.get(channel);
        if (regs == null) return;
        for (var r : regs) {
            try { r.getListener().onPluginMessageReceived(channel, source, message); } catch (Exception ignored) {}
        }
    }

    @Override
    public void dispatchIncomingMessage(@NotNull PlayerConnection source, @NotNull String channel, byte @NotNull [] message) {
        var regs = incoming.get(channel);
        if (regs == null) return;
        for (var r : regs) {
            try { r.getListener().onPluginMessageReceived(channel, source, message); } catch (Exception ignored) {}
        }
    }
}
