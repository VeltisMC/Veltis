package org.veltismc.veltis.runtime.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

public final class RuntimeClassIndex {

    private final List<String> classNames;
    private final ClassLoader classLoader;

    public RuntimeClassIndex(Path jarPath, ClassLoader classLoader) {
        this.classNames = scanClassNames(jarPath);
        this.classLoader = classLoader;
    }

    public Optional<Class<?>> findClassBySimpleName(String simpleName) {
        var suffix = "." + simpleName;
        return classNames.stream()
            .filter(n -> n.endsWith(suffix))
            .findFirst()
            .flatMap(this::tryLoad);
    }

    public boolean hasClass(String className) {
        return classNames.contains(className);
    }

    public Optional<Class<?>> tryLoad(String className) {
        try {
            return Optional.of(Class.forName(className, false, classLoader));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<String> allClassNames() {
        return classNames;
    }

    public int classCount() {
        return classNames.size();
    }

    private static List<String> scanClassNames(Path jarPath) {
        var names = new ArrayList<String>();
        try (var zf = new ZipFile(jarPath.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (name.endsWith(".class")) {
                    var fqcn = name.replace('/', '.').replaceAll("\\.class$", "");
                    names.add(fqcn);
                }
            }
        } catch (IOException e) {
            // Return empty list if jar cannot be read
        }
        return names;
    }
}


