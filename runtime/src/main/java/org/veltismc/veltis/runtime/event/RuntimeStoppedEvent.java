package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.server.event.Event;

/**
 * Fired when the Minecraft runtime has fully stopped.
 *
 * @param uptimeMs   total runtime uptime in milliseconds
 * @param totalTicks total ticks executed during the session
 */
public record RuntimeStoppedEvent(long uptimeMs, long totalTicks) implements Event {
}


