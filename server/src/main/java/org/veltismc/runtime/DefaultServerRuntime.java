package org.veltismc.runtime;

import org.veltismc.runtime.container.DefaultPlayerContainer;
import org.veltismc.runtime.container.DefaultWorldContainer;
import org.veltismc.runtime.container.PlayerContainer;
import org.veltismc.runtime.container.WorldContainer;
import org.veltismc.runtime.event.EventBus;
import org.veltismc.runtime.event.SimpleEventBus;
import org.veltismc.runtime.lifecycle.DefaultLifecycleManager;
import org.veltismc.runtime.lifecycle.LifecycleManager;
import org.veltismc.runtime.lifecycle.LifecyclePhase;
import org.veltismc.runtime.log.PhaseTimer;
import org.veltismc.runtime.metrics.ServerMetrics;
import org.veltismc.runtime.scheduler.DefaultTaskScheduler;
import org.veltismc.runtime.scheduler.TaskScheduler;
import org.veltismc.runtime.service.DefaultServiceRegistry;
import org.veltismc.runtime.service.ServiceRegistry;
import org.veltismc.runtime.service.WorldEngineService;
import org.veltismc.runtime.tick.DefaultTickEngine;
import org.veltismc.runtime.tick.TickEngine;
import org.veltismc.world.api.WorldEngine;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultServerRuntime implements ServerRuntime {

    private final AtomicReference<RuntimeState> state;
    private final TickEngine tickEngine;
    private final TaskScheduler scheduler;
    private final EventBus eventBus;
    private final ServiceRegistry services;
    private final LifecycleManager lifecycle;
    private final PlayerContainer players;
    private final WorldContainer worlds;
    private final WorldEngine worldEngine;

    public DefaultServerRuntime() {
        this.state = new AtomicReference<>(RuntimeState.CREATED);
        this.services = new DefaultServiceRegistry();
        this.lifecycle = new DefaultLifecycleManager();
        this.eventBus = new SimpleEventBus();
        this.scheduler = new DefaultTaskScheduler();
        this.tickEngine = new DefaultTickEngine();
        this.players = new DefaultPlayerContainer();
        this.worlds = new DefaultWorldContainer();
        WorldEngineService worldEngineService = new WorldEngineService();
        this.worldEngine = worldEngineService.engine();
        this.services.register(worldEngineService);
    }

    @Override
    public CompletableFuture<Void> start() {
        // Deliberately synchronous on the calling thread (vanilla's boot
        // thread): startup order must be deterministic and the caller's Done
        // message must only print after Veltis is fully up. The returned
        // future keeps the join()/CompletionException test contract.
        var future = new CompletableFuture<Void>();
        if (!state.compareAndSet(RuntimeState.CREATED, RuntimeState.INITIALIZING)) {
            future.completeExceptionally(new IllegalStateException(
                "Cannot start runtime from state: " + state.get()));
            return future;
        }
        try {
            state.set(RuntimeState.STARTING);
            lifecycle.transition(LifecyclePhase.INITIALIZING);
            var timer = PhaseTimer.start("Starting runtime");

            services.initializeAll();
            lifecycle.transition(LifecyclePhase.STARTING);

            tickEngine.start();

            lifecycle.transition(LifecyclePhase.RUNNING);
            state.set(RuntimeState.RUNNING);
            timer.complete("Runtime started");
            future.complete(null);
        } catch (Exception e) {
            state.set(RuntimeState.FAILED);
            lifecycle.transition(LifecyclePhase.CRASHED);
            future.completeExceptionally(new RuntimeException("Runtime startup failed", e));
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        // Synchronous for the same reason as start(): the caller (vanilla's
        // stop sequence) waits for a real, ordered shutdown.
        var future = new CompletableFuture<Void>();
        var previous = state.get();
        // Guard before mutating: a repeated/concurrent shutdown must not
        // push an already stopped runtime back into STOPPING.
        if (previous == RuntimeState.STOPPED || previous == RuntimeState.STOPPING) {
            future.complete(null);
            return future;
        }
        if (!state.compareAndSet(previous, RuntimeState.STOPPING)) {
            future.complete(null);
            return future;
        }
        try {
            lifecycle.transition(LifecyclePhase.STOPPING);

            scheduler.shutdown();
            tickEngine.stop();
            services.shutdownAll();

            lifecycle.transition(LifecyclePhase.STOPPED);
            state.set(RuntimeState.STOPPED);
            future.complete(null);
        } catch (Exception e) {
            state.set(RuntimeState.FAILED);
            lifecycle.transition(LifecyclePhase.CRASHED);
            future.completeExceptionally(new RuntimeException("Runtime shutdown failed", e));
        }
        return future;
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
    public WorldEngine worldEngine() {
        return worldEngine;
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



