package org.veltismc.patchengine;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration for the patch engine runtime.
 */
public record PatchEngineConfig(
    String minecraftVersion,
    Path homeDirectory,
    Path patchesDirectory
) {
    public PatchEngineConfig {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion cannot be null");
        Objects.requireNonNull(homeDirectory, "homeDirectory cannot be null");
        Objects.requireNonNull(patchesDirectory, "patchesDirectory cannot be null");
    }

    public Path vanillaJarsDirectory() {
        return homeDirectory.resolve("vanilla").resolve(minecraftVersion);
    }

    public Path versionsDirectory() {
        return homeDirectory.resolve("versions").resolve(minecraftVersion);
    }

    public Path vanillaServerJar() {
        return vanillaJarsDirectory().resolve("server.jar");
    }

    public Path patchedServerJar() {
        return versionsDirectory().resolve("veltismc-server.jar");
    }

    public Path patchedSourceDirectory() {
        return versionsDirectory().resolve("patched-source");
    }

    public Path compiledClassesDirectory() {
        return versionsDirectory().resolve("classes");
    }

    public Path librariesDirectory() {
        return homeDirectory().resolve("libraries");
    }

}
