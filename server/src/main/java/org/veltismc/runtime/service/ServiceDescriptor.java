package org.veltismc.runtime.service;

/**
 * Immutable metadata record for a registered {@link Service}.
 *
 * <p>Captures the service type, name, current state, and timestamp
 * information for monitoring and diagnostics.
 *
 * @param type           the service type class
 * @param name           the human-readable service name
 * @param state          the current service state
 * @param registeredAt   nanosecond timestamp of registration
 * @param initializedAt  nanosecond timestamp of initialization (-1 if not initialized)
 * @param startedAt      nanosecond timestamp of start (-1 if not started)
 */
public record ServiceDescriptor(
    Class<? extends Service> type,
    String name,
    Service.State state,
    long registeredAt,
    long initializedAt,
    long startedAt
) {

    /**
     * Creates a descriptor snapshot from a service's current state.
     */
    public static ServiceDescriptor create(Service service) {
        return new ServiceDescriptor(
            service.type(),
            service.name(),
            service.state(),
            System.nanoTime(),
            -1,
            -1
        );
    }

    /**
     * Returns the elapsed nanoseconds since this service was started,
     * or zero if it has not been started.
     */
    public long uptime() {
        if (startedAt < 0) return 0;
        return System.nanoTime() - startedAt;
    }

    /**
     * Returns true if the service is currently in STARTED state.
     */
    public boolean isRunning() {
        return state == Service.State.STARTED;
    }

    /**
     * Returns true if the service is in STOPPED state.
     */
    public boolean isStopped() {
        return state == Service.State.STOPPED;
    }
}


