package org.veltismc.veltis.runtime.bootstrap;

import org.veltismc.veltis.runtime.loader.MinecraftServerClasspathBuilder;

import java.io.PrintStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RuntimeBootstrapResolver {

    private static final Logger LOG = System.getLogger(RuntimeBootstrapResolver.class.getName());

    private final RuntimeEntrypointResolver entrypointResolver;

    public RuntimeBootstrapResolver() {
        this.entrypointResolver = new RuntimeEntrypointResolver();
    }

    public RuntimeBootstrapResolver(RuntimeEntrypointResolver entrypointResolver) {
        this.entrypointResolver = Objects.requireNonNull(entrypointResolver, "entrypointResolver");
    }

    public BootstrapResult resolve(Path serverJar, URLClassLoader classLoader,
                                    MinecraftServerClasspathBuilder classpathBuilder) {
        Objects.requireNonNull(serverJar, "serverJar");
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(classpathBuilder, "classpathBuilder");

        var diagnostics = new ArrayList<String>();
        var failures = new ArrayList<ResolutionFailure>();

        var resolvedJar = classpathBuilder.resolvedJarPath();

        var entrypointName = resolveEntrypoint(serverJar, resolvedJar);
        if (entrypointName.isPresent()) {
            diagnostics.add("[PASS] Main-Class resolved \u2500 " + entrypointName.get());
        } else {
            diagnostics.add("[FAIL] Main-Class not found");
            failures.add(new ResolutionFailure("Main-Class",
                "Not found in META-INF/main-class or MANIFEST.MF"));
        }

        Class<?> mainClass = null;
        if (entrypointName.isPresent()) {
            var result = loadClass("Entrypoint", entrypointName.get(), classLoader);
            mainClass = result.classValue();
            diagnostics.add(result.diagnostic());
            if (mainClass == null) {
                failures.add(new ResolutionFailure("Entrypoint", entrypointName.get()));
            }
        }

        RuntimeClassIndex classIndex = null;
        if (resolvedJar != null) {
            try {
                classIndex = new RuntimeClassIndex(resolvedJar, classLoader);
                diagnostics.add("[PASS] Class index built \u2500 " + classIndex.classCount() + " entries");
            } catch (Exception e) {
                diagnostics.add("[FAIL] Class index failed \u2500 " + e.getMessage());
                failures.add(new ResolutionFailure("ClassIndex", e.getMessage()));
            }
        }

        Class<?> minecraftServer = null;
        if (classIndex != null) {
            var result = findServerClass("MinecraftServer", classIndex);
            minecraftServer = result.classValue();
            diagnostics.add(result.diagnostic());
            if (minecraftServer == null) {
                failures.add(new ResolutionFailure("MinecraftServer", result.diagnostic()));
            }
        }

        Class<?> dedicatedServer = null;
        if (classIndex != null) {
            var result = findServerClass("DedicatedServer", classIndex);
            dedicatedServer = result.classValue();
            diagnostics.add(result.diagnostic());
        }

        var success = mainClass != null && minecraftServer != null;
        return new BootstrapResult(success, mainClass, minecraftServer, dedicatedServer,
            classIndex, List.copyOf(diagnostics), List.copyOf(failures));
    }

    private Optional<String> resolveEntrypoint(Path bundlerJar, Path extractedServerJar) {
        var result = entrypointResolver.resolveEntrypoint(bundlerJar);
        if (result.isPresent()) return result;
        if (extractedServerJar != null) {
            result = entrypointResolver.resolveEntrypoint(extractedServerJar);
        }
        return result;
    }

    private ClassLoadResult loadClass(String label, String className, ClassLoader classLoader) {
        try {
            var clazz = Class.forName(className, false, classLoader);
            return new ClassLoadResult(clazz,
                "[PASS] " + label + " loaded \u2500 " + className);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Failed to load {0}: {1}", className, e.getMessage());
            return new ClassLoadResult(null,
                "[FAIL] " + label + " load failed \u2500 " + e.getMessage());
        }
    }

    private ClassLoadResult findServerClass(String simpleName, RuntimeClassIndex classIndex) {
        var found = classIndex.findClassBySimpleName(simpleName);
        if (found.isPresent()) {
            var clazz = found.get();
            return new ClassLoadResult(clazz,
                "[PASS] " + simpleName + " resolved \u2500 " + clazz.getName());
        }
        return new ClassLoadResult(null,
            "[FAIL] " + simpleName + " not found in class index");
    }

    public record ResolutionFailure(String component, String detail) {
        public String formatted() {
            return "  [FAIL] " + component + ": " + detail;
        }
    }

    public record BootstrapResult(
        boolean success,
        Class<?> mainClass,
        Class<?> minecraftServerClass,
        Class<?> dedicatedServerClass,
        RuntimeClassIndex classIndex,
        List<String> diagnostics,
        List<ResolutionFailure> failures
    ) {
        public void print(PrintStream out) {
            out.println("=== Runtime Bootstrap Resolution ===");
            for (var diag : diagnostics) {
                out.println("  " + diag);
            }
            if (!failures.isEmpty()) {
                out.println("  Failures:");
                for (var f : failures) {
                    out.println("    " + f.formatted());
                }
            }
            out.println("  Result: " + (success ? "PASSED" : "FAILED"));
        }
    }

    private record ClassLoadResult(Class<?> classValue, String diagnostic) {
    }
}


