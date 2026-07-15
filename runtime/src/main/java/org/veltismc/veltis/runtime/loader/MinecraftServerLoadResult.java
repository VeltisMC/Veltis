package org.veltismc.veltis.runtime.loader;

import java.net.URLClassLoader;
import java.util.List;

public record MinecraftServerLoadResult(
    boolean success,
    List<Class<?>> loadedClasses,
    List<String> errors,
    URLClassLoader classLoader
) {

    public boolean hasClass(String name) {
        return loadedClasses.stream().anyMatch(c -> c.getName().equals(name));
    }

    public boolean hasMinecraftServer() {
        return hasClass("net.minecraft.server.MinecraftServer");
    }

    public boolean hasDedicatedServer() {
        return hasClass("net.minecraft.server.dedicated.DedicatedServer");
    }

    public boolean hasMain() {
        return hasClass("net.minecraft.server.Main");
    }
}


