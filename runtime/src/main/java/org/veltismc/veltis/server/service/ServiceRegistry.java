package org.veltismc.veltis.server.service;

import java.util.Collection;
import java.util.Optional;

/**
 * Thread-safe registry for managing {@link Service} instances.
 *
 * <p>Services are keyed by their {@link Class} type and stored as
 * {@link ServiceProvider} wrappers. Registration, lookup, lifecycle
 * initialization, and shutdown are all supported.
 *
 * <p>All collections returned are immutable snapshots.
 */
public interface ServiceRegistry {

    /**
     * Registers a service instance.
     *
     * @param service the service instance
     * @param <T>     the service type
     * @return the registered service
     * @throws IllegalStateException if a service of the same type is already registered
     */
    <T extends Service> T register(T service);

    /**
     * Unregisters and returns a service by type. If the service was
     * in STARTED state, it is shut down before removal.
     *
     * @param type the service type class
     * @param <T>  the service type
     * @return the unregistered service, or empty if not found
     */
    <T extends Service> Optional<T> unregister(Class<T> type);

    /**
     * Looks up a service by type.
     *
     * @param type the service type class
     * @param <T>  the service type
     * @return the service, or empty if not registered
     */
    <T extends Service> Optional<T> get(Class<T> type);

    /**
     * Returns true if a service of the given type is registered.
     */
    boolean contains(Class<? extends Service> type);

    /**
     * Immutable snapshot of all registered service descriptors.
     */
    Collection<ServiceDescriptor> descriptors();

    /**
     * Immutable snapshot of all registered service providers.
     */
    Collection<ServiceProvider<?>> providers();

    /**
     * Number of registered services.
     */
    int count();

    /**
     * Initializes a specific service if it is in CREATED state.
     */
    <T extends Service> void initialize(Class<T> type);

    /**
     * Shuts down a specific service if it is in STARTED state.
     */
    <T extends Service> void shutdown(Class<T> type);

    /**
     * Initializes all registered services that are in CREATED state.
     */
    void initializeAll();

    /**
     * Shuts down all registered services that are in STARTED state.
     */
    void shutdownAll();
}


