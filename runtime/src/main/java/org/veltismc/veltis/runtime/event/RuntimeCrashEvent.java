package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.runtime.RuntimeState;
import org.veltismc.veltis.server.event.Event;

/**
 * Fired when the Minecraft runtime crashes unexpectedly.
 *
 * @param timestamp    time of the crash (millis)
 * @param cause        the exception or error that caused the crash
 * @param previousState the state before the crash
 */
public record RuntimeCrashEvent(long timestamp, Throwable cause, RuntimeState previousState) implements Event {
}


