package org.veltismc.veltis.runtime.minecraft;

import org.veltismc.veltis.runtime.RuntimeConfiguration;
import org.veltismc.veltis.runtime.RuntimeState;
import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link MinecraftServerController}.
 *
 * <p>Delegates all server operations to the {@link MinecraftRuntimeAdapter}
 * and tracks runtime state with validated transitions.
 */
public final class DefaultMinecraftServerController implements MinecraftServerController {

    private static final Logger LOG = System.getLogger(DefaultMinecraftServerController.class.getName());

    private final MinecraftRuntimeAdapter adapter;
    private final RuntimeConfiguration configuration;
    private final AtomicReference<RuntimeState> state;
    private final AtomicLong startedAtNanos;

    /**
     * Creates a new controller backed by the given adapter.
     *
     * @param adapter       the runtime adapter that bridges to Mojang classes
     * @param configuration the runtime configuration
     */
    public DefaultMinecraftServerController(
        MinecraftRuntimeAdapter adapter,
        RuntimeConfiguration configuration
    ) {
        this.adapter = adapter;
        this.configuration = configuration;
        this.state = new AtomicReference<>(RuntimeState.CREATED);
        this.startedAtNanos = new AtomicLong(0);
    }

    @Override
    public CompletableFuture<Void> startServer() {
        return CompletableFuture.runAsync(() -> {
            if (!state.compareAndSet(RuntimeState.CREATED, RuntimeState.STARTING)) {
                throw new IllegalStateException(
                    "Cannot start server from state: " + state.get());
            }
            LOG.log(Level.INFO, "Starting Minecraft server...");
            try {
                adapter.start(configuration);
                state.set(RuntimeState.RUNNING);
                startedAtNanos.set(System.nanoTime());
                LOG.log(Level.INFO, "Minecraft server is now running");
            } catch (Exception e) {
                state.set(RuntimeState.CRASHED);
                LOG.log(Level.ERROR, "Minecraft server failed to start", e);
                throw new RuntimeException("Server start failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> stopServer() {
        return CompletableFuture.runAsync(() -> {
            var previous = state.getAndSet(RuntimeState.STOPPING);
            if (previous == RuntimeState.STOPPED || previous == RuntimeState.STOPPING) {
                return;
            }
            LOG.log(Level.INFO, "Stopping Minecraft server...");
            try {
                adapter.stop();
                state.set(RuntimeState.STOPPED);
                LOG.log(Level.INFO, "Minecraft server stopped");
            } catch (Exception e) {
                state.set(RuntimeState.CRASHED);
                LOG.log(Level.ERROR, "Minecraft server failed to stop", e);
                throw new RuntimeException("Server stop failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> restartServer() {
        return stopServer().thenCompose(v -> startServer());
    }

    @Override
    public RuntimeState state() {
        return state.get();
    }

    @Override
    public boolean isRunning() {
        return state.get() == RuntimeState.RUNNING;
    }

    @Override
    public long tickCount() {
        try {
            return adapter.tickCount();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long uptimeMs() {
        var started = startedAtNanos.get();
        if (started == 0) return 0;
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public boolean isReady() {
        try {
            return adapter.isRunning() && adapter.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the runtime configuration used by this controller.
     */
    public RuntimeConfiguration configuration() {
        return configuration;
    }
}


