package org.veltismc.veltis.config;

import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.storage.StorageLocation;
import org.veltismc.veltis.storage.StorageManager;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central configuration manager for VeltisMC.
 *
 * <p>Manages loading, saving, and hot-reloading of all configuration
 * files. Thread-safe. All config files are stored under the
 * {@link StorageLocation#CONFIG} directory.
 *
 * <p>Usage:
 * <pre>{@code
 * var configManager = new ConfigurationManager(storageManager, eventBus);
 * configManager.initialize();
 * var serverConfig = configManager.get("server");
 * </pre>
 */
public final class ConfigurationManager implements ConfigurationProvider {

    private static final Logger LOG = System.getLogger(ConfigurationManager.class.getName());

    private final StorageManager storageManager;
    private final EventBus eventBus;
    private final ConcurrentHashMap<String, AtomicReference<ConfigurationFile>> loaded;
    private final CopyOnWriteArrayList<String> knownConfigs;

    /**
     * Creates a new configuration manager.
     *
     * @param storageManager the storage manager for directory resolution
     * @param eventBus       the event bus for publishing config events
     */
    public ConfigurationManager(StorageManager storageManager, EventBus eventBus) {
        this.storageManager = storageManager;
        this.eventBus = eventBus;
        this.loaded = new ConcurrentHashMap<>();
        this.knownConfigs = new CopyOnWriteArrayList<>();
    }

    /**
     * Initializes the config directory and generates default configs
     * if they do not exist.
     */
    public void initialize() throws IOException {
        var configDir = storageManager.resolve(StorageLocation.CONFIG);
        Files.createDirectories(configDir);

        generateDefault("server", configDir.resolve("server.yml"), DefaultConfigs.server());
        generateDefault("runtime", configDir.resolve("runtime.yml"), DefaultConfigs.runtime());
        generateDefault("metrics", configDir.resolve("metrics.yml"), DefaultConfigs.metrics());
        generateDefault("scheduler", configDir.resolve("scheduler.yml"), DefaultConfigs.scheduler());
        generateDefault("logging", configDir.resolve("logging.yml"), DefaultConfigs.logging());

        LOG.log(Level.INFO, "Configuration manager initialized in {0}", configDir);
    }

    private void generateDefault(String name, Path path, ConfigurationNode defaults) throws IOException {
        knownConfigs.add(name);
        if (Files.notExists(path)) {
            var format = ConfigurationSerializer.detectFormat(path.getFileName().toString());
            ConfigurationSerializer.save(path, format, defaults);
            LOG.log(Level.INFO, "Generated default config: {0}", path);
        }
        var file = ConfigurationSerializer.loadFile(name, path);
        loaded.put(name, new AtomicReference<>(file));
        eventBus.publish(new ConfigurationLoadedEvent(file));
    }

    @Override
    public ConfigurationFile load(String name) throws IOException {
        var configDir = storageManager.resolve(StorageLocation.CONFIG);
        var path = resolve(name);
        var file = ConfigurationSerializer.loadFile(name, path);
        loaded.put(name, new AtomicReference<>(file));
        eventBus.publish(new ConfigurationLoadedEvent(file));
        return file;
    }

    @Override
    public void save(ConfigurationFile file) {
        var ref = loaded.get(file.name());
        if (ref != null) ref.set(file);
        Thread.ofVirtual().name("config-save-" + file.name()).start(() -> {
            try {
                ConfigurationSerializer.saveFile(file);
                LOG.log(Level.INFO, "Saved configuration: {0}", file.name());
            } catch (IOException e) {
                LOG.log(Level.ERROR, "Failed to save configuration: {0} - {1}", file.name(), e.getMessage());
            }
        });
    }

    @Override
    public ConfigurationFile reload(String name) throws IOException {
        var path = resolve(name);
        if (Files.notExists(path)) {
            throw new IOException("Configuration not found: " + name);
        }
        var file = ConfigurationSerializer.loadFile(name, path);
        loaded.put(name, new AtomicReference<>(file));
        eventBus.publish(new ConfigurationReloadedEvent(file));
        LOG.log(Level.INFO, "Reloaded configuration: {0}", name);
        return file;
    }

    @Override
    public Path resolve(String name) {
        var configDir = storageManager.resolve(StorageLocation.CONFIG);
        return configDir.resolve(name + ".yml");
    }

    @Override
    public Optional<ConfigurationFile> current(String name) {
        var ref = loaded.get(name);
        return ref != null ? Optional.of(ref.get()) : Optional.empty();
    }

    @Override
    public boolean exists(String name) {
        return Files.exists(resolve(name));
    }

    /**
     * Returns the currently loaded configuration for the given name.
     *
     * @param name the config name
     * @return the configuration, or a default empty config if not loaded
     */
    public ConfigurationFile get(String name) {
        return current(name).orElseGet(() -> {
            LOG.log(Level.WARNING, "Configuration not loaded: {0}", name);
            return new ConfigurationFile(name, resolve(name), "yml", ConfigurationNode.map(), 0);
        });
    }

    /**
     * Returns all known configuration names.
     */
    public List<String> knownConfigs() {
        return List.copyOf(knownConfigs);
    }

    /**
     * Returns the storage manager used by this config manager.
     */
    public StorageManager storageManager() {
        return storageManager;
    }
}



