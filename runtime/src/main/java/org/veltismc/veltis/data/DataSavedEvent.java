package org.veltismc.veltis.data;

import org.veltismc.veltis.server.event.Event;

import java.nio.file.Path;

/**
 * Fired when data is saved to persistent storage.
 *
 * @param key  the data key
 * @param path the file path on disk
 */
public record DataSavedEvent(String key, Path path) implements Event {
}


