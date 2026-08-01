package org.veltismc.veltis.plugin.classloader;

import io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;

@ApiStatus.Internal
public class DependencyBasedPluginClassLoaderGroup extends SimpleListPluginClassLoaderGroup {

    private final GlobalPluginClassLoaderGroup globalPluginClassLoaderGroup;
    private final ClassLoaderAccess access;

    public DependencyBasedPluginClassLoaderGroup(GlobalPluginClassLoaderGroup globalPluginClassLoaderGroup, ClassLoaderAccess access) {
        super(new ArrayList<>());
        this.access = access;
        this.globalPluginClassLoaderGroup = globalPluginClassLoaderGroup;
    }

    public void populateDependencies() {
        this.classloaders.clear();
        for (ConfiguredPluginClassLoader configuredPluginClassLoader : this.globalPluginClassLoaderGroup.getClassLoaders()) {
            if (this.access.canAccess(configuredPluginClassLoader)) {
                this.classloaders.add(configuredPluginClassLoader);
            }
        }
    }

    @Override
    public ClassLoaderAccess getAccess() {
        return this.access;
    }

    @Override
    public String toString() {
        return "DependencyBasedPluginClassLoaderGroup{" +
            "globalPluginClassLoaderGroup=" + this.globalPluginClassLoaderGroup +
            ", access=" + this.access +
            ", classloaders=" + this.classloaders +
            '}';
    }
}
