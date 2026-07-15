package org.veltismc.veltis.plugin;

import io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;

import java.util.ArrayList;
import java.util.List;

public class VeltisClassLoaderStorage implements PaperClassLoaderStorage {

    private static final PluginClassLoaderGroup GLOBAL = new PluginClassLoaderGroup() {
        private final List<ConfiguredPluginClassLoader> loaders = new ArrayList<>();
        @Override public Class<?> getClassByName(String name, boolean resolve, ConfiguredPluginClassLoader requester) {
            for (var loader : loaders) {
                try { return loader.loadClass(name, resolve, false, false); } catch (ClassNotFoundException ignored) {}
            }
            return null;
        }
        @Override public void remove(ConfiguredPluginClassLoader loader) { loaders.remove(loader); }
        @Override public void add(ConfiguredPluginClassLoader loader) { loaders.add(loader); }
        @Override public ClassLoaderAccess getAccess() { return loader -> true; }
    };

    @Override
    public PluginClassLoaderGroup registerSpigotGroup(org.bukkit.plugin.java.PluginClassLoader pluginClassLoader) {
        GLOBAL.add(pluginClassLoader);
        return GLOBAL;
    }

    @Override
    public PluginClassLoaderGroup registerOpenGroup(ConfiguredPluginClassLoader classLoader) {
        GLOBAL.add(classLoader);
        return GLOBAL;
    }

    @Override
    public PluginClassLoaderGroup registerAccessBackedGroup(ConfiguredPluginClassLoader classLoader, ClassLoaderAccess access) {
        GLOBAL.add(classLoader);
        return new PluginClassLoaderGroup() {
            @Override public Class<?> getClassByName(String name, boolean resolve, ConfiguredPluginClassLoader requester) {
                if (!access.canAccess(requester)) return null;
                try { return classLoader.loadClass(name, resolve, false, false); } catch (ClassNotFoundException e) { return null; }
            }
            @Override public void remove(ConfiguredPluginClassLoader loader) { GLOBAL.remove(loader); }
            @Override public void add(ConfiguredPluginClassLoader loader) { GLOBAL.add(loader); }
            @Override public ClassLoaderAccess getAccess() { return access; }
        };
    }

    @Override
    public void unregisterClassloader(ConfiguredPluginClassLoader configuredPluginClassLoader) {
        GLOBAL.remove(configuredPluginClassLoader);
    }

    @Override
    public boolean registerUnsafePlugin(ConfiguredPluginClassLoader pluginLoader) {
        GLOBAL.add(pluginLoader);
        return true;
    }
}
