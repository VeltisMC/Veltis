package org.veltismc.veltis.plugin.compat;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import org.veltismc.veltis.plugin.compat.VeltisLibraryStore;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class VeltisClasspathBuilder implements PluginClasspathBuilder {

    private final List<ClassPathLibrary> libraries = new ArrayList<>();
    private final PluginProviderContext context;

    public VeltisClasspathBuilder(PluginProviderContext context) {
        this.context = context;
    }

    @Override
    public @NotNull PluginClasspathBuilder addLibrary(@NotNull ClassPathLibrary classPathLibrary) {
        this.libraries.add(classPathLibrary);
        return this;
    }

    @Override
    public PluginProviderContext getContext() {
        return this.context;
    }

    public List<Path> buildLibraryPaths() {
        VeltisLibraryStore veltisLibraryStore = new VeltisLibraryStore();
        for (ClassPathLibrary library : this.libraries) {
            library.register(veltisLibraryStore);
        }
        return veltisLibraryStore.getPaths();
    }
}
