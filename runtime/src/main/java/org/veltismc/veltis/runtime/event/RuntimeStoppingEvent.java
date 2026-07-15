package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.runtime.RuntimeState;
import org.veltismc.veltis.server.event.Event;

/**
 * Fired when the Minecraft runtime is beginning to stop.
 *
 * @param stoppedAt    timestamp when the stop was initiated (millis)
 * @param previousState the state the runtime was in before stopping
 */
public record RuntimeStoppingEvent(long stoppedAt, RuntimeState previousState) implements Event {
}


