package org.veltismc.veltis.server.runtime;

import org.veltismc.veltis.server.container.DefaultPlayerContainer;
import org.veltismc.veltis.server.container.DefaultWorldContainer;
import org.veltismc.veltis.server.container.PlayerContainer;
import org.veltismc.veltis.server.container.WorldContainer;
import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.server.event.SimpleEventBus;
import org.veltismc.veltis.server.lifecycle.DefaultLifecycleManager;
import org.veltismc.veltis.server.lifecycle.LifecycleManager;
import org.veltismc.veltis.server.lifecycle.LifecyclePhase;
import org.veltismc.veltis.server.metrics.ServerMetrics;
import org.veltismc.veltis.server.scheduler.DefaultTaskScheduler;
import org.veltismc.veltis.server.scheduler.TaskScheduler;
import org.veltismc.veltis.server.service.DefaultServiceRegistry;
import org.veltismc.veltis.server.service.ServiceRegistry;
import org.veltismc.veltis.server.tick.DefaultTickEngine;
import org.veltismc.veltis.server.tick.TickEngine;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultServerRuntime implements ServerRuntime {

    private static final Logger LOG = System.getLogger(DefaultServerRuntime.class.getName());

    private final AtomicReference<RuntimeState> state;
    private final TickEngine tickEngine;
    private final TaskScheduler scheduler;
    private final EventBus eventBus;
    private final ServiceRegistry services;
    private final LifecycleManager lifecycle;
    private final PlayerContainer players;
    private final WorldContainer worlds;

    public DefaultServerRuntime() {
        this.state = new AtomicReference<>(RuntimeState.CREATED);
        this.services = new DefaultServiceRegistry();
        this.lifecycle = new DefaultLifecycleManager();
        this.eventBus = new SimpleEventBus();
        this.scheduler = new DefaultTaskScheduler();
        this.tickEngine = new DefaultTickEngine();
        this.players = new DefaultPlayerContainer();
        this.worlds = new DefaultWorldContainer();
    }

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            if (!state.compareAndSet(RuntimeState.CREATED, RuntimeState.INITIALIZING)) {
                throw new IllegalStateException(
                    "Cannot start runtime from state: " + state.get());
            }
            try {
                state.set(RuntimeState.STARTING);
                lifecycle.transition(LifecyclePhase.INITIALIZING);
                LOG.log(Level.INFO, "Starting VeltisMC runtime...");

                services.initializeAll();
                lifecycle.transition(LifecyclePhase.STARTING);

                tickEngine.start();

                lifecycle.transition(LifecyclePhase.RUNNING);
                state.set(RuntimeState.RUNNING);
                LOG.log(Level.INFO, "VeltisMC runtime started successfully");
            } catch (Exception e) {
                state.set(RuntimeState.FAILED);
                lifecycle.transition(LifecyclePhase.CRASHED);
                LOG.log(Level.ERROR, "Failed to start runtime", e);
                throw new RuntimeException("Runtime startup failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            var previous = state.getAndSet(RuntimeState.STOPPING);
            if (previous == RuntimeState.STOPPED || previous == RuntimeState.STOPPING) {
                return;
            }
            try {
                lifecycle.transition(LifecyclePhase.STOPPING);
                LOG.log(Level.INFO, "Shutting down VeltisMC runtime...");

                scheduler.shutdown();
                tickEngine.stop();
                services.shutdownAll();

                lifecycle.transition(LifecyclePhase.STOPPED);
                state.set(RuntimeState.STOPPED);
                LOG.log(Level.INFO, "VeltisMC runtime stopped");
            } catch (Exception e) {
                state.set(RuntimeState.FAILED);
                lifecycle.transition(LifecyclePhase.CRASHED);
                LOG.log(Level.ERROR, "Failed to shutdown runtime", e);
                throw new RuntimeException("Runtime shutdown failed", e);
            }
        });
    }

    @Override
    public boolean isRunning() {
        return state.get() == RuntimeState.RUNNING;
    }

    @Override
    public RuntimeState state() {
        return state.get();
    }

    @Override
    public TickEngine tickEngine() {
        return tickEngine;
    }

    @Override
    public TaskScheduler scheduler() {
        return scheduler;
    }

    @Override
    public EventBus eventBus() {
        return eventBus;
    }

    @Override
    public ServiceRegistry services() {
        return services;
    }

    @Override
    public LifecycleManager lifecycle() {
        return lifecycle;
    }

    @Override
    public PlayerContainer players() {
        return players;
    }

    @Override
    public WorldContainer worlds() {
        return worlds;
    }

    @Override
    public ServerMetrics metrics() {
        return tickEngine.metrics();
    }
}



