package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.server.event.Event;

public record RuntimePortBoundEvent(long timestamp, int port, boolean listening) implements Event {
}


