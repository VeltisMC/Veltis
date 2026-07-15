package org.veltismc.veltis.resource;

import org.veltismc.veltis.server.event.Event;

/**
 * Fired when a resource is loaded from disk or classpath.
 *
 * @param container the loaded resource container
 */
public record ResourceLoadedEvent(ResourceContainer container) implements Event {
}


