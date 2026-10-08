package org.veltismc.runtime;

import org.veltismc.plugins.PluginLoader;
import org.veltismc.VeltisServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static Path homeDir;

    public static volatile Object MINECRAFT_SERVER;

    private static volatile org.veltismc.runtime.ServerRuntime runtime;
    private static volatile org.veltismc.world.nms.VeltisWorldIntegration worldIntegration;

    private static volatile PluginLoader pluginLoader;

    private VeltisBootstrap() {}

    public static void boot(String[] args) {
        if (booted) return;
        booted = true;

        homeDir = detectHomeDirectory(args);
        try {
            org.veltismc.runtime.config.VeltisConfig.load(homeDir);
        } catch (Exception e) {
            LOG.warn("[VeltisMC] Config load failed: {}", e.getMessage());
        }
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
    }

    /**
     * Server-side access point for the VeltisMC runtime (world engine, scheduler,
     * services). Null until the Minecraft server has been created.
     */
    public static org.veltismc.runtime.ServerRuntime runtime() {
        return runtime;
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

            org.slf4j.Logger slf4jLogger = org.slf4j.LoggerFactory.getLogger("PluginEngine");
            
            pluginLoader = new PluginLoader(homeDir, new VeltisServer(), slf4jLogger);
            pluginLoader.loadAllPlugins();

        } catch (Throwable t) {
            runtime = null;
            LOG.error("[VeltisMC] Failed to start VeltisMC runtime", rootCause(t));
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
            LOG.error("[VeltisMC] Failed to bind Minecraft levels to Veltis worlds", rootCause(t));
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
            LOG.error("[VeltisMC] Failed to clear Minecraft level bindings", rootCause(t));
        }
    }

    private static void stopRuntime() {
        var loader = pluginLoader;
        pluginLoader = null;
        if (loader != null) {
            try {
                loader.unloadAllPlugins();
            } catch (Throwable t) {
                LOG.error("[VeltisMC] Failed to cleanly unload plugins", rootCause(t));
            }
        }

        var rt = runtime;
        runtime = null;
        if (rt == null) return;
        LOG.info("Stopping VeltisMC");
        try {
            rt.shutdown().join();
            LOG.info("VeltisMC stopped");
        } catch (Throwable t) {
            LOG.error("[VeltisMC] Failed to stop VeltisMC runtime", rootCause(t));
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
