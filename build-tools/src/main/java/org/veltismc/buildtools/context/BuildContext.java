package org.veltismc.buildtools.context;

import org.veltismc.buildtools.version.MinecraftVersion;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class BuildContext {

    private final MinecraftVersion targetVersion;
    private final Path workingDirectory;
    private final Path cacheDirectory;
    private final Path outputDirectory;
    private final Map<String, Object> sharedState;

    private BuildContext(Builder builder) {
        this.targetVersion = builder.targetVersion;
        this.workingDirectory = builder.workingDirectory;
        this.cacheDirectory = builder.cacheDirectory;
        this.outputDirectory = builder.outputDirectory;
        this.sharedState = new HashMap<>(builder.sharedState);
    }

    public static Builder builder() {
        return new Builder();
    }

    public MinecraftVersion targetVersion() {
        return targetVersion;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public Path cacheDirectory() {
        return cacheDirectory;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Path serverJarPath() {
        return cacheDirectory.resolve("server.jar");
    }

    public Path mappingsPath() {
        return cacheDirectory.resolve("mappings.tiny");
    }

    public Path minecraftSourcePath() {
        return cacheDirectory.resolve("minecraft-source");
    }

    public Path patchedSourcePath() {
        return cacheDirectory.resolve("patched-source");
    }

    public Path baselineSourcePath() {
        return cacheDirectory.resolve("patched-source-baseline");
    }

    public Path serverPatchesPath() {
        return workingDirectory.resolve("server/patches");
    }

    public Path veltismcServerJarPath() {
        return workingDirectory.resolve("build/veltismc-server.jar");
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> state(String key) {
        return Optional.ofNullable((T) sharedState.get(key));
    }

    public void state(String key, Object value) {
        sharedState.put(key, value);
    }

    public static final class Builder {
        private MinecraftVersion targetVersion;
        private Path workingDirectory;
        private Path cacheDirectory;
        private Path outputDirectory;
        private final Map<String, Object> sharedState = new HashMap<>();

        private Builder() {
        }

        public Builder targetVersion(MinecraftVersion version) {
            this.targetVersion = version;
            return this;
        }

        public Builder workingDirectory(Path path) {
            this.workingDirectory = path;
            return this;
        }

        public Builder cacheDirectory(Path path) {
            this.cacheDirectory = path;
            return this;
        }

        public Builder outputDirectory(Path path) {
            this.outputDirectory = path;
            return this;
        }

        public Builder state(String key, Object value) {
            this.sharedState.put(key, value);
            return this;
        }

        public BuildContext build() {
            if (targetVersion == null) {
                throw new IllegalStateException("targetVersion is required");
            }
            if (workingDirectory == null) {
                workingDirectory = Path.of(".");
            }
            if (cacheDirectory == null) {
                cacheDirectory = workingDirectory.resolve("ver/" + targetVersion);
            }
            if (outputDirectory == null) {
                outputDirectory = cacheDirectory;
            }
            return new BuildContext(this);
        }
    }
}
