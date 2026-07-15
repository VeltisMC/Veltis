package org.veltismc.veltis.config;

import org.veltismc.veltis.server.event.Event;

/**
 * Fired when a configuration file is hot-reloaded from disk.
 *
 * @param file the reloaded configuration file
 */
public record ConfigurationReloadedEvent(ConfigurationFile file) implements Event {
}


