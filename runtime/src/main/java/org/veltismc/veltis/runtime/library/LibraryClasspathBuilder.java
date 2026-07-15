package org.veltismc.veltis.runtime.library;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LibraryClasspathBuilder {

    private final Path librariesDir;

    public LibraryClasspathBuilder(Path librariesDir) {
        this.librariesDir = librariesDir;
    }

    public List<URL> build() {
        var urls = new ArrayList<URL>();
        if (!Files.isDirectory(librariesDir)) return urls;

        try (var files = Files.walk(librariesDir)) {
            files.filter(p -> p.toString().endsWith(".jar"))
                 .filter(Files::isRegularFile)
                 .sorted(Comparator.comparing(Path::toString))
                 .map(this::toURL)
                 .forEach(urls::add);
        } catch (Exception ignored) {
        }

        return urls;
    }

    private URL toURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid path: " + path, e);
        }
    }
}


