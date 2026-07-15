package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.runtime.RuntimeContext;
import org.veltismc.veltis.server.event.Event;

/**
 * Fired when the Minecraft runtime reaches the RUNNING state.
 *
 * @param startedAt timestamp when the runtime started (millis)
 * @param context   the runtime context at startup
 */
public record RuntimeStartedEvent(long startedAt, RuntimeContext context) implements Event {
}


