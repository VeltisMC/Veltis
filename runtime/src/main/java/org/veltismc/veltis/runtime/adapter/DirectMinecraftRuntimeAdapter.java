package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.runtime.RuntimeConfiguration;
import org.veltismc.veltis.runtime.RuntimeDiagnostics;
import org.veltismc.veltis.runtime.binding.RuntimeBindingResult;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;

public final class DirectMinecraftRuntimeAdapter implements MinecraftRuntimeAdapter, RuntimeDiagnostics.ReflectiveBindingProvider {

    private static final Logger LOG = System.getLogger(DirectMinecraftRuntimeAdapter.class.getName());

    private final ClassLoader classLoader;
    private Object serverInstance;
    private boolean running;
    private boolean ready;
    private int port;
    private int maxPlayers;
    private int playerCount;
    private String motd;
    private RuntimeBindingResult bindingResult;

    public DirectMinecraftRuntimeAdapter(RuntimeConfiguration configuration) {
        this.classLoader = DirectMinecraftRuntimeAdapter.class.getClassLoader();
        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();
        this.bindingResult = RuntimeBindingResult.bound(
            null, null, configuration.minecraftVersion(), List.of());
    }

    @Override
    public void start(RuntimeConfiguration configuration) throws Exception {
        LOG.log(Level.INFO, "Starting Minecraft server (direct adapter)...");

        this.port = configuration.port();
        this.maxPlayers = configuration.maxPlayers();
        this.motd = configuration.motd();

        var mainClass = Class.forName("net.minecraft.server.Main", true, classLoader);
        var mainMethod = mainClass.getMethod("main", String[].class);

        LOG.log(Level.INFO, "Invoking net.minecraft.server.Main.main with --nogui");
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
        waitForPortBinding();

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
                return running;
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
                return -1;
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
        this.bindingResult = result;
    }

    private void captureServerInstance() {
        Class<?> minecraftServerClass;
        try {
            minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer", true, classLoader);
        } catch (ClassNotFoundException e) {
            LOG.log(Level.WARNING, "Could not find MinecraftServer class");
            return;
        }

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

}
