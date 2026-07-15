package org.veltismc.veltis.runtime.loader;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MinecraftServerClasspathBuilder {

    private final Path serverJar;

    public MinecraftServerClasspathBuilder(Path serverJar) {
        this.serverJar = serverJar;
    }

    public URLClassLoader build() {
        var urls = new ArrayList<URL>();

        if (isBundlerFormat()) {
            var extractionDir = extractBundler();
            var extractedServerJar = findExtractedServerJar(extractionDir);
            urls.add(toURL(extractedServerJar));
            urls.addAll(findExtractedLibraries(extractionDir));
        } else {
            urls.add(toURL(serverJar));
            urls.addAll(findExternalLibraries());
        }

        urls.addAll(findProvisionedLibraries());

        return new URLClassLoader(
            urls.toArray(URL[]::new),
            ClassLoader.getPlatformClassLoader()
        );
    }

    public Path resolvedJarPath() {
        if (isBundlerFormat()) {
            return findExtractedServerJar(extractBundler());
        }
        return serverJar;
    }

    private boolean isBundlerFormat() {
        try (var zf = new ZipFile(serverJar.toFile())) {
            return zf.getEntry("META-INF/classpath-joined") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private Path extractBundler() {
        var extractDir = serverJar.getParent().resolve(".extracted");
        try {
            if (Files.exists(extractDir)) {
                return extractDir;
            }
        } catch (Exception e) {
            // fall through to re-extract
        }

        try (var zf = new ZipFile(serverJar.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                var name = entry.getName();
                if (name.startsWith("META-INF/versions/") && name.endsWith(".jar")) {
                    var target = extractDir.resolve(name.substring("META-INF/".length()));
                    extractEntry(zf, entry, target);
                } else if (name.startsWith("META-INF/libraries/") && name.endsWith(".jar")) {
                    var target = extractDir.resolve(name.substring("META-INF/".length()));
                    extractEntry(zf, entry, target);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract bundler jar: " + serverJar, e);
        }

        return extractDir;
    }

    private Path findExtractedServerJar(Path extractionDir) {
        var versionsDir = extractionDir.resolve("versions");
        if (Files.isDirectory(versionsDir)) {
            try (var files = Files.walk(versionsDir)) {
                var found = files.filter(p -> p.toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .findFirst();
                if (found.isPresent()) return found.get();
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan extracted versions directory", e);
            }
        }
        throw new RuntimeException("No server jar found in extracted bundler");
    }

    private List<URL> findExtractedLibraries(Path extractionDir) {
        var urls = new ArrayList<URL>();
        var libDir = extractionDir.resolve("libraries");
        if (Files.isDirectory(libDir)) {
            try (var files = Files.walk(libDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                     .filter(Files::isRegularFile)
                     .sorted()
                     .map(this::toURL)
                     .forEach(urls::add);
            } catch (Exception ignored) {
            }
        }
        return urls;
    }

    private List<URL> findProvisionedLibraries() {
        var urls = new ArrayList<URL>();
        var provisionedDir = serverJar.getParent().resolve("libraries");
        if (Files.isDirectory(provisionedDir)) {
            try (var files = Files.walk(provisionedDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                     .filter(Files::isRegularFile)
                     .sorted()
                     .map(this::toURL)
                     .forEach(urls::add);
            } catch (Exception ignored) {
            }
        }
        return urls;
    }

    private List<URL> findExternalLibraries() {
        var urls = new ArrayList<URL>();
        var librariesDir = locateLibrariesDirectory();
        if (librariesDir != null) {
            try (var files = Files.walk(librariesDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                     .filter(Files::isRegularFile)
                     .map(this::toURL)
                     .forEach(urls::add);
            } catch (Exception ignored) {
            }
        }
        return urls;
    }

    private Path locateLibrariesDirectory() {
        var candidates = List.of(
            serverJar.getParent().resolve("libraries"),
            serverJar.getParent().resolve("libs"),
            serverJar.getParent().getParent().resolve("libraries"),
            serverJar.getParent().getParent().resolve("libs")
        );
        for (var candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void extractEntry(ZipFile zf, ZipEntry entry, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try (var in = zf.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract " + entry.getName(), e);
        }
    }

    private URL toURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid path: " + path, e);
        }
    }
}


