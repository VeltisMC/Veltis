package io.papermc.paper.plugin.loader;

import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.PaperLibraryStore;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PaperClasspathBuilder implements PluginClasspathBuilder {

    private final List<ClassPathLibrary> libraries = new ArrayList<>();

    @Override
    public @NotNull PluginClasspathBuilder addLibrary(@NotNull ClassPathLibrary classPathLibrary) {
        this.libraries.add(classPathLibrary);
        return this;
    }

    public List<Path> buildLibraryPaths() {
        PaperLibraryStore paperLibraryStore = new PaperLibraryStore();
        for (ClassPathLibrary library : this.libraries) {
            library.register(paperLibraryStore);
        }
        return paperLibraryStore.getPaths();
    }
}
