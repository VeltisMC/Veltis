package org.veltismc.veltis.runtime.loader;

import org.veltismc.veltis.runtime.bootstrap.RuntimeBootstrapResolver;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MinecraftServerLoader {

    private final URLClassLoader classLoader;
    private final Path serverJar;
    private final MinecraftServerClasspathBuilder classpathBuilder;

    public MinecraftServerLoader(URLClassLoader classLoader, Path serverJar,
                                  MinecraftServerClasspathBuilder classpathBuilder) {
        this.classLoader = classLoader;
        this.serverJar = serverJar;
        this.classpathBuilder = classpathBuilder;
    }

    public MinecraftServerLoadResult load() {
        var resolver = new RuntimeBootstrapResolver();
        var result = resolver.resolve(serverJar, classLoader, classpathBuilder);

        var loaded = new ArrayList<Class<?>>();
        var errors = new ArrayList<String>();

        if (result.mainClass() != null) loaded.add(result.mainClass());
        else errors.add("Main class not resolved");

        if (result.minecraftServerClass() != null) loaded.add(result.minecraftServerClass());
        else errors.add("MinecraftServer class not resolved");

        if (result.dedicatedServerClass() != null) loaded.add(result.dedicatedServerClass());
        else errors.add("DedicatedServer class not resolved");

        var success = result.success();
        return new MinecraftServerLoadResult(success, List.copyOf(loaded), List.copyOf(errors), classLoader);
    }
}


