package org.veltismc.veltis.api.definition;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ServerDefinition {

    enum ServerState {
        INITIALIZING,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        CRASHED
    }

    ServerState currentState();

    UUID identifier();

    String name();

    int protocolVersion();

    String minecraftVersion();

    long startedAt();

    Collection<? extends WorldDefinition> loadedWorlds();

    Collection<? extends PlayerDefinition> connectedPlayers();

    CompletableFuture<Void> start();

    CompletableFuture<Void> shutdown();

    boolean isRunning();
}


