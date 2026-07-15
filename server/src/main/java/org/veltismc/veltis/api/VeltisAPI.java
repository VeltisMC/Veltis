package org.veltismc.veltis.api;

import org.veltismc.veltis.api.definition.ServerDefinition;

import java.util.Optional;
import java.util.ServiceLoader;

public final class VeltisAPI {

    private static volatile ServerDefinition server;

    private VeltisAPI() {
    }

    public static Optional<ServerDefinition> server() {
        return Optional.ofNullable(server);
    }

    public static void bind(ServerDefinition instance) {
        if (server != null) {
            throw new IllegalStateException("Server instance already bound");
        }
        server = instance;
    }

    public static void unbind() {
        server = null;
    }

    public static <T> T loadService(Class<T> serviceType) {
        return ServiceLoader.load(serviceType)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No service found for " + serviceType.getName()));
    }
}



