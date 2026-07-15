package org.veltismc.veltis.server.event;

/**
 * Handle returned by {@link EventBus#subscribe(Class, EventListener)}
 * that can be used to unsubscribe the listener.
 */
public interface ListenerRegistration {

    /**
     * Removes the associated listener from the event bus.
     *
     * @return true if the listener was found and removed
     */
    boolean unsubscribe();

    /**
     * Returns true if the listener is still registered and active.
     */
    boolean isActive();
}


