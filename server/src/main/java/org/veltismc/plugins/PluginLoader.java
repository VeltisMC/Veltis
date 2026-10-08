package org.veltismc.plugins;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veltismc.api.plugin.PluginLifecycle;
import org.veltismc.api.plugin.PluginMeta;
import org.veltismc.api.Server;

public class PluginLoader {
    private final Map<String, Object> activePlugins = new ConcurrentHashMap<>();
    private final Map<String, PluginLifecycle> lifecycleRegistry = new ConcurrentHashMap<>();

    private final File pluginsDir;
    private final Server apiServerInstance;
    private final Logger log;

    public PluginLoader(Path homeDir, Server apiServerInstance, Logger log) {
        this.pluginsDir = homeDir.resolve("plugins").toFile();
        this.apiServerInstance = apiServerInstance;
        this.log = log;
    }

    public void loadAllPlugins() {
        if (!pluginsDir.exists())
            pluginsDir.mkdirs();

        File[] files = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0)
            return;

        List<URL> jarUrls = new ArrayList<>();
        for (File file : files) {
            try {
                jarUrls.add(file.toURI().toURL());
            } catch (Exception ignored) {
            }
        }

        URLClassLoader pluginClassLoader = new URLClassLoader(
                jarUrls.toArray(new URL[0]),
                this.getClass().getClassLoader());

        ExecutorService loaderExecutor = Executors.newFixedThreadPool(2);
        log.info("[VeltisMC] Scanning and initializing plugins in parallel...");
        long startTime = System.currentTimeMillis();

        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(pluginClassLoader)
                .enableAnnotationInfo()
                .scan()) {

            List<Class<?>> annotatedClasses = scanResult
                    .getClassesWithAnnotation(PluginMeta.class.getName())
                    .loadClasses();

            List<CompletableFuture<Void>> tasks = new ArrayList<>();

            for (Class<?> clazz : annotatedClasses) {
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        PluginMeta meta = clazz.getAnnotation(PluginMeta.class);
                        String lookupKey = meta.name().trim().toLowerCase();

                        if (activePlugins.containsKey(lookupKey)) {
                            log.error("[VeltisMC] CRITICAL: Duplicate plugin name '{}'. Skipping execution.",
                                    meta.name());
                            return;
                        }

                        PluginLifecycle specificPluginLifecycle = new PluginLifecycle();
                        Logger pluginLogger = LoggerFactory.getLogger(meta.name());

                        Constructor<?>[] constructors = clazz.getConstructors();
                        if (constructors.length == 0)
                            return;

                        Constructor<?> constructor = constructors[0];
                        Parameter[] parameters = constructor.getParameters();
                        Object[] initArgs = new Object[parameters.length];

                        for (int i = 0; i < parameters.length; i++) {
                            Class<?> paramType = parameters[i].getType();
                            if (paramType == Server.class)
                                initArgs[i] = apiServerInstance;
                            else if (paramType == Logger.class)
                                initArgs[i] = pluginLogger;
                            else if (paramType == PluginLifecycle.class)
                                initArgs[i] = specificPluginLifecycle;
                            else
                                throw new IllegalArgumentException(
                                        "Unsupported API dependency: " + paramType.getSimpleName() +
                                                "\nOnly supported is Server, Logger and PluginLifecycle");
                        }

                        Object pluginInstance = constructor.newInstance(initArgs);

                        activePlugins.put(lookupKey, pluginInstance);
                        lifecycleRegistry.put(lookupKey, specificPluginLifecycle);

                        log.info("[VeltisMC] Natively booted plugin: {} v{} by {}", meta.name(), meta.version(),
                                String.join(", ", meta.authors()));

                    } catch (Exception e) {
                        log.error("[VeltisMC] Failed loading plugin class: {}", clazz.getName(), e);
                    }
                }, loaderExecutor);

                tasks.add(task);
            }

            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();

        } finally {
            loaderExecutor.shutdown();
        }

        long endTime = System.currentTimeMillis();
        log.info("Successfully loaded {} plugins in {}ms!", activePlugins.size(), (endTime - startTime));
    }

    public void unloadAllPlugins() {
        long startTime = System.currentTimeMillis();

        if (lifecycleRegistry.isEmpty()) {
            activePlugins.clear();
            return;
        }

        ExecutorService unloaderExecutor = Executors.newFixedThreadPool(2);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        for (var entry : lifecycleRegistry.entrySet()) {
            String pluginName = entry.getKey();
            PluginLifecycle lifecycle = entry.getValue();

            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    lifecycle.executeShutdown();
                    log.info("Plugin successfully detached: {}", pluginName);
                } catch (Exception e) {
                    log.error("Error while running shutdown loop for plugin: {}", pluginName, e);
                }
            }, unloaderExecutor);

            tasks.add(task);
        }

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            unloaderExecutor.shutdown();
        }

        lifecycleRegistry.clear();
        activePlugins.clear();

        long endTime = System.currentTimeMillis();
        log.info("All plugins successfully unloaded in {}ms!", (endTime - startTime));
    }
}