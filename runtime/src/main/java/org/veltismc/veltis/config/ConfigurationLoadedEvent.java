package org.veltismc.veltis.config;

import org.veltismc.veltis.server.event.Event;

/**
 * Fired when a configuration file is loaded from disk.
 *
 * @param file the loaded configuration file
 */
public record ConfigurationLoadedEvent(ConfigurationFile file) implements Event {
}


