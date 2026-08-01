package org.veltismc.veltis;

import java.nio.file.Path;

/**
 * Minimal runtime bootstrap for the bare-NMS VeltisMC server.
 *
 * <p>Entry points are invoked reflectively to avoid hard compile-time coupling
 * between the launcher-side entrypoint ({@code org.veltismc.veltis.Main}) and
 * the runtime module:
 * <ul>
 *   <li>{@link #boot(String[])} — pre-Minecraft initialization (home dir, config).</li>
 *   <li>{@link #onMinecraftServerCreated(Object)} — invoked by the
 *       Wire-VeltisBootstrap-Integration patch once the dedicated server has finished starting.</li>
 * </ul>
 */
public final class VeltisBootstrap {

    private static final System.Logger LOG = System.getLogger(VeltisBootstrap.class.getName());

    private static boolean booted;
    private static Path homeDir;

    public static volatile Object MINECRAFT_SERVER;

    private VeltisBootstrap() {}

    public static void boot(String[] args) {
        if (booted) return;
        booted = true;

        homeDir = detectHomeDirectory(args);
        try {
            org.veltismc.veltis.config.VeltisConfig.load(homeDir);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "[VeltisMC] Config load failed: {0}", e.getMessage());
        }

        LOG.log(System.Logger.Level.INFO, "[VeltisMC] Bootstrap ready (home: {0})", homeDir);
    }

    public static void onMinecraftServerCreated(Object server) {
        if (MINECRAFT_SERVER != null) return;
        MINECRAFT_SERVER = server;
        LOG.log(System.Logger.Level.INFO, "[VeltisMC] Minecraft server created: {0}",
            server != null ? server.getClass().getName() : "null");
    }

    private static Path detectHomeDirectory(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--home".equals(args[i])) return Path.of(args[i + 1]);
        }
        var home = System.getProperty("veltismc.home");
        return home != null ? Path.of(home) : Path.of(".");
    }
}
