package org.veltismc.runtime.internal;

import org.veltismc.runtime.api.PlayerDefinition;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.UUID;

public interface InternalPlayer {

    PlayerDefinition definition();

    UUID uniqueId();

    String username();

    String displayName();

    SocketAddress socketAddress();

    InetAddress inetAddress();

    int ping();

    InternalWorld world();

    Position position();

    void sendMessage(String message);

    void sendActionBar(String message);

    void kick(String reason);

    boolean online();

    boolean operator();

    void operator(boolean value);

    boolean hasPermission(String permission);

    long connectionTime();

    record Position(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {

        public Position withX(double x) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withY(double y) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withZ(double z) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withYaw(float yaw) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withPitch(float pitch) {
            return new Position(x, y, z, yaw, pitch);
        }

        public static Position at(double x, double y, double z) {
            return new Position(x, y, z, 0.0f, 0.0f);
        }

        public static Position facing(double x, double y, double z, float yaw, float pitch) {
            return new Position(x, y, z, yaw, pitch);
        }
    }
}


