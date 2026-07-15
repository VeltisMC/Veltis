package io.papermc.paper.plugin.loader.library;

import org.jspecify.annotations.NullMarked;

@NullMarked
public interface ClassPathLibrary {

    void register(LibraryStore store) throws LibraryLoadingException;

}
