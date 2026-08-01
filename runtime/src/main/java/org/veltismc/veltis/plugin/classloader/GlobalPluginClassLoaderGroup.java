package org.veltismc.veltis.plugin.classloader;

import io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public class GlobalPluginClassLoaderGroup extends SimpleListPluginClassLoaderGroup {

    @Override
    public ClassLoaderAccess getAccess() {
        return (v) -> true;
    }

    @Override
    public String toString() {
        return "GLOBAL:" + super.toString();
    }
}
