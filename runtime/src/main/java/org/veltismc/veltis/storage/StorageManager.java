package org.veltismc.veltis.storage;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Objects;

/**
 * Manages server storage directories.
 *
 * <p>All storage locations are subdirectories of a single root path.
 * Directories are created lazily on first resolution.
 *
 * <p>Thread-safe and designed for constructor injection.
 */
public final class StorageManager implements StorageProvider {

    private static final Logger LOG = System.getLogger(StorageManager.class.getName());

    private final Path root;
    private final EnumMap<StorageLocation, Path> cache;

    /**
     * Creates a new storage manager with the given root directory.
     *
     * @param root the root storage directory
     */
    public StorageManager(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.cache = new EnumMap<>(StorageLocation.class);
    }

    @Override
    public Path resolve(StorageLocation location) {
        return cache.computeIfAbsent(location, loc -> {
            var resolved = root.resolve(loc.directory()).normalize();
            validatePath(resolved);
            return resolved;
        });
    }

    @Override
    public Path ensureDirectory(StorageLocation location) throws IOException {
        var path = resolve(location);
        if (Files.notExists(path)) {
            Files.createDirectories(path);
            LOG.log(Level.DEBUG, "Created storage directory: {0}", path);
        }
        return path;
    }

    /**
     * Initializes all storage directories.
     *
     * @throws IOException if any directory cannot be created
     */
    public void initializeAll() throws IOException {
        for (var loc : StorageLocation.values()) {
            ensureDirectory(loc);
        }
        LOG.log(Level.INFO, "Storage initialized at {0}", root);
    }

    /**
     * Returns the root storage directory.
     */
    public Path root() {
        return root;
    }

    private static void validatePath(Path path) {
        if (path.toString().isBlank()) {
            throw new IllegalArgumentException("Storage path must not be blank");
        }
    }
}


