package org.veltismc.veltis.command.execution;

import org.veltismc.veltis.command.Command;
import org.veltismc.veltis.command.CommandRegistry;
import org.veltismc.veltis.server.event.Event;

/**
 * Fired when a new command is registered with the {@link CommandRegistry}.
 *
 * @param command  the command that was registered
 * @param registry the registry it was registered with
 */
public record CommandRegisteredEvent(Command command, CommandRegistry registry) implements Event {
}


