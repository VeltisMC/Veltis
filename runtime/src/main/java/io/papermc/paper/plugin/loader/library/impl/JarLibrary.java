package io.papermc.paper.plugin.loader.library.impl;

import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.LibraryLoadingException;
import io.papermc.paper.plugin.loader.library.LibraryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class JarLibrary implements ClassPathLibrary {

    private final Path path;

    public JarLibrary(final Path path) {
        this.path = path;
    }

    @Override
    public void register(final LibraryStore store) throws LibraryLoadingException {
        if (Files.notExists(this.path)) {
            throw new LibraryLoadingException("Could not find library at " + this.path);
        }
        store.addLibrary(this.path);
    }
}
