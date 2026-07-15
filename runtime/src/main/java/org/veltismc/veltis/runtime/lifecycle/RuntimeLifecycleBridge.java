package org.veltismc.veltis.runtime.lifecycle;

import org.veltismc.veltis.runtime.MinecraftRuntime;
import org.veltismc.veltis.runtime.RuntimeState;
import org.veltismc.veltis.runtime.event.RuntimeCrashEvent;
import org.veltismc.veltis.runtime.event.RuntimeStartedEvent;
import org.veltismc.veltis.runtime.event.RuntimeStoppedEvent;
import org.veltismc.veltis.runtime.event.RuntimeStoppingEvent;
import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.server.lifecycle.LifecycleManager;
import org.veltismc.veltis.server.lifecycle.LifecycleParticipant;
import org.veltismc.veltis.server.lifecycle.LifecyclePhase;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronizes the Minecraft runtime state with the VeltisMC lifecycle.
 *
 * <p>Acting as a {@link LifecycleParticipant}, this bridge:
 * <ul>
 *   <li>Listens for lifecycle phase transitions</li>
 *   <li>Publishes runtime events ({@link RuntimeStartedEvent},
 *       {@link RuntimeStoppingEvent}, {@link RuntimeStoppedEvent},
 *       {@link RuntimeCrashEvent}) on the {@link EventBus}</li>
 *   <li>Updates the runtime state to match the lifecycle phase</li>
 *   <li>Handles runtime failures by transitioning to CRASHED</li>
 * </ul>
 */
public final class RuntimeLifecycleBridge implements LifecycleParticipant {

    private static final Logger LOG = System.getLogger(RuntimeLifecycleBridge.class.getName());

    private final MinecraftRuntime runtime;
    private final LifecycleManager lifecycleManager;
    private final EventBus eventBus;
    private final AtomicReference<RuntimeState> runtimeState;

    /**
     * Creates a new lifecycle bridge.
     *
     * @param runtime         the Minecraft runtime to bridge
     * @param lifecycleManager the VeltisMC lifecycle manager
     * @param eventBus        the event bus for publishing runtime events
     */
    public RuntimeLifecycleBridge(
        MinecraftRuntime runtime,
        LifecycleManager lifecycleManager,
        EventBus eventBus
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.runtimeState = new AtomicReference<>(RuntimeState.CREATED);
    }

    /**
     * Registers this bridge as a lifecycle participant and subscribes
     * to runtime state changes.
     */
    public void register() {
        lifecycleManager.register(this);
        LOG.log(Level.DEBUG, "RuntimeLifecycleBridge registered with lifecycle manager");
    }

    /**
     * Unregisters this bridge from the lifecycle manager.
     */
    public void unregister() {
        lifecycleManager.unregister(this);
        LOG.log(Level.DEBUG, "RuntimeLifecycleBridge unregistered");
    }

    /**
     * Transitions the tracked runtime state and publishes the
     * corresponding event.
     *
     * @param newState the target runtime state
     */
    public void transitionTo(RuntimeState newState) {
        var oldState = runtimeState.getAndSet(newState);
        if (oldState == newState) {
            return;
        }
        LOG.log(Level.DEBUG, "Runtime state: {0} -> {1}", oldState, newState);

        switch (newState) {
            case RUNNING -> {
                var context = runtime.context();
                eventBus.publish(new RuntimeStartedEvent(
                    System.currentTimeMillis(), context));
            }
            case STOPPING -> {
                eventBus.publish(new RuntimeStoppingEvent(
                    System.currentTimeMillis(), oldState));
            }
            case STOPPED -> {
                eventBus.publish(new RuntimeStoppedEvent(
                    runtime.uptimeMs(), runtime.tickCount()));
            }
            case CRASHED -> {
                eventBus.publish(new RuntimeCrashEvent(
                    System.currentTimeMillis(),
                    new RuntimeException("Runtime crashed"),
                    oldState));
            }
            default -> {
            }
        }
    }

    /**
     * Returns the current tracked runtime state.
     */
    public RuntimeState currentRuntimeState() {
        return runtimeState.get();
    }

    // -- LifecycleParticipant implementation --

    @Override
    public String name() {
        return "RuntimeLifecycleBridge";
    }

    @Override
    public void onPhaseChange(LifecyclePhase oldPhase, LifecyclePhase newPhase) {
        LOG.log(Level.DEBUG, "Lifecycle phase changed: {0} -> {1}", oldPhase, newPhase);
    }

    @Override
    public void onEntering(LifecyclePhase phase) {
        switch (phase) {
            case INITIALIZING -> transitionTo(RuntimeState.BOOTSTRAPPING);
            case STARTING -> transitionTo(RuntimeState.STARTING);
            case RUNNING -> transitionTo(RuntimeState.RUNNING);
            case STOPPING -> transitionTo(RuntimeState.STOPPING);
            case STOPPED -> transitionTo(RuntimeState.STOPPED);
            case CRASHED -> transitionTo(RuntimeState.CRASHED);
            default -> {
            }
        }
    }
}



