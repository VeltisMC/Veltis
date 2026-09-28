package org.veltismc.runtime.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class DefaultServiceRegistry implements ServiceRegistry {

    private static final Logger LOG = LogManager.getLogger(DefaultServiceRegistry.class);

    private final ConcurrentMap<Class<? extends Service>, ServiceProvider<?>> services;

    public DefaultServiceRegistry() {
        this.services = new ConcurrentHashMap<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Service> T register(T service) {
        var type = (Class<T>) service.type();
        var existing = services.putIfAbsent(type, ServiceProvider.of(service));
        if (existing != null) {
            throw new IllegalStateException(
                "Service already registered: " + type.getName());
        }
        LOG.debug("Registered service: {}", service.name());
        return service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Service> Optional<T> unregister(Class<T> type) {
        var removed = services.remove(type);
        if (removed != null) {
            var service = (T) removed.instance();
            if (service.state() == Service.State.STARTED) {
                try {
                    service.shutdown();
                } catch (Exception e) {
                    LOG.warn("Error shutting down service {}", service.name(), e);
                }
            }
            LOG.debug("Unregistered service: {}", service.name());
            return Optional.of(service);
        }
        return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Service> Optional<T> get(Class<T> type) {
        return Optional.ofNullable(services.get(type))
            .map(provider -> (T) provider.instance());
    }

    @Override
    public boolean contains(Class<? extends Service> type) {
        return services.containsKey(type);
    }

    @Override
    public Collection<ServiceDescriptor> descriptors() {
        return services.values().stream()
            .map(ServiceProvider::descriptor)
            .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<ServiceProvider<?>> providers() {
        return List.copyOf(services.values());
    }

    @Override
    public int count() {
        return services.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Service> void initialize(Class<T> type) {
        get(type).ifPresent(service -> {
            if (service.state() == Service.State.CREATED) {
                try {
                    service.initialize();
                    LOG.debug("Initialized service: {}", service.name());
                } catch (Exception e) {
                    LOG.error("Failed to initialize service {}", service.name(), e);
                    throw new RuntimeException(
                        "Service initialization failed: " + service.name(), e);
                }
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Service> void shutdown(Class<T> type) {
        get(type).ifPresent(service -> {
            if (service.state() == Service.State.STARTED) {
                try {
                    service.shutdown();
                    LOG.debug("Shutdown service: {}", service.name());
                } catch (Exception e) {
                    LOG.error("Error shutting down service {}", service.name(), e);
                }
            }
        });
    }

    @Override
    public void initializeAll() {
        services.values().stream()
            .map(ServiceProvider::instance)
            .filter(s -> s.state() == Service.State.CREATED)
            .forEach(s -> initialize(s.type()));
    }

    @Override
    public void shutdownAll() {
        services.values().stream()
            .map(ServiceProvider::instance)
            .filter(s -> s.state() == Service.State.STARTED)
            .forEach(s -> shutdown(s.type()));
    }
}


