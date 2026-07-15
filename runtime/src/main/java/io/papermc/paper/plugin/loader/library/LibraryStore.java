package io.papermc.paper.plugin.loader.library;

import java.nio.file.Path;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

@ApiStatus.Internal
@NullMarked
public interface LibraryStore {

    void addLibrary(Path library);

}
