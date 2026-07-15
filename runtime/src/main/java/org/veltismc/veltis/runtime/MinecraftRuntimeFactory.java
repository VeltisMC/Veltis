package org.veltismc.veltis.runtime;

import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.adapter.ReflectiveMinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.bootstrap.MinecraftBootstrap;
import org.veltismc.veltis.runtime.bootstrap.RuntimeBootstrapResolver;
import org.veltismc.veltis.runtime.library.MinecraftLibraryProvisioner;
import org.veltismc.veltis.runtime.loader.MinecraftServerClasspathBuilder;
import org.veltismc.veltis.runtime.loader.MinecraftServerLocator;
import org.veltismc.veltis.runtime.minecraft.DefaultMinecraftServerController;
import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.server.lifecycle.LifecycleManager;
import org.veltismc.veltis.server.metrics.ServerMetrics;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Objects;

public final class MinecraftRuntimeFactory {

    private static final Logger LOG = System.getLogger(MinecraftRuntimeFactory.class.getName());

    private MinecraftRuntimeFactory() {
    }

    public static MinecraftRuntime createDefault(
        Path serverDirectory,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        Objects.requireNonNull(serverDirectory, "serverDirectory");
        Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(serverMetrics, "serverMetrics");

        var configuration = RuntimeConfiguration.defaults(serverDirectory);

        ReflectiveMinecraftRuntimeAdapter adapter;
        var locator = new MinecraftServerLocator(serverDirectory, configuration.minecraftVersion());
        var jarPath = locator.locate();
        if (jarPath.isPresent()) {
            LOG.log(Level.INFO, "Found Minecraft server jar: {0}", jarPath.get());
            try {
                var libraryProvisioner = new MinecraftLibraryProvisioner(
                    serverDirectory, configuration.minecraftVersion());
                var libResult = libraryProvisioner.provision();
                libResult.print(System.out);

                var classpathBuilder = new MinecraftServerClasspathBuilder(jarPath.get());
                var classLoader = classpathBuilder.build();

                try {
                    Class.forName("joptsimple.ValueConverter", false, classLoader);
                    LOG.log(Level.INFO, "  [PASS] joptsimple.ValueConverter verified on classpath");
                } catch (ClassNotFoundException e) {
                    LOG.log(Level.WARNING, "  [FAIL] joptsimple.ValueConverter not on classpath: {0}",
                        e.getMessage());
                }

                var resolver = new RuntimeBootstrapResolver();
                var result = resolver.resolve(jarPath.get(), classLoader, classpathBuilder);
                result.print(System.out);
                if (result.success()) {
                    var loaded = result.classIndex() != null
                        ? result.classIndex().allClassNames()
                        : java.util.List.<String>of();
                    LOG.log(Level.INFO, "Bootstrap resolved {0} classes", loaded.size());
                    adapter = new ReflectiveMinecraftRuntimeAdapter(
                        classLoader, jarPath.get(), result, configuration);
                } else {
                    LOG.log(Level.WARNING, "Server jar found but bootstrap failed, using simulation");
                    var failures = result.failures().stream()
                        .map(RuntimeBootstrapResolver.ResolutionFailure::formatted)
                        .toList();
                    LOG.log(Level.WARNING, "Bootstrap failures: {0}", String.join("; ", failures));
                    adapter = new ReflectiveMinecraftRuntimeAdapter(configuration);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load server jar, using simulation: {0}", e.getMessage());
                adapter = new ReflectiveMinecraftRuntimeAdapter(configuration);
            }
        } else {
            LOG.log(Level.INFO, "No Minecraft server jar found, starting in simulation mode");
            LOG.log(Level.INFO, "Place a minecraft_server.{0}.jar in {1} to run a real server",
                configuration.minecraftVersion(), serverDirectory);
            adapter = new ReflectiveMinecraftRuntimeAdapter(configuration);
        }

        var controller = new DefaultMinecraftServerController(adapter, configuration);

        return new DefaultMinecraftRuntime(
            configuration, adapter, controller,
            lifecycleManager, eventBus, serverMetrics
        );
    }

    public static MinecraftRuntime create(
        RuntimeConfiguration configuration,
        MinecraftRuntimeAdapter adapter,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(serverMetrics, "serverMetrics");

        var controller = new DefaultMinecraftServerController(adapter, configuration);

        return new DefaultMinecraftRuntime(
            configuration, adapter, controller,
            lifecycleManager, eventBus, serverMetrics
        );
    }

    public static MinecraftRuntime fromBootstrap(
        MinecraftBootstrap bootstrap,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        try {
            return DefaultMinecraftRuntime.fromBootstrap(
                bootstrap, lifecycleManager, eventBus, serverMetrics);
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Failed to create runtime from bootstrap", e);
            return null;
        }
    }

    public static MinecraftRuntime createWithController(
        RuntimeConfiguration configuration,
        MinecraftRuntimeAdapter adapter,
        DefaultMinecraftServerController controller,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        return new DefaultMinecraftRuntime(
            configuration, adapter, controller,
            lifecycleManager, eventBus, serverMetrics
        );
    }
}


