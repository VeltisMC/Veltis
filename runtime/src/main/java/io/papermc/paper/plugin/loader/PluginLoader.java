package io.papermc.paper.plugin.loader;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

@ApiStatus.Experimental
@NullMarked
@ApiStatus.OverrideOnly
public interface PluginLoader {

    void classloader(PluginClasspathBuilder classpathBuilder);

}
