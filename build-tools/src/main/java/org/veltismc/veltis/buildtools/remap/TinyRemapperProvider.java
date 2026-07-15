package org.veltismc.veltis.buildtools.remap;

import org.veltismc.veltis.buildtools.context.BuildContext;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

public final class TinyRemapperProvider implements RemapperIntegration {

    @Override
    public String remapperName() {
        return "TinyRemapper";
    }

    @Override
    public String remapperVersion() {
        return "0.10.3";
    }

    @Override
    public RemapResult remap(BuildContext context, RemapSpec spec) throws Exception {
        var startTime = System.currentTimeMillis();
        var inputJar = spec.inputJar();
        var outputJar = spec.outputJar();
        try {
            var classpath = spec.classpath().stream()
                .map(Path::of)
                .toArray(Path[]::new);
            var mappingPath = Path.of(spec.mappingsFile());

            // Filter out package-info and module-info classes that TinyRemapper can't parse
            var filteredJar = inputJar.resolveSibling(inputJar.getFileName() + ".filtered.tmp");
            createFilteredJar(inputJar, filteredJar);

            var remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(
                    mappingPath, "official", "intermediary"))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .ignoreConflicts(true)
                .threads(Runtime.getRuntime().availableProcessors())
                .build();

            try {
                remapper.readInputsAsync(filteredJar);
                remapper.readClassPathAsync(classpath);

                var outputEntries = new HashMap<String, byte[]>();
                remapper.apply(outputEntries::put);

                writeJar(outputJar, inputJar, outputEntries);

                var elapsed = System.currentTimeMillis() - startTime;
                return RemapResult.success(
                    outputJar, outputEntries.size(), 0, 0, elapsed);
            } finally {
                remapper.finish();
                Files.deleteIfExists(filteredJar);
            }
        } catch (Exception e) {
            return RemapResult.failure(Objects.toString(e.getMessage(), "Unknown remap error"));
        }
    }

    private static void createFilteredJar(Path inputJar, Path outputJar) throws IOException {
        Files.deleteIfExists(outputJar);
        try (var jis = new JarInputStream(Files.newInputStream(inputJar));
             var jos = new JarOutputStream(Files.newOutputStream(outputJar))) {
            byte[] buffer = new byte[8192];
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                var name = entry.getName();
                if (name.endsWith("package-info.class") || name.endsWith("module-info.class")) {
                    continue;
                }
                jos.putNextEntry(new JarEntry(name));
                int len;
                while ((len = jis.read(buffer)) > 0) {
                    jos.write(buffer, 0, len);
                }
                jos.closeEntry();
            }
        }
    }

    private static void writeJar(
        Path outputJar,
        Path inputJar,
        Map<String, byte[]> remappedClasses
    ) throws IOException {
        Files.deleteIfExists(outputJar);
        var env = Map.of("create", "true");
        var uri = URI.create("jar:" + outputJar.toUri());
        try (var fs = FileSystems.newFileSystem(uri, env)) {
            copyNonClassFiles(inputJar, fs);
            writeRemappedClasses(fs, remappedClasses);
        }
    }

    private static void copyNonClassFiles(Path inputJar, FileSystem outputFs) throws IOException {
        try (var inputFs = FileSystems.newFileSystem(inputJar, (ClassLoader) null)) {
            for (var root : inputFs.getRootDirectories()) {
                try (var walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().endsWith(".class"))
                        .forEach(source -> {
                            try {
                                var relative = inputFs.getPath("/").relativize(source);
                                var target = outputFs.getPath("/", relative.toString());
                                Files.createDirectories(target.getParent());
                                Files.copy(source, target);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                }
            }
        }
    }

    private static void writeRemappedClasses(
        FileSystem outputFs,
        Map<String, byte[]> classes
    ) throws IOException {
        for (var entry : classes.entrySet()) {
            var classPath = entry.getKey().replace('.', '/') + ".class";
            var target = outputFs.getPath("/", classPath.split("/"));
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }
}


