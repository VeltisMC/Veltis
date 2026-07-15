package io.papermc.paper.plugin.loader;

import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NullMarked;

@ApiStatus.Experimental
@NullMarked
@ApiStatus.NonExtendable
public interface PluginClasspathBuilder {

    @Contract("_ -> this")
    PluginClasspathBuilder addLibrary(ClassPathLibrary classPathLibrary);

}
