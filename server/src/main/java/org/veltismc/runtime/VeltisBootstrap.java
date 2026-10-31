package org.veltismc.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.veltismc.nativeengine.NativeConfig;
import org.veltismc.nativeengine.NativeEngine;
import org.veltismc.nativeengine.NativeStatus;

import java.nio.file.Path;

/**
 * Minimal runtime bootstrap for the bare-NMS VeltisMC server.
 *
 * <p>Entry points are invoked reflectively to avoid hard compile-time coupling
 * between the launcher-side entrypoint ({@code org.veltismc.server.Main}) and
 * the runtime module:
 * <ul>
 *   <li>{@link #boot(String[])} — pre-Minecraft initialization (home dir, config).</li>
 *   <li>{@link #onMinecraftServerCreated(Object)} — invoked by patch 016, just
 *       before vanilla prints its Done line, once the dedicated server has
 *       finished starting. It runs the runtime startup synchronously so
 *       {@code Done} can only appear after Veltis is fully up.</li>
 *   <li>{@link #onServerStopping()} — invoked by patch 017 from
 *       {@code MinecraftServer.stopServer}, so shutdown is deterministic too.</li>
 * </ul>
 */
public final class VeltisBootstrap {

    private static final Logger LOG = LogManager.getLogger(VeltisBootstrap.class);

    private static boolean booted;

    public static volatile Object MINECRAFT_SERVER;

    private static volatile org.veltismc.runtime.ServerRuntime runtime;
    private static volatile org.veltismc.world.nms.VeltisWorldIntegration worldIntegration;

    private VeltisBootstrap() {}

    public static void boot(String[] args) {
        if (booted) return;
        booted = true;

        // An installation that was once started by an earlier build may still
        // hold the retired Paper-style configuration files. They are reported
        // once and then left alone: nothing is read, nothing is required, and
        // nothing is deleted. See LegacyConfig for why there is nothing to
        // migrate.
        var home = detectHomeDirectory(args);
        org.veltismc.runtime.config.LegacyConfig.warnIfPresent(home, LOG);

        // Native support is optional and never fatal. The configuration is read
        // from the same server.properties file the rest of the server uses, with
        // -Dveltis.native.* taking precedence for a single run.
        initializeNative(home);
    }

    public static void onMinecraftServerCreated(Object server) {
        if (MINECRAFT_SERVER != null) return;
        MINECRAFT_SERVER = server;
        startRuntime();
        bindMinecraftLevels(server);
    }

    public static void onServerStopping() {
        unbindMinecraftLevels();
        MINECRAFT_SERVER = null;
        stopRuntime();
        stopNative();
    }

    /**
     * Server-side access point for the VeltisMC runtime (world engine, scheduler,
     * services). Null until the Minecraft server has been created.
     */
    public static org.veltismc.runtime.ServerRuntime runtime() {
        return runtime;
    }

    /**
     * The optional native engine facade. Callers must still handle
     * {@link NativeStatus#fallbackActive()}; the server itself does not assume a
     * native library is present.
     */
    public static NativeEngine nativeEngine() {
        return NativeEngine.shared();
    }

    /**
     * Attempts native initialization once, logging the outcome. A missing or
     * incompatible library is reported and leaves the server on Java fallbacks.
     */
    private static void initializeNative(Path home) {
        try {
            var config = NativeConfig.load(home);
            var status = NativeEngine.shared().initialize(config);
            if (status.available()) {
                LOG.info("Native support enabled: {}", status.summary());
            } else if (status.loaded()) {
                LOG.warn("Native library was found but cannot be used; Java fallback active: {}",
                        status.reason());
            } else {
                LOG.info("Native support unavailable; Java fallback active: {}", status.reason());
            }
        } catch (Throwable t) {
            LOG.warn("Failed to initialize native support; Java fallback remains active", t);
        }
    }

    private static void stopNative() {
        try {
            NativeEngine.shared().shutdown();
        } catch (Throwable t) {
            LOG.warn("Failed to shut down native support", t);
        }
    }

    /**
     * Starts the runtime on the calling thread (vanilla's boot thread) so the
     * startup order is deterministic: nothing asynchronous races the vanilla
     * Done message. Start/stop logging lives in {@link DefaultServerRuntime}
     * and {@link org.veltismc.runtime.service.WorldEngineService}; only
     * failures are reported here.
     */
    private static void startRuntime() {
        try {
            var rt = new DefaultServerRuntime();
            runtime = rt;
            rt.start().join();
        } catch (Throwable t) {
            runtime = null;
            LOG.error("Failed to start runtime", rootCause(t));
        }
    }

    /**
     * Binds every existing ServerLevel to its own Veltis world, only after the
     * world engine has started (so no world exists before it runs). One level
     * maps to one world, named after its dimension. Never fatal: a failure
     * leaves the engine running without level bindings, matching how a failed
     * {@link #startRuntime()} leaves the vanilla server usable.
     */
    private static void bindMinecraftLevels(Object server) {
        var rt = runtime;
        if (rt == null) {
            return;
        }
        try {
            worldIntegration =
                    org.veltismc.world.nms.VeltisWorldIntegration.install(rt.worldEngine(), server);
        } catch (Throwable t) {
            worldIntegration = null;
            LOG.error("Failed to bind Minecraft levels to Veltis worlds", rootCause(t));
        }
    }

    /** Drops every level hook and binding before the runtime shuts down. */
    private static void unbindMinecraftLevels() {
        var integration = worldIntegration;
        worldIntegration = null;
        if (integration == null) return;
        try {
            integration.shutdown();
        } catch (Throwable t) {
            LOG.error("Failed to clear Minecraft level bindings", rootCause(t));
        }
    }

    private static void stopRuntime() {
        var rt = runtime;
        runtime = null;
        if (rt == null) return;
        LOG.info("Stopping runtime");
        try {
            rt.shutdown().join();
            LOG.info("VeltisMC stopped");
        } catch (Throwable t) {
            LOG.error("Failed to stop runtime", rootCause(t));
        }
    }

    /** Unwraps {@code CompletionException} so logs show the real failure. */
    private static Throwable rootCause(Throwable t) {
        var cause = t.getCause();
        return cause != null ? cause : t;
    }

    private static Path detectHomeDirectory(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--home".equals(args[i])) return Path.of(args[i + 1]);
        }
        var home = System.getProperty("veltismc.home");
        return home != null ? Path.of(home) : Path.of(".");
    }
}
