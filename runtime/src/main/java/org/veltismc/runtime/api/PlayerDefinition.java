package org.veltismc.runtime.api;

import java.net.InetAddress;
import java.util.UUID;

public interface PlayerDefinition {

    UUID uniqueId();

    String username();

    String displayName();

    InetAddress address();

    int ping();

    WorldDefinition world();

    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    GameMode gameMode();

    long firstJoined();

    long lastSeen();

    enum GameMode {
        SURVIVAL,
        CREATIVE,
        ADVENTURE,
        SPECTATOR
    }
}


