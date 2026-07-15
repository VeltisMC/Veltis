package org.veltismc.veltis.data;

import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.storage.StorageLocation;
import org.veltismc.veltis.storage.StorageManager;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central data manager for runtime persistent data.
 *
 * <p>Stores data as JSON files under the {@link StorageLocation#DATA}
 * directory. Provides synchronous and asynchronous access.
 *
 * <p>Thread-safe with in-memory caching.
 */
public final class DataManager implements DataRepository {

    private static final Logger LOG = System.getLogger(DataManager.class.getName());

    private final StorageManager storageManager;
    private final EventBus eventBus;
    private final ConcurrentHashMap<String, DataContainer> cache;
    private final Path dataDir;

    /**
     * Creates a new data manager.
     *
     * @param storageManager the storage manager for directory resolution
     * @param eventBus       the event bus for publishing data events
     */
    public DataManager(StorageManager storageManager, EventBus eventBus) {
        this.storageManager = storageManager;
        this.eventBus = eventBus;
        this.cache = new ConcurrentHashMap<>();
        this.dataDir = storageManager.resolve(StorageLocation.DATA);
    }

    /**
     * Initializes the data directory.
     */
    public void initialize() throws IOException {
        Files.createDirectories(dataDir);
        LOG.log(Level.INFO, "Data manager initialized in {0}", dataDir);
    }

    @Override
    public Optional<DataContainer> read(String key) throws IOException {
        var cached = cache.get(key);
        if (cached != null) return Optional.of(cached);

        var path = resolvePath(key);
        if (Files.notExists(path)) return Optional.empty();

        var container = DataSerializer.loadJson(path);
        cache.put(key, container);
        return Optional.of(container);
    }

    @Override
    public void write(String key, DataContainer value) throws IOException {
        var path = resolvePath(key);
        Files.createDirectories(path.getParent());
        DataSerializer.saveJson(path, value);
        cache.put(key, value);
        eventBus.publish(new DataSavedEvent(key, path));
    }

    @Override
    public void delete(String key) throws IOException {
        cache.remove(key);
        Files.deleteIfExists(resolvePath(key));
    }

    @Override
    public boolean exists(String key) throws IOException {
        return cache.containsKey(key) || Files.exists(resolvePath(key));
    }

    @Override
    public CompletableFuture<Optional<DataContainer>> readAsync(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read(key);
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Async read failed for key: {0}", key);
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletableFuture<Void> writeAsync(String key, DataContainer value) {
        return CompletableFuture.runAsync(() -> {
            try {
                write(key, value);
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Async write failed for key: {0}", key);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteAsync(String key) {
        return CompletableFuture.runAsync(() -> {
            try {
                delete(key);
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Async delete failed for key: {0}", key);
            }
        });
    }

    /**
     * Evicts a key from the in-memory cache.
     */
    public void evict(String key) {
        cache.remove(key);
    }

    /**
     * Clears the in-memory cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Returns the number of cached entries.
     */
    public int cachedCount() {
        return cache.size();
    }

    private Path resolvePath(String key) {
        return dataDir.resolve(key.replace('.', '/') + ".json");
    }
}


