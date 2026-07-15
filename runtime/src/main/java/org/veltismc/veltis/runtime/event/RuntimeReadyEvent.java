package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.runtime.RuntimeContext;
import org.veltismc.veltis.server.event.Event;

public record RuntimeReadyEvent(long timestamp, RuntimeContext context) implements Event {
}


