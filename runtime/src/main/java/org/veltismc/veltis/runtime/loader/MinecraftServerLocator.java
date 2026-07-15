package org.veltismc.veltis.runtime.loader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class MinecraftServerLocator {

    private static final List<String> SEARCH_PATTERNS = List.of(
        "minecraft_server.%s.jar",
        "minecraft_server.jar",
        "server.jar",
        "minecraft-server-%s.jar",
        "paper-%s.jar",
        "purpur-%s.jar"
    );

    private static final List<Path> SEARCH_DIRECTORIES = List.of(
        Path.of(""),
        Path.of("libs"),
        Path.of("libraries"),
        Path.of("versions"),
        Path.of("server")
    );

    private final Path rootDirectory;
    private final String minecraftVersion;

    public MinecraftServerLocator(Path rootDirectory, String minecraftVersion) {
        this.rootDirectory = rootDirectory;
        this.minecraftVersion = minecraftVersion;
    }

    public MinecraftServerLocator(Path rootDirectory) {
        this(rootDirectory, "26.2");
    }

    public Optional<Path> locate() {
        var override = System.getProperty("VeltisMC.server-jar");
        if (override != null && !override.isBlank()) {
            var path = Path.of(override);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath());
            }
        }
        for (var dir : searchDirectories()) {
            for (var pattern : SEARCH_PATTERNS) {
                for (var version : versionVariants()) {
                    var name = String.format(pattern, version);
                    var path = dir.resolve(name);
                    if (Files.isRegularFile(path)) {
                        return Optional.of(path.toAbsolutePath());
                    }
                }
            }
        }
        return Optional.empty();
    }

    public boolean exists() {
        return locate().isPresent();
    }

    private List<Path> searchDirectories() {
        return List.of(
            rootDirectory,
            rootDirectory.resolve("libs"),
            rootDirectory.resolve("libraries"),
            rootDirectory.resolve("versions"),
            rootDirectory.resolve("server"),
            rootDirectory.resolve("runtime").resolve("minecraft").resolve(minecraftVersion)
        );
    }

    private List<String> versionVariants() {
        return List.of(minecraftVersion, minecraftVersion.replace(".", "_"));
    }
}



