package org.veltismc.veltis.runtime;

import org.veltismc.veltis.server.container.DefaultPlayerContainer;
import org.veltismc.veltis.server.container.DefaultWorldContainer;
import org.veltismc.veltis.runtime.adapter.EntityRuntimeAdapter;
import org.veltismc.veltis.runtime.adapter.MinecraftRuntimeAdapter;
import org.veltismc.veltis.runtime.adapter.PlayerRuntimeAdapter;
import org.veltismc.veltis.runtime.adapter.WorldRuntimeAdapter;
import org.veltismc.veltis.runtime.bootstrap.MinecraftBootstrap;
import org.veltismc.veltis.runtime.bootstrap.RuntimeBootstrapResult;
import org.veltismc.veltis.runtime.bootstrap.RuntimeValidator;
import org.veltismc.veltis.runtime.event.PlayerJoinBridgeEvent;
import org.veltismc.veltis.runtime.event.PlayerQuitBridgeEvent;
import org.veltismc.veltis.runtime.event.RuntimePortBoundEvent;
import org.veltismc.veltis.runtime.event.RuntimeReadyEvent;
import org.veltismc.veltis.runtime.lifecycle.RuntimeLifecycleBridge;
import org.veltismc.veltis.runtime.minecraft.MinecraftServerController;
import org.veltismc.veltis.server.container.PlayerContainer;
import org.veltismc.veltis.server.container.WorldContainer;
import org.veltismc.veltis.server.event.EventBus;
import org.veltismc.veltis.server.lifecycle.LifecycleManager;
import org.veltismc.veltis.server.metrics.ServerMetrics;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultMinecraftRuntime implements MinecraftRuntime {

    private static final Logger LOG = System.getLogger(DefaultMinecraftRuntime.class.getName());

    private final RuntimeConfiguration configuration;
    private final MinecraftRuntimeAdapter adapter;
    private final MinecraftServerController controller;
    private final RuntimeLifecycleBridge lifecycleBridge;
    private final ServerMetrics serverMetrics;
    private final EventBus eventBus;

    private PlayerRuntimeAdapter playerAdapter;
    private WorldRuntimeAdapter worldAdapter;
    private EntityRuntimeAdapter entityAdapter;
    private PlayerContainer playerContainer;
    private WorldContainer worldContainer;

    private final AtomicReference<RuntimeState> state;
    private final AtomicLong startedAtNanos;
    private final AtomicLong lastTickCount;

    private final Set<UUID> trackedPlayers;

    private RuntimeBootstrapResult bootstrapResult;
    private RuntimeValidator.ValidationResult validationResult;

    public DefaultMinecraftRuntime(
        RuntimeConfiguration configuration,
        MinecraftRuntimeAdapter adapter,
        MinecraftServerController controller,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.serverMetrics = Objects.requireNonNull(serverMetrics, "serverMetrics");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.state = new AtomicReference<>(RuntimeState.CREATED);
        this.startedAtNanos = new AtomicLong(0);
        this.lastTickCount = new AtomicLong(0);
        this.trackedPlayers = new HashSet<>();

        this.lifecycleBridge = new RuntimeLifecycleBridge(
            this, lifecycleManager, eventBus);
    }

    public static DefaultMinecraftRuntime fromBootstrap(
        MinecraftBootstrap bootstrap,
        LifecycleManager lifecycleManager,
        EventBus eventBus,
        ServerMetrics serverMetrics
    ) {
        var result = bootstrap.bootstrap();
        if (!result.success()) {
            throw new RuntimeException("Bootstrap failed: " + result.errorMessage());
        }
        var runtime = new DefaultMinecraftRuntime(
            result.configuration(),
            result.adapter(),
            result.controller(),
            lifecycleManager,
            eventBus,
            serverMetrics
        );
        runtime.bootstrapResult = result;
        runtime.validationResult = bootstrap.validator().validate();
        return runtime;
    }

    @Override
    public RuntimeState state() {
        return state.get();
    }

    @Override
    public RuntimeContext context() {
        var s = state.get();
        var started = startedAtNanos.get();
        return new RuntimeContext(
            s,
            configuration.minecraftVersion(),
            configuration.protocolVersion(),
            started > 0 ? (System.nanoTime() - started) / 1_000_000 : 0,
            controller.tickCount(),
            adapter.playerCount(),
            adapter.maxPlayers(),
            controller.isReady(),
            adapter.port()
        );
    }

    @Override
    public RuntimeConfiguration configuration() {
        return configuration;
    }

    @Override
    public MinecraftServerController controller() {
        return controller;
    }

    @Override
    public MinecraftRuntimeAdapter adapter() {
        return adapter;
    }

    @Override
    public RuntimeBootstrapResult bootstrapResult() {
        return bootstrapResult;
    }

    @Override
    public void start() {
        if (!state.compareAndSet(RuntimeState.CREATED, RuntimeState.BOOTSTRAPPING)) {
            throw new IllegalStateException("Cannot start runtime from state: " + state.get());
        }

        lifecycleBridge.register();
        updateMetrics();

        LOG.log(Level.INFO, "Starting Minecraft runtime...");
        controller.startServer().join();

        wireAdapters();
        synchronizeState();
        detectPortBinding();

        state.set(RuntimeState.RUNNING);
        startedAtNanos.set(System.nanoTime());
        lifecycleBridge.transitionTo(RuntimeState.RUNNING);
        LOG.log(Level.INFO, "Minecraft runtime started");
    }

    @Override
    public void stop() {
        var previous = state.getAndSet(RuntimeState.STOPPING);
        if (previous == RuntimeState.STOPPED || previous == RuntimeState.STOPPING) {
            return;
        }

        lifecycleBridge.transitionTo(RuntimeState.STOPPING);
        LOG.log(Level.INFO, "Stopping Minecraft runtime...");

        controller.stopServer().join();

        state.set(RuntimeState.STOPPED);
        lifecycleBridge.transitionTo(RuntimeState.STOPPED);
        lifecycleBridge.unregister();
        LOG.log(Level.INFO, "Minecraft runtime stopped");
    }

    @Override
    public boolean isRunning() {
        return state.get() == RuntimeState.RUNNING;
    }

    @Override
    public boolean isReady() {
        return isRunning() && controller.isReady();
    }

    @Override
    public long uptimeMs() {
        var started = startedAtNanos.get();
        if (started == 0) return 0;
        return (System.nanoTime() - started) / 1_000_000;
    }

    @Override
    public long tickCount() {
        return controller.tickCount();
    }

    public void updateMetrics() {
        try {
            serverMetrics.playerCount(adapter.playerCount());
            serverMetrics.maxPlayers(adapter.maxPlayers());
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not update runtime metrics: {0}", e.getMessage());
        }
        synchronizePlayers();
        updatePlayerWorlds();
    }

    public void connect(PlayerContainer playerContainer, WorldContainer worldContainer) {
        this.playerContainer = playerContainer;
        this.worldContainer = worldContainer;
    }

    private void wireAdapters() {
        var server = adapter.serverInstance();
        if (server == null) {
            LOG.log(Level.INFO, "No Minecraft server instance available, skipping adapter wiring");
            return;
        }
        try {
            this.worldAdapter = new WorldRuntimeAdapter(server);
            this.entityAdapter = new EntityRuntimeAdapter(worldAdapter);
            this.playerAdapter = new PlayerRuntimeAdapter(server);
            LOG.log(Level.INFO, "Runtime adapters wired to live Minecraft server");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to wire runtime adapters: {0}", e.getMessage());
        }
    }

    private void synchronizeState() {
        if (playerContainer == null || worldContainer == null) {
            LOG.log(Level.INFO, "Containers not connected, skipping state synchronization");
            return;
        }
        if (worldAdapter == null || playerAdapter == null) {
            LOG.log(Level.INFO, "Adapters not wired, skipping state synchronization");
            return;
        }
        try {
            synchronizeWorlds();
            synchronizePlayers();
            LOG.log(Level.INFO, "Runtime state synchronized with live Minecraft server");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to synchronize runtime state: {0}", e.getMessage());
        }
    }

    private void synchronizeWorlds() {
        if (worldAdapter == null || worldContainer == null) return;
        var levels = worldAdapter.getAllLevels();
        for (var level : levels) {
            var adapted = worldAdapter.adapt(level);
            if (!worldContainer.contains(adapted.uniqueId())) {
                worldContainer.add(new DefaultWorldContainer.AdaptedInternal(adapted));
                LOG.log(System.Logger.Level.INFO, "Loading world {0}", adapted.name());
            }
        }
        serverMetrics.worldCount(worldContainer.count());
    }

    private void synchronizePlayers() {
        if (playerAdapter == null || playerContainer == null || worldAdapter == null) return;
        var now = System.currentTimeMillis();
        var mojangPlayers = playerAdapter.getAllPlayers();
        var currentUuids = new HashSet<UUID>();

        for (var mojangPlayer : mojangPlayers) {
            try {
                var adapted = playerAdapter.adapt(mojangPlayer, worldAdapter);
                var uuid = adapted.uniqueId();
                currentUuids.add(uuid);

                if (!trackedPlayers.contains(uuid)) {
                    trackedPlayers.add(uuid);
                    playerContainer.add(new DefaultPlayerContainer.AdaptedInternal(adapted));
                    LOG.log(Level.INFO, "Player joined: {0} ({1})", adapted.profile().name(), uuid);
                    eventBus.publish(new PlayerJoinBridgeEvent(
                        now, adapted, adapted.profile().name() + " joined the game"));
                }
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Failed to synchronize player: {0}", e.getMessage());
            }
        }

        var removed = new HashSet<>(trackedPlayers);
        removed.removeAll(currentUuids);
        for (var uuid : removed) {
            trackedPlayers.remove(uuid);
            var removedPlayer = playerContainer.remove(uuid);
            removedPlayer.ifPresent(player -> {
                var displayName = player.displayName();
                LOG.log(Level.INFO, "Player quit: {0} ({1})", displayName, uuid);
                var modelPlayer = ((DefaultPlayerContainer.AdaptedInternal) player).delegate();
                eventBus.publish(new PlayerQuitBridgeEvent(
                    now, modelPlayer, displayName + " left the game", "disconnect"));
            });
        }
    }

    private void detectPortBinding() {
        var serverPort = adapter.port();
        var bound = false;
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", serverPort), 500);
            bound = true;
        } catch (Exception e) {
            bound = false;
        }
        if (bound) {
            LOG.log(Level.INFO, "Server bound to port {0}", serverPort);
            eventBus.publish(new RuntimePortBoundEvent(
                System.currentTimeMillis(), serverPort, true));
        } else {
            LOG.log(Level.WARNING, "Server not detected on port {0}", serverPort);
            eventBus.publish(new RuntimePortBoundEvent(
                System.currentTimeMillis(), serverPort, false));
        }
        eventBus.publish(new RuntimeReadyEvent(
            System.currentTimeMillis(), context()));
    }

    private void updatePlayerWorlds() {
        if (playerContainer == null || worldContainer == null || adapter.serverInstance() == null) {
            return;
        }
        try {
            serverMetrics.worldCount(worldContainer.count());
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not update world metrics: {0}", e.getMessage());
        }
    }

    public PlayerRuntimeAdapter playerAdapter() { return playerAdapter; }
    public WorldRuntimeAdapter worldAdapter() { return worldAdapter; }
    public EntityRuntimeAdapter entityAdapter() { return entityAdapter; }

    public RuntimeLifecycleBridge lifecycleBridge() {
        return lifecycleBridge;
    }

    public RuntimeValidator.ValidationResult validationResult() {
        return validationResult;
    }
}


