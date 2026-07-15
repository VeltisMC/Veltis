package org.veltismc.veltis.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A named configuration file loaded from disk.
 *
 * <p>Immutable after creation. Provides access to the root
 * {@link ConfigurationNode}, file metadata, and the format.
 *
 * @param name     logical name (e.g. "server", "runtime")
 * @param path     absolute path to the file on disk
 * @param format   serialization format (yml, json, toml)
 * @param root     the root configuration node
 * @param loadedAt timestamp when this file was loaded (millis)
 */
public record ConfigurationFile(
    String name,
    Path path,
    String format,
    ConfigurationNode root,
    long loadedAt
) {

    /**
     * Creates a configuration file.
     */
    public ConfigurationFile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(root, "root");
    }

    /**
     * Returns the file extension (e.g. "yml", "json", "toml").
     */
    public String extension() {
        return format;
    }

    /**
     * Returns the file size in bytes.
     */
    public long fileSize() {
        try {
            return java.nio.file.Files.size(path);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Gets a configuration value by dot-separated path.
     *
     * @param path dot-separated key path (e.g. "server.port")
     * @return the node at that path, or empty
     */
    public Optional<ConfigurationNode> at(String path) {
        var current = root;
        for (var key : path.split("\\.")) {
            var next = current.get(key);
            if (next.isEmpty()) return Optional.empty();
            current = next.get();
        }
        return Optional.of(current);
    }

    /**
     * Gets a string value by dot-separated path.
     */
    public String stringAt(String path, String fallback) {
        return at(path).map(ConfigurationNode::asString).orElse(fallback);
    }

    /**
     * Gets an integer value by dot-separated path.
     */
    public int intAt(String path, int fallback) {
        return at(path).map(ConfigurationNode::asInt).orElse(fallback);
    }

    /**
     * Gets a boolean value by dot-separated path.
     */
    public boolean booleanAt(String path, boolean fallback) {
        return at(path).map(ConfigurationNode::asBoolean).orElse(fallback);
    }
}


