package org.veltismc.veltis.runtime.event;

import org.veltismc.veltis.server.event.Event;
import org.veltismc.veltis.server.model.player.InternalPlayer;

public record PlayerQuitBridgeEvent(
    long timestamp,
    InternalPlayer player,
    String quitMessage,
    String reason
) implements Event {
}


