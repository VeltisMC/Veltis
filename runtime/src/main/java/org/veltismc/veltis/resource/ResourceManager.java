package org.veltismc.veltis.resource;

import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.storage.StorageLocation;
import org.veltismc.veltis.storage.StorageManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central resource manager for VeltisMC.
 *
 * <p>Loads resources from:
 * <ol>
 *   <li>The filesystem under the runtime/ directory</li>
 *   <li>The classpath (embedded JAR resources)</li>
 *   <li>Future plugin resource packs</li>
 * </ol>
 *
 * <p>Resources are cached after first load for fast repeated access.
 */
public final class ResourceManager implements ResourceProvider {

    private static final Logger LOG = System.getLogger(ResourceManager.class.getName());

    private final StorageManager storageManager;
    private final EventBus eventBus;
    private final ConcurrentHashMap<ResourceLocator, ResourceContainer> cache;

    /**
     * Creates a new resource manager.
     *
     * @param storageManager the storage manager for directory resolution
     * @param eventBus       the event bus for publishing resource events
     */
    public ResourceManager(StorageManager storageManager, EventBus eventBus) {
        this.storageManager = storageManager;
        this.eventBus = eventBus;
        this.cache = new ConcurrentHashMap<>();
    }

    @Override
    public InputStream open(ResourceLocator locator) throws IOException {
        var data = read(locator);
        return new java.io.ByteArrayInputStream(data);
    }

    @Override
    public byte[] read(ResourceLocator locator) throws IOException {
        var cached = cache.get(locator);
        if (cached != null) {
            return cached.data();
        }

        var container = loadResource(locator);
        cache.put(locator, container);
        eventBus.publish(new ResourceLoadedEvent(container));
        return container.data();
    }

    @Override
    public String readString(ResourceLocator locator) throws IOException {
        return new String(read(locator), StandardCharsets.UTF_8);
    }

    @Override
    public Optional<Path> path(ResourceLocator locator) {
        if (!"VeltisMC".equals(locator.namespace())) {
            return Optional.empty();
        }
        var resourceDir = storageManager.resolve(StorageLocation.RUNTIME);
        var filePath = resourceDir.resolve(locator.path());
        return Files.exists(filePath) ? Optional.of(filePath) : Optional.empty();
    }

    @Override
    public boolean exists(ResourceLocator locator) {
        if (cache.containsKey(locator)) return true;
        try {
            return path(locator).isPresent() || classpathResource(locator) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clears the resource cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Evicts a single resource from cache.
     */
    public void evict(ResourceLocator locator) {
        cache.remove(locator);
    }

    private ResourceContainer loadResource(ResourceLocator locator) throws IOException {
        var filePath = path(locator);
        if (filePath.isPresent()) {
            var data = Files.readAllBytes(filePath.get());
            LOG.log(Level.DEBUG, "Loaded filesystem resource: {0}", locator.full());
            return new ResourceContainer(locator, data, System.currentTimeMillis());
        }

        var classpathData = classpathResource(locator);
        if (classpathData != null) {
            LOG.log(Level.DEBUG, "Loaded classpath resource: {0}", locator.full());
            return new ResourceContainer(locator, classpathData, System.currentTimeMillis());
        }

        throw new IOException("Resource not found: " + locator.full());
    }

    private byte[] classpathResource(ResourceLocator locator) {
        try {
            var resourcePath = "/" + locator.namespace() + "/" + locator.path();
            var stream = getClass().getResourceAsStream(resourcePath);
            if (stream == null) return null;
            return stream.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}



