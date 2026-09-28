package org.veltismc.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LauncherManifest {

    private final Path homeDirectory;
    private final Map<String, String> flags;

    private LauncherManifest(Path homeDirectory, Map<String, String> flags) {
        this.homeDirectory = homeDirectory;
        this.flags = Map.copyOf(flags);
    }

    public static LauncherManifest from(String[] args) {
        var flags = parseFlags(args);
        var home = detectHomeDirectory(flags);
        return new LauncherManifest(home, flags);
    }

    public Path homeDirectory() {
        return homeDirectory;
    }

    public Optional<String> serverName() {
        return Optional.ofNullable(flags.get("name"));
    }

    public Optional<Integer> protocolVersion() {
        return Optional.ofNullable(flags.get("protocol"))
            .map(Integer::parseInt);
    }

    public Optional<String> minecraftVersion() {
        return Optional.ofNullable(flags.get("version"));
    }

    public Optional<Integer> port() {
        return Optional.ofNullable(flags.get("port"))
            .map(Integer::parseInt);
    }

    public Optional<Integer> maxPlayers() {
        return Optional.ofNullable(flags.get("max-players"))
            .map(Integer::parseInt);
    }

    public boolean hasFlag(String key) {
        return flags.containsKey(key);
    }

    /**
     * Server data directory: {@code --home <dir>} wins, then the
     * {@code veltismc.home} system property, then the working directory
     * (matching {@code VeltisBootstrap} and the build-tools {@code --home} flag).
     */
    private static Path detectHomeDirectory(Map<String, String> flags) {
        var customHome = flags.get("home");
        if (customHome != null) {
            return Paths.get(customHome).toAbsolutePath().normalize();
        }
        var property = System.getProperty("veltismc.home");
        if (property != null) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static Map<String, String> parseFlags(String[] args) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    map.put(key, args[++i]);
                } else {
                    map.put(key, "true");
                }
            }
        }
        return map;
    }
}


