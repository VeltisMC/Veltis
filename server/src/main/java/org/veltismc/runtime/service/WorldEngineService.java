package org.veltismc.runtime.service;

import org.veltismc.runtime.log.PhaseTimer;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;

/**
 * Manages the {@link WorldEngine} lifecycle as a runtime {@link Service}.
 *
 * <p>Registered with the runtime's {@link ServiceRegistry} and started/stopped
 * by the registry during server startup and shutdown. Host code obtains the
 * engine through {@code runtime.worldEngine()} or
 * {@code runtime.services().get(WorldEngineService.class)}.
 */
public final class WorldEngineService implements Service {

    private final WorldEngine engine;
    private volatile State state = State.CREATED;

    public WorldEngineService() {
        this(WorldConfig.defaults());
    }

    public WorldEngineService(WorldConfig config) {
        this.engine = WorldEngines.create(config);
    }

    @Override
    public String name() {
        return "world-engine";
    }

    @Override
    public Class<? extends Service> type() {
        return WorldEngineService.class;
    }

    @Override
    public State state() {
        return state;
    }

    /** Starts the engine's workers and scheduler. Idempotent. */
    @Override
    public void initialize() {
        if (state != State.CREATED) {
            return;
        }
        var timer = PhaseTimer.start("[VeltisMC] Loading world engine");
        engine.start();
        state = State.STARTED;
        timer.complete("[VeltisMC] World engine initialized");
    }

    /** Stops the engine: flushes saves, drains queues, joins workers. Idempotent. */
    @Override
    public void shutdown() {
        if (state != State.STARTED) {
            return;
        }
        engine.stop();
        state = State.STOPPED;
    }

    /** The wrapped engine. */
    public WorldEngine engine() {
        return engine;
    }
}
