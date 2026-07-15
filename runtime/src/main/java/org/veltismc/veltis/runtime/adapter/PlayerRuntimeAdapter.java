package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.server.model.player.InternalPlayer;
import org.veltismc.veltis.server.model.player.PlayerProfile;
import org.veltismc.veltis.server.model.player.PlayerSession;
import org.veltismc.veltis.server.model.player.PlayerState;
import org.veltismc.veltis.server.model.world.InternalWorld;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerRuntimeAdapter {

    private static final Logger LOG = System.getLogger(PlayerRuntimeAdapter.class.getName());

    private static final String SERVER_PLAYER_CLASS = "net.minecraft.server.level.ServerPlayer";

    private final ClassLoader classLoader;
    private final Object minecraftServer;

    private Method getPlayerListMethod;
    private Method getPlayersMethod;
    private Boolean methodSearchDone;

    public PlayerRuntimeAdapter(Object minecraftServer) {
        this.minecraftServer = Objects.requireNonNull(minecraftServer, "minecraftServer");
        this.classLoader = getClass().getClassLoader();
        this.methodSearchDone = false;
    }

    public InternalPlayer adapt(
        Object mojangPlayer,
        WorldRuntimeAdapter worldAdapter
    ) {
        Objects.requireNonNull(mojangPlayer, "mojangPlayer");
        Objects.requireNonNull(worldAdapter, "worldAdapter");

        var profile = extractProfile(mojangPlayer);
        var session = extractSession(mojangPlayer);
        var world = extractWorld(mojangPlayer, worldAdapter);
        var position = extractPosition(mojangPlayer);

        var adapted = new AdaptedPlayer(profile, session, world, position, mojangPlayer);
        adapted.state(PlayerState.ONLINE);
        return adapted;
    }

    public InternalPlayer createOffline(
        PlayerProfile profile,
        InternalWorld world
    ) {
        return new AdaptedPlayer(
            profile,
            PlayerSession.offline(),
            world,
            InternalPlayer.Position.ZERO,
            null
        );
    }

    public List<Object> getAllPlayers() {
        var result = new ArrayList<Object>();
        try {
            var playerList = resolvePlayerList();
            if (playerList == null) return result;

            var players = invokeGetPlayers(playerList);
            if (players instanceof List<?> list) {
                for (var p : list) {
                    if (p != null) result.add(p);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Failed to retrieve player list: {0}", e.getMessage());
        }
        return result;
    }

    private Object resolvePlayerList() {
        try {
            if (getPlayerListMethod == null && !methodSearchDone) {
                getPlayerListMethod = findAccessibleMethod(minecraftServer.getClass(), "getPlayerList");
                methodSearchDone = true;
            }
            if (getPlayerListMethod != null) {
                return getPlayerListMethod.invoke(minecraftServer);
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not resolve player list: {0}", e.getMessage());
        }
        return null;
    }

    private Object invokeGetPlayers(Object playerList) {
        try {
            if (getPlayersMethod == null) {
                getPlayersMethod = findAccessibleMethod(playerList.getClass(), "getPlayers");
            }
            if (getPlayersMethod != null) {
                return getPlayersMethod.invoke(playerList);
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not invoke getPlayers: {0}", e.getMessage());
        }
        return List.of();
    }

    private PlayerProfile extractProfile(Object mojangPlayer) {
        try {
            var getGameProfile = mojangPlayer.getClass().getMethod("getGameProfile");
            var gameProfile = getGameProfile.invoke(mojangPlayer);

            var getId = gameProfile.getClass().getMethod("getId");
            var uuid = (UUID) getId.invoke(gameProfile);

            var getName = gameProfile.getClass().getMethod("getName");
            var name = (String) getName.invoke(gameProfile);

            return PlayerProfile.minimal(uuid, name)
                .withDisplayName(name);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract player profile reflectively", e);
            return PlayerProfile.minimal(UUID.randomUUID(), "unknown");
        }
    }

    private PlayerSession extractSession(Object mojangPlayer) {
        try {
            var connectionMethod = findAccessibleMethod(mojangPlayer.getClass(), "connection");
            var connection = connectionMethod.invoke(mojangPlayer);

            var address = extractAddress(connection);
            var connectionTime = System.currentTimeMillis();

            var session = new PlayerSession(connectionTime, address, address.getAddress());

            try {
                var latencyMethod = mojangPlayer.getClass().getMethod("latency");
                session.ping((int) latencyMethod.invoke(mojangPlayer));
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Could not extract latency");
            }

            return session;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract player session, using offline", e);
            return PlayerSession.offline();
        }
    }

    private InetSocketAddress extractAddress(Object connection) {
        try {
            var addressMethod = connection.getClass().getMethod("getRemoteAddress");
            var addr = addressMethod.invoke(connection);
            if (addr instanceof InetSocketAddress isa) {
                return isa;
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract remote address");
        }
        return new InetSocketAddress(0);
    }

    private InternalWorld extractWorld(
        Object mojangPlayer,
        WorldRuntimeAdapter worldAdapter
    ) {
        try {
            var levelMethod = mojangPlayer.getClass().getMethod("level");
            var level = levelMethod.invoke(mojangPlayer);
            if (level != null) {
                return worldAdapter.adapt(level);
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract player world");
        }
        return null;
    }

    private InternalPlayer.Position extractPosition(Object mojangPlayer) {
        try {
            var getX = mojangPlayer.getClass().getMethod("getX");
            var getY = mojangPlayer.getClass().getMethod("getY");
            var getZ = mojangPlayer.getClass().getMethod("getZ");
            var getYaw = mojangPlayer.getClass().getMethod("getYaw");
            var getPitch = mojangPlayer.getClass().getMethod("getPitch");

            var x = (double) getX.invoke(mojangPlayer);
            var y = (double) getY.invoke(mojangPlayer);
            var z = (double) getZ.invoke(mojangPlayer);
            var yaw = ((Number) getYaw.invoke(mojangPlayer)).floatValue();
            var pitch = ((Number) getPitch.invoke(mojangPlayer)).floatValue();

            return new InternalPlayer.Position(x, y, z, yaw, pitch);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract player position");
            return InternalPlayer.Position.ZERO;
        }
    }

    private static Method findAccessibleMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            var method = clazz.getMethod(name, paramTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            for (var iface : clazz.getInterfaces()) {
                try {
                    var method = iface.getMethod(name, paramTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            return null;
        }
    }

    private static final class AdaptedPlayer implements InternalPlayer {

        private final PlayerProfile profile;
        private final PlayerSession session;
        private volatile PlayerState state;
        private volatile InternalWorld world;
        private volatile Position position;
        private final Object mojangPlayer;

        AdaptedPlayer(
            PlayerProfile profile,
            PlayerSession session,
            InternalWorld world,
            Position position,
            Object mojangPlayer
        ) {
            this.profile = profile;
            this.session = session;
            this.world = world;
            this.position = position;
            this.mojangPlayer = mojangPlayer;
            this.state = PlayerState.OFFLINE;
        }

        @Override
        public UUID uniqueId() { return profile.uniqueId(); }

        @Override
        public PlayerProfile profile() { return profile; }

        @Override
        public PlayerSession session() { return session; }

        @Override
        public PlayerState state() { return state; }

        @Override
        public void state(PlayerState newState) {
            if (state.allowsTransitionTo(newState)) {
                this.state = newState;
            }
        }

        @Override
        public InternalWorld world() { return world; }

        @Override
        public Position position() { return position; }

        @Override
        public void position(Position position) { this.position = position; }

        @Override
        public void teleport(InternalWorld world, Position position) {
            this.world = world;
            this.position = position;
        }

        @Override
        public float health() { return 20.0f; }

        @Override
        public float maxHealth() { return 20.0f; }

        @Override
        public int foodLevel() { return 20; }

        @Override
        public int experienceLevel() { return 0; }

        @Override
        public float experienceProgress() { return 0.0f; }

        @Override
        public boolean sneaking() { return false; }

        @Override
        public boolean sprinting() { return false; }

        @Override
        public boolean flying() { return false; }

        @Override
        public boolean online() { return state == PlayerState.ONLINE; }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<org.veltismc.veltis.server.internal.InternalPlayer> serverPlayer() {
            if (mojangPlayer != null) {
                var raw = Optional.of(mojangPlayer);
                return (Optional<org.veltismc.veltis.server.internal.InternalPlayer>) (Optional<?>) raw;
            }
            return Optional.empty();
        }
    }
}


