package org.veltismc.runtime.event;

/**
 * Base marker interface for all VeltisMC events.
 *
 * <p>Events are dispatched through the {@link EventBus} to registered
 * {@link EventListener} instances. Implementations should be immutable
 * POJOs (classes or records) carrying event-specific data.
 */
public interface Event {
}



