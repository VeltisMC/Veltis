package org.veltismc.veltis.server.model.player;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Mutable session metadata for a connected player.
 *
 * <p>Tracks connection-level state that changes during a play
 * session: connection time, address, ping, and game mode.
 *
 * <p>Thread-safe: all fields are volatile for visibility.
 * Snapshot via {@link #freeze()} for an immutable copy.
 */
public final class PlayerSession {

    private volatile long connectionTime;
    private volatile SocketAddress socketAddress;
    private volatile InetAddress inetAddress;
    private volatile int ping;
    private volatile String gameMode;
    private volatile String locale;
    private volatile int viewDistance;

    /**
     * Creates a new session with the given connection metadata.
     */
    public PlayerSession(
        long connectionTime,
        SocketAddress socketAddress,
        InetAddress inetAddress
    ) {
        this.connectionTime = connectionTime;
        this.socketAddress = Objects.requireNonNull(socketAddress, "socketAddress");
        this.inetAddress = inetAddress;
        this.ping = 0;
        this.gameMode = "survival";
        this.locale = "en_US";
        this.viewDistance = 10;
    }

    /**
     * Creates an empty offline session.
     */
    public static PlayerSession offline() {
        return new PlayerSession(0, new java.net.InetSocketAddress(0), null);
    }

    /**
     * Milliseconds since epoch when the player connected.
     */
    public long connectionTime() { return connectionTime; }

    /**
     * Sets the connection time.
     */
    public void connectionTime(long time) { this.connectionTime = time; }

    /**
     * The socket address of the player's connection.
     */
    public SocketAddress socketAddress() { return socketAddress; }

    /**
     * The resolved IP address.
     */
    public InetAddress inetAddress() { return inetAddress; }

    /**
     * Player latency in milliseconds.
     */
    public int ping() { return ping; }

    /**
     * Sets the player latency.
     */
    public void ping(int ping) { this.ping = ping; }

    /**
     * Current game mode (e.g. "survival", "creative").
     */
    public String gameMode() { return gameMode; }

    /**
     * Sets the game mode.
     */
    public void gameMode(String gameMode) { this.gameMode = Objects.requireNonNull(gameMode); }

    /**
     * Client locale string (e.g. "en_US").
     */
    public String locale() { return locale; }

    /**
     * Sets the client locale.
     */
    public void locale(String locale) { this.locale = Objects.requireNonNull(locale); }

    /**
     * Client view distance in chunks.
     */
    public int viewDistance() { return viewDistance; }

    /**
     * Sets the client view distance.
     */
    public void viewDistance(int distance) { this.viewDistance = distance; }

    /**
     * Returns an immutable snapshot of this session.
     */
    public Snapshot freeze() {
        return new Snapshot(
            connectionTime, socketAddress, inetAddress,
            ping, gameMode, locale, viewDistance
        );
    }

    /**
     * Immutable point-in-time snapshot of a session.
     */
    public record Snapshot(
        long connectionTime,
        SocketAddress socketAddress,
        InetAddress inetAddress,
        int ping,
        String gameMode,
        String locale,
        int viewDistance
    ) {}
}


