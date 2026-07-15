package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.runtime.RuntimeConfiguration;
import org.veltismc.veltis.runtime.RuntimeDiagnostics;
import org.veltismc.veltis.runtime.bootstrap.RuntimeBootstrapResolver.BootstrapResult;
import org.veltismc.veltis.runtime.binding.RuntimeBindingResult;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ReflectiveMinecraftRuntimeAdapter implements MinecraftRuntimeAdapter, RuntimeDiagnostics.ReflectiveBindingProvider {

    private static final Logger LOG = System.getLogger(ReflectiveMinecraftRuntimeAdapter.class.getName());

    private final ClassLoader classLoader;
    private final Path serverJar;
    private final Class<?> mainClass;
    private final Class<?> minecraftServerClass;
    private final Class<?> dedicatedServerClass;
    private final List<String> classNames;
    private Object serverInstance;
    private boolean running;
    private boolean ready;
    private int port;
    private int maxPlayers;
    private int playerCount;
    private String motd;
    private RuntimeBindingResult bindingResult;

    public ReflectiveMinecraftRuntimeAdapter() {
        this.classLoader = getClass().getClassLoader();
        this.serverJar = null;
        this.mainClass = null;
        this.minecraftServerClass = null;
        this.dedicatedServerClass = null;
        this.classNames = List.of();
        this.bindingResult = RuntimeBindingResult.simulation("No server jar configured");
    }

    public ReflectiveMinecraftRuntimeAdapter(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.serverJar = null;
        this.mainClass = null;
        this.minecraftServerClass = null;
        this.dedicatedServerClass = null;
        this.classNames = List.of();
        this.bindingResult = RuntimeBindingResult.simulation("No server jar configured");
    }

    public ReflectiveMinecraftRuntimeAdapter(RuntimeConfiguration configuration) {
        this();
        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();
    }

    public ReflectiveMinecraftRuntimeAdapter(ClassLoader classLoader, RuntimeConfiguration configuration) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.serverJar = null;
        this.mainClass = null;
        this.minecraftServerClass = null;
        this.dedicatedServerClass = null;
        this.classNames = List.of();
        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();
        this.bindingResult = RuntimeBindingResult.simulation("No server jar configured");
    }

    public ReflectiveMinecraftRuntimeAdapter(ClassLoader classLoader, Path serverJar,
                                              BootstrapResult bootstrapResult,
                                              RuntimeConfiguration configuration) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.serverJar = serverJar;
        this.mainClass = bootstrapResult.mainClass();
        this.minecraftServerClass = bootstrapResult.minecraftServerClass();
        this.dedicatedServerClass = bootstrapResult.dedicatedServerClass();
        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();
        if (bootstrapResult.classIndex() != null) {
            this.classNames = bootstrapResult.classIndex().allClassNames();
        } else {
            this.classNames = List.of();
        }
        this.bindingResult = RuntimeBindingResult.bound(serverJar,
            classLoader instanceof java.net.URLClassLoader ucl ? ucl : null,
            configuration.minecraftVersion(), classNames);
    }

    @Override
    public void start(RuntimeConfiguration configuration) throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        LOG.log(Level.INFO, "Starting Minecraft server via reflection...");

        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();

        var serverClass = resolveServerClass();
        if (serverClass == null) {
            LOG.log(Level.WARNING, "Minecraft server class not found. Running in headless mode.");
            simulateRunning(configuration);
            return;
        }

        try {
            writeServerProperties(configuration);
            startWithMainClass(configuration);
            waitForPortBinding();
        } catch (Exception e) {
            try {
                startWithServerClass(serverClass, configuration);
                waitForPortBinding();
            } catch (Exception e2) {
                LOG.log(Level.ERROR, "Failed to start Minecraft server via reflection", e2);
                simulateRunning(configuration);
                return;
            }
        }

        this.running = true;
        this.ready = true;
    }

    @Override
    public void stop() throws Exception {
        if (serverInstance != null) {
            try {
                var stopMethod = serverInstance.getClass().getMethod("stop");
                stopMethod.invoke(serverInstance);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to call stop() reflectively: {0}", e.getMessage());
            }
            serverInstance = null;
        }
        this.running = false;
        this.ready = false;
    }

    @Override
    public boolean isRunning() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("isRunning");
                return (boolean) method.invoke(serverInstance);
            } catch (Exception e) {
                try {
                    var field = serverInstance.getClass().getField("running");
                    return field.getBoolean(serverInstance);
                } catch (Exception e2) {
                    return running;
                }
            }
        }
        return running;
    }

    @Override
    public boolean isReady() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("isReady");
                return (boolean) method.invoke(serverInstance);
            } catch (Exception e) {
                return ready;
            }
        }
        return ready;
    }

    @Override
    public long tickCount() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("getTickCount");
                return (long) method.invoke(serverInstance);
            } catch (Exception e) {
                try {
                    var field = serverInstance.getClass().getField("tickCount");
                    return field.getLong(serverInstance);
                } catch (Exception e2) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @Override
    public int maxPlayers() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("getMaxPlayers");
                return (int) method.invoke(serverInstance);
            } catch (Exception e) {
                return maxPlayers;
            }
        }
        return maxPlayers;
    }

    @Override
    public int playerCount() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("getPlayerCount");
                return (int) method.invoke(serverInstance);
            } catch (Exception e) {
                try {
                    var playerList = serverInstance.getClass().getMethod("getPlayerList");
                    var list = playerList.invoke(serverInstance);
                    var players = list.getClass().getMethod("getPlayers");
                    var pList = (java.util.List<?>) players.invoke(list);
                    return pList != null ? pList.size() : playerCount;
                } catch (Exception e2) {
                    return playerCount;
                }
            }
        }
        return playerCount;
    }

    @Override
    public int port() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("getPort");
                return (int) method.invoke(serverInstance);
            } catch (Exception e) {
                return port;
            }
        }
        return port;
    }

    @Override
    public String motd() {
        if (serverInstance != null) {
            try {
                var method = serverInstance.getClass().getMethod("getMotd");
                return (String) method.invoke(serverInstance);
            } catch (Exception e) {
                return motd;
            }
        }
        return motd;
    }

    @Override
    public Object serverInstance() {
        return serverInstance;
    }

    @Override
    public RuntimeBindingResult bindingResult() {
        return bindingResult;
    }

    public void setBindingResult(RuntimeBindingResult result) {
        this.bindingResult = Objects.requireNonNull(result, "bindingResult");
    }

    private Class<?> resolveServerClass() {
        if (dedicatedServerClass != null) return dedicatedServerClass;
        return minecraftServerClass;
    }

    private void startWithMainClass(RuntimeConfiguration configuration) throws Exception {
        if (mainClass == null) {
            throw new IllegalStateException("No main class resolved");
        }
        var mainMethod = mainClass.getMethod("main", String[].class);

        LOG.log(Level.INFO, "Invoking {0}.main with --nogui", mainClass.getName());
        var thread = Thread.ofVirtual()
            .name("minecraft-server-main")
            .unstarted(() -> {
                try {
                    mainMethod.invoke(null, (Object) new String[]{"--nogui"});
                } catch (Exception e) {
                    LOG.log(Level.ERROR, "Minecraft Main.main threw", e);
                }
            });
        thread.start();

        captureServerInstance();
    }

    private void startWithServerClass(Class<?> serverClass, RuntimeConfiguration configuration) throws Exception {
        try {
            var serverField = serverClass.getField("SERVER");
            var existing = serverField.get(null);
            if (existing != null) {
                LOG.log(Level.DEBUG, "Server instance already exists, reusing");
                this.serverInstance = existing;
                return;
            }
        } catch (NoSuchFieldException e) {
            LOG.log(Level.DEBUG, "No SERVER field on server class");
        }

        try {
            var dedicatedCtor = serverClass.getDeclaredConstructor();
            dedicatedCtor.setAccessible(true);
            var instance = dedicatedCtor.newInstance();
            this.serverInstance = instance;

            var initMethod = serverClass.getMethod("init");
            initMethod.invoke(instance);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not instantiate server directly: {0}", e.getMessage());
        }

        LOG.log(Level.WARNING, "Could not start Minecraft server via reflection.");
    }

    private void captureServerInstance() {
        if (minecraftServerClass == null) return;
        for (int i = 0; i < 100; i++) {
            try {
                Field serverField;
                try {
                    serverField = minecraftServerClass.getField("SERVER");
                } catch (NoSuchFieldException e) {
                    serverField = minecraftServerClass.getDeclaredField("SERVER");
                    serverField.setAccessible(true);
                }
                var instance = serverField.get(null);
                if (instance != null) {
                    this.serverInstance = instance;
                    LOG.log(Level.INFO, "Captured Minecraft server instance");
                    return;
                }
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Waiting for server instance...");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.log(Level.WARNING, "Could not capture Minecraft server instance after 20 seconds");
    }

    private void waitForPortBinding() {
        for (int i = 0; i < 50; i++) {
            if (isPortListening(port)) {
                LOG.log(Level.INFO, "Server is listening on port {0}", port);
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.log(Level.WARNING, "Server did not bind to port {0} within 10 seconds", port);
    }

    private boolean isPortListening(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void simulateRunning(RuntimeConfiguration configuration) {
        LOG.log(Level.INFO, "Simulating Minecraft server runtime (no server classes available)");
        this.running = true;
        this.ready = true;
        this.serverInstance = null;
        if (bindingResult == null || !bindingResult.isReal()) {
            this.bindingResult = RuntimeBindingResult.simulation(
                "Mojang server classes not found on classpath");
        }
    }

    private void writeServerProperties(RuntimeConfiguration configuration) throws IOException {

        var propsFile =
                configuration.serverDirectory()
                        .resolve("server.properties");

        // Don't overwrite existing file
        if (Files.exists(propsFile)) {
            LOG.log(Level.INFO,
                    "Using existing server.properties");
            return;
        }

        LOG.log(Level.INFO,
                "Generating default server.properties");

        var lines = new ArrayList<String>();

        lines.add("#Minecraft server properties");
        lines.add("#Generated by VeltisMC");

        lines.add("server-port=" + configuration.port());
        lines.add("max-players=" + configuration.maxPlayers());
        lines.add("online-mode=" + configuration.onlineMode());

        lines.add("difficulty=easy");
        lines.add("gamemode=survival");
        lines.add("motd=VeltisMC Server");

        Files.createDirectories(propsFile.getParent());

        Files.writeString(
                propsFile,
                String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE
        );
    }
}



