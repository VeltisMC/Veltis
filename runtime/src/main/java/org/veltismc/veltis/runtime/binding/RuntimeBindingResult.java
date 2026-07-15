package org.veltismc.veltis.runtime.binding;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

public record RuntimeBindingResult(
    boolean bound,
    Path serverJar,
    URLClassLoader classLoader,
    String serverVersion,
    List<String> loadedClasses,
    String mode,
    List<String> errors
) {

    public static RuntimeBindingResult unbound(String reason) {
        return new RuntimeBindingResult(false, null, null, null, List.of(), "SIMULATION", List.of(reason));
    }

    public static RuntimeBindingResult simulation(String reason) {
        return new RuntimeBindingResult(false, null, null, null, List.of(), "SIMULATION", List.of(reason));
    }

    public static RuntimeBindingResult bound(Path serverJar, URLClassLoader classLoader,
                                              String version, List<String> loadedClasses) {
        return new RuntimeBindingResult(true, serverJar, classLoader, version,
            loadedClasses, "REAL", List.of());
    }

    public boolean isSimulation() {
        return !bound;
    }

    public boolean isReal() {
        return bound;
    }
}


