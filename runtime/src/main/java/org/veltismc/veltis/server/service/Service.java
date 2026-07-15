package org.veltismc.veltis.server.service;

/**
 * Core service interface for the VeltisMC service system.
 *
 * <p>A service is a managed component with a defined lifecycle.
 * Implementations provide {@link #initialize()} and {@link #shutdown()}
 * hooks called by the {@link ServiceRegistry} when transitioning between states.
 *
 * <p>All services are identified by their {@link #type()} class and a
 * human-readable {@link #name()}.
 */
public interface Service {

    /**
     * Human-readable service name for logging and diagnostics.
     */
    String name();

    /**
     * Returns the service's type class, used as the registry key.
     */
    Class<? extends Service> type();

    /**
     * Current lifecycle state of this service.
     */
    State state();

    /**
     * Initializes this service. Called once by the registry after
     * registration when {@code initialize(type)} is invoked.
     */
    default void initialize() {
    }

    /**
     * Shuts down this service. Called once by the registry during
     * shutdown or when the service is unregistered.
     */
    default void shutdown() {
    }

    /**
     * Lifecycle states for a {@link Service}.
     */
    enum State {
        CREATED,
        INITIALIZED,
        STARTED,
        STOPPED,
        FAILED
    }
}



