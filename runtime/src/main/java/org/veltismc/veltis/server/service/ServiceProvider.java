package org.veltismc.veltis.server.service;

/**
 * Typed wrapper that associates a {@link Service} with its {@link ServiceDescriptor}.
 *
 * <p>Used internally by the {@link ServiceRegistry} to store services
 * alongside their metadata without losing type information.
 *
 * @param <T> the service type
 */
public interface ServiceProvider<T extends Service> {

    /**
     * Returns the service type class.
     */
    Class<T> type();

    /**
     * Returns the service instance.
     */
    T instance();

    /**
     * Returns the current service descriptor.
     */
    ServiceDescriptor descriptor();

    /**
     * Creates a new provider wrapping the given service.
     *
     * @param service the service instance
     * @param <T>     the service type
     * @return a new service provider
     */
    @SuppressWarnings("unchecked")
    static <T extends Service> ServiceProvider<T> of(T service) {
        var serviceType = (Class<T>) service.type();
        return new ServiceProvider<>() {
            @Override
            public Class<T> type() {
                return serviceType;
            }

            @Override
            public T instance() {
                return service;
            }

            @Override
            public ServiceDescriptor descriptor() {
                return ServiceDescriptor.create(service);
            }
        };
    }
}


