package org.veltismc.runtime;

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
    }

    public static void onServerStopping() {
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
        } catch (Throwable t) {
            runtime = null;
            LOG.error("[VeltisMC] Failed to start VeltisMC runtime", rootCause(t));
        }
    }

    private static void stopRuntime() {
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
