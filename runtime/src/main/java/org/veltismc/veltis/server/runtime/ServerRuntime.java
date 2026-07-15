package org.veltismc.veltis.server.runtime;

import org.veltismc.veltis.server.container.PlayerContainer;
import org.veltismc.veltis.server.container.WorldContainer;
import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.server.lifecycle.LifecycleManager;
import org.veltismc.veltis.server.metrics.ServerMetrics;
import org.veltismc.veltis.server.scheduler.TaskScheduler;
import org.veltismc.veltis.server.service.ServiceRegistry;
import org.veltismc.veltis.server.tick.TickEngine;

import java.util.concurrent.CompletableFuture;

public interface ServerRuntime {

    CompletableFuture<Void> start();

    CompletableFuture<Void> shutdown();

    boolean isRunning();

    RuntimeState state();

    TickEngine tickEngine();

    TaskScheduler scheduler();

    EventBus eventBus();

    ServiceRegistry services();

    LifecycleManager lifecycle();

    PlayerContainer players();

    WorldContainer worlds();

    ServerMetrics metrics();

    enum RuntimeState {
        CREATED,
        INITIALIZING,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }
}


