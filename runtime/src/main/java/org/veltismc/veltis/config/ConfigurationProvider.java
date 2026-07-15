package org.veltismc.veltis.config;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Provides access to configuration files by name.
 *
 * <p>Implementations are responsible for locating, loading, and
 * optionally watching configuration files on disk.
 */
public interface ConfigurationProvider {

    /**
     * Loads a configuration by name.
     *
     * @param name the configuration name (e.g. "server", "runtime")
     * @return the loaded configuration file
     * @throws java.io.IOException if loading fails
     */
    ConfigurationFile load(String name) throws java.io.IOException;

    /**
     * Saves a configuration to disk.
     */
    void save(ConfigurationFile file) throws java.io.IOException;

    /**
     * Reloads a configuration from disk, returning a new immutable snapshot.
     */
    ConfigurationFile reload(String name) throws java.io.IOException;

    /**
     * Returns the path to a configuration by name.
     */
    Path resolve(String name);

    /**
     * Returns the currently loaded configuration, if any.
     */
    Optional<ConfigurationFile> current(String name);

    /**
     * Returns true if a configuration with the given name exists on disk.
     */
    boolean exists(String name);
}


