package org.veltismc.veltis.config;

/**
 * Factory for default configuration node trees.
 *
 * <p>Each method returns a {@link ConfigurationNode} map that
 * represents the default values for a VeltisMC configuration file.
 */
final class DefaultConfigs {

    private DefaultConfigs() {
    }

    /**
     * Default server.yml configuration.
     *
     * <pre>
     * server:
     *   name: VeltisMC Server
     *   port: 25565
     *   max-players: 20
     *   online-mode: false
     *   motd: A VeltisMC Minecraft Server
     * world:
     *   level-name: world
     *   allow-flight: false
     *   max-build-height: 320
     *   view-distance: 10
     *   simulation-distance: 10
     * </pre>
     */
    static ConfigurationNode server() {
        return ConfigurationNode.map(
            "server", ConfigurationNode.map(
                "name", ConfigurationNode.of("VeltisMC Server"),
                "port", ConfigurationNode.of(25565),
                "max-players", ConfigurationNode.of(20),
                "online-mode", ConfigurationNode.of(false),
                "motd", ConfigurationNode.of("A VeltisMC Minecraft Server")
            ),
            "world", ConfigurationNode.map(
                "level-name", ConfigurationNode.of("world"),
                "allow-flight", ConfigurationNode.of(false),
                "max-build-height", ConfigurationNode.of(320),
                "view-distance", ConfigurationNode.of(10),
                "simulation-distance", ConfigurationNode.of(10)
            )
        );
    }

    /**
     * Default runtime.yml configuration.
     *
     * <pre>
     * runtime:
     *   minecraft-version: 26.2
     *   protocol-version: 768
     *   java-home: auto
     *   max-memory: 2G
     *   min-memory: 512M
     *   jvm-args: -XX:+UseZGC
     * thread-pool:
     *   virtual-threads: true
     *   worker-count: 4
     * </pre>
     */
    static ConfigurationNode runtime() {
        return ConfigurationNode.map(
            "runtime", ConfigurationNode.map(
                "minecraft-version", ConfigurationNode.of("26.2"),
                "protocol-version", ConfigurationNode.of(768),
                "java-home", ConfigurationNode.of("auto"),
                "max-memory", ConfigurationNode.of("2G"),
                "min-memory", ConfigurationNode.of("512M"),
                "jvm-args", ConfigurationNode.of("-XX:+UseZGC -XX:+ZGenerational")
            ),
            "thread-pool", ConfigurationNode.map(
                "virtual-threads", ConfigurationNode.of(true),
                "worker-count", ConfigurationNode.of(4)
            )
        );
    }

    /**
     * Default metrics.yml configuration.
     *
     * <pre>
     * tick-engine:
     *   target-tps: 20
     *   tps-window: 100
     *   lag-spike-threshold-ms: 50
     * metrics:
     *   enabled: true
     *   sample-interval-ms: 1000
     *   log-tps: true
     *   log-memory: true
     *   tps-warn-threshold: 15.0
     *   tps-critical-threshold: 5.0
     * </pre>
     */
    static ConfigurationNode metrics() {
        return ConfigurationNode.map(
            "tick-engine", ConfigurationNode.map(
                "target-tps", ConfigurationNode.of(20),
                "tps-window", ConfigurationNode.of(100),
                "lag-spike-threshold-ms", ConfigurationNode.of(50)
            ),
            "metrics", ConfigurationNode.map(
                "enabled", ConfigurationNode.of(true),
                "sample-interval-ms", ConfigurationNode.of(1000),
                "log-tps", ConfigurationNode.of(true),
                "log-memory", ConfigurationNode.of(true),
                "tps-warn-threshold", ConfigurationNode.of(15.0),
                "tps-critical-threshold", ConfigurationNode.of(5.0)
            )
        );
    }

    /**
     * Default scheduler.yml configuration.
     *
     * <pre>
     * scheduler:
     *   max-pending-tasks: 10000
     *   default-timeout-ms: 30000
     *   warn-on-overload: true
     * pool:
     *   virtual-threads: true
     *   core-threads: 4
     *   max-threads: 8
     * </pre>
     */
    static ConfigurationNode scheduler() {
        return ConfigurationNode.map(
            "scheduler", ConfigurationNode.map(
                "max-pending-tasks", ConfigurationNode.of(10000),
                "default-timeout-ms", ConfigurationNode.of(30000),
                "warn-on-overload", ConfigurationNode.of(true)
            ),
            "pool", ConfigurationNode.map(
                "virtual-threads", ConfigurationNode.of(true),
                "core-threads", ConfigurationNode.of(4),
                "max-threads", ConfigurationNode.of(8)
            )
        );
    }

    /**
     * Default logging.yml configuration.
     *
     * <pre>
     * logging:
     *   joins: true
     *   quits: true
     *   kicks: true
     *   commands: true
     *   plugins: true
     *   worlds: true
     *   chat: false
     * </pre>
     */
    static ConfigurationNode logging() {
        return ConfigurationNode.map(
            "logging", ConfigurationNode.map(
                "joins", ConfigurationNode.of(true),
                "quits", ConfigurationNode.of(true),
                "kicks", ConfigurationNode.of(true),
                "commands", ConfigurationNode.of(true),
                "plugins", ConfigurationNode.of(true),
                "worlds", ConfigurationNode.of(true),
                "chat", ConfigurationNode.of(false)
            )
        );
    }
}



