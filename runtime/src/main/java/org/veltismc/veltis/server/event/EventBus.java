package org.veltismc.veltis.server.event;

/**
 * Type-safe event bus for dispatching events to registered listeners.
 *
 * <p>Listeners are registered per event type class via
 * {@link #subscribe(Class, EventListener)} and dispatched via
 * {@link #publish(Event)}. The bus is fully type-safe without
 * reflection — event types are mapped at registration time using
 * {@link Class} keys.
 *
 * <p>All operations are thread-safe. Listeners are invoked on
 * the publisher's thread.
 */
public interface EventBus {

    /**
     * Subscribes a listener to a specific event type.
     *
     * @param eventType the event class to subscribe to
     * @param listener  the listener to invoke when the event is published
     * @param <E>       the event type
     * @return a registration handle that can be used to unsubscribe
     */
    <E extends Event> ListenerRegistration subscribe(Class<E> eventType, EventListener<E> listener);

    /**
     * Publishes an event to all registered listeners of its type.
     * Exceptions thrown by listeners are caught and logged.
     *
     * @param event the event to publish
     * @param <E>   the event type
     */
    <E extends Event> void publish(E event);

    /**
     * Publishes an event to all registered listeners of its type,
     * allowing exceptions to propagate to the caller.
     *
     * @param event the event to publish
     * @param <E>   the event type
     * @throws RuntimeException if any listener throws
     */
    <E extends Event> void publishOrThrow(E event);

    /**
     * Returns the number of registered listeners across all event types.
     */
    int listenerCount();

    /**
     * Returns the number of registered listeners for a specific event type.
     */
    int listenerCount(Class<? extends Event> eventType);

    /**
     * Removes all listeners for all event types.
     */
    void clear();

    /**
     * Returns true if any listener is registered for the given event type.
     */
    boolean hasListeners(Class<? extends Event> eventType);
}


