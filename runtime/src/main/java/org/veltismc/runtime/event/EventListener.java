package org.veltismc.runtime.event;

/**
 * Functional interface for handling events dispatched by the {@link EventBus}.
 *
 * @param <E> the event type this listener accepts
 */
@FunctionalInterface
public interface EventListener<E extends Event> {

    /**
     * Called when an event of the subscribed type is published.
     *
     * @param event the event instance
     */
    void onEvent(E event);
}


