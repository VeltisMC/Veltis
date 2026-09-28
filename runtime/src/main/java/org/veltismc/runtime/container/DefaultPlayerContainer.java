package org.veltismc.runtime.container;

import org.veltismc.runtime.api.PlayerDefinition;
import org.veltismc.runtime.api.WorldDefinition;
import org.veltismc.runtime.model.player.InternalPlayer;
import org.veltismc.runtime.internal.InternalWorld;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

public final class DefaultPlayerContainer implements PlayerContainer {

    private final ConcurrentMap<UUID, org.veltismc.runtime.internal.InternalPlayer> byUuid;
    private final ConcurrentMap<String, org.veltismc.runtime.internal.InternalPlayer> byName;

    public DefaultPlayerContainer() {
        this.byUuid = new ConcurrentHashMap<>();
        this.byName = new ConcurrentHashMap<>();
    }

    @Override
    public org.veltismc.runtime.internal.InternalPlayer add(org.veltismc.runtime.internal.InternalPlayer player) {
        byUuid.put(player.uniqueId(), player);
        byName.put(player.username().toLowerCase(), player);
        return player;
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalPlayer> remove(UUID uuid) {
        return Optional.ofNullable(byUuid.remove(uuid))
            .map(player -> {
                byName.remove(player.username().toLowerCase());
                return player;
            });
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalPlayer> remove(String username) {
        return Optional.ofNullable(byName.remove(username.toLowerCase()))
            .map(player -> {
                byUuid.remove(player.uniqueId());
                return player;
            });
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalPlayer> byUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    @Override
    public Optional<org.veltismc.runtime.internal.InternalPlayer> byName(String username) {
        return Optional.ofNullable(byName.get(username.toLowerCase()));
    }

    @Override
    public Collection<org.veltismc.runtime.internal.InternalPlayer> all() {
        return Collections.unmodifiableCollection(byUuid.values());
    }

    @Override
    public Stream<org.veltismc.runtime.internal.InternalPlayer> stream() {
        return byUuid.values().stream();
    }

    @Override
    public int count() {
        return byUuid.size();
    }

    @Override
    public boolean isEmpty() {
        return byUuid.isEmpty();
    }

    @Override
    public boolean contains(UUID uuid) {
        return byUuid.containsKey(uuid);
    }

    @Override
    public boolean contains(String username) {
        return byName.containsKey(username.toLowerCase());
    }

    public static final class AdaptedInternal implements org.veltismc.runtime.internal.InternalPlayer {

        private final InternalPlayer delegate;
        private final InternalWorld world;

        public AdaptedInternal(InternalPlayer delegate) {
            this(delegate, null);
        }

        public AdaptedInternal(InternalPlayer delegate, InternalWorld world) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.world = world;
        }

        public InternalPlayer delegate() { return delegate; }

        @Override
        public PlayerDefinition definition() {
            var profile = delegate.profile();
            var session = delegate.session();
            var pos = delegate.position();
            return new PlayerDefinition() {
                @Override public UUID uniqueId() { return profile.uniqueId(); }
                @Override public String username() { return profile.name(); }
                @Override public String displayName() { return profile.displayName(); }
                @Override public InetAddress address() { return session.inetAddress(); }
                @Override public int ping() { return session.ping(); }
                @Override public WorldDefinition world() { return null; }
                @Override public double x() { return pos != null ? pos.x() : 0; }
                @Override public double y() { return pos != null ? pos.y() : 0; }
                @Override public double z() { return pos != null ? pos.z() : 0; }
                @Override public float yaw() { return pos != null ? pos.yaw() : 0; }
                @Override public float pitch() { return pos != null ? pos.pitch() : 0; }
                @Override public GameMode gameMode() { return GameMode.SURVIVAL; }
                @Override public long firstJoined() { return session.connectionTime(); }
                @Override public long lastSeen() { return session.connectionTime(); }
            };
        }

        @Override
        public UUID uniqueId() { return delegate.uniqueId(); }

        @Override
        public String username() { return delegate.profile().name(); }

        @Override
        public String displayName() { return delegate.profile().displayName(); }

        @Override
        public SocketAddress socketAddress() { return delegate.session().socketAddress(); }

        @Override
        public InetAddress inetAddress() { return delegate.session().inetAddress(); }

        @Override
        public int ping() { return delegate.session().ping(); }

        @Override
        public InternalWorld world() { return world; }

        @Override
        public org.veltismc.runtime.internal.InternalPlayer.Position position() {
            var pos = delegate.position();
            return new org.veltismc.runtime.internal.InternalPlayer.Position(pos.x(), pos.y(), pos.z(), pos.yaw(), pos.pitch());
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void sendActionBar(String message) {
        }

        @Override
        public void kick(String reason) {
        }

        @Override
        public boolean online() { return delegate.online(); }

        @Override
        public boolean operator() { return false; }

        @Override
        public void operator(boolean value) {
        }

        @Override
        public boolean hasPermission(String permission) { return false; }

        @Override
        public long connectionTime() { return delegate.session().connectionTime(); }
    }
}


