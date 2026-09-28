package org.veltismc.runtime;

import org.veltismc.runtime.container.PlayerContainer;
import org.veltismc.runtime.container.WorldContainer;
import org.veltismc.runtime.event.EventBus;
import org.veltismc.runtime.lifecycle.LifecycleManager;
import org.veltismc.runtime.metrics.ServerMetrics;
import org.veltismc.runtime.scheduler.TaskScheduler;
import org.veltismc.runtime.service.ServiceRegistry;
import org.veltismc.runtime.tick.TickEngine;
import org.veltismc.world.api.WorldEngine;

import java.util.concurrent.CompletableFuture;

public interface ServerRuntime {

    CompletableFuture<Void> start();

    CompletableFuture<Void> shutdown();

    boolean isRunning();

    RuntimeState state();

    TickEngine tickEngine();

    TaskScheduler scheduler();

    /** The world simulation engine (region-based, multithreaded). */
    WorldEngine worldEngine();

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


