package org.veltismc.world.core;

import org.veltismc.world.api.RegionScheduler;
import org.veltismc.world.api.PoolStats;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldMetrics;
import org.veltismc.world.scheduler.RegionSchedulerImpl;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link WorldEngine} implementation.
 *
 * <p>Owns the {@link RegionSchedulerImpl}, the world registry, and the metrics
 * snapshot. World creation, region instantiation, and the save/lighting pipelines
 * are wired here; see {@code WorldImpl} for the per-world behavior.
 */
public final class DefaultWorldEngine implements WorldEngine {

    private final WorldConfig defaultConfig;
    private final RegionSchedulerImpl scheduler;
    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong startedAtMillis = new AtomicLong();
    private final AtomicLong regionMigrations = new AtomicLong();
    private final AtomicLong deadlockDetections = new AtomicLong();
    private final DeadlockWatchdog watchdog;

    public DefaultWorldEngine(WorldConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
        this.scheduler = new RegionSchedulerImpl(defaultConfig);
        this.watchdog = new DeadlockWatchdog(scheduler, defaultConfig);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            startedAtMillis.set(System.currentTimeMillis());
            scheduler.start();
            watchdog.start();
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            watchdog.stop();
            for (World world : worlds.values()) {
                world.close();
            }
            worlds.clear();
            scheduler.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public World createWorld(String name) {
        return createWorld(name, defaultConfig);
    }

    @Override
    public World createWorld(String name, WorldConfig config) {
        if (!running.get()) {
            throw new IllegalStateException("engine is not running");
        }
        return worlds.computeIfAbsent(name, n -> new WorldImpl(n, config, scheduler, this));
    }

    @Override
    public World world(String name) {
        return worlds.get(name);
    }

    @Override
    public Set<String> worlds() {
        return Collections.unmodifiableSet(worlds.keySet());
    }

    @Override
    public RegionScheduler scheduler() {
        return scheduler;
    }

    @Override
    public WorldMetrics metrics() {
        long now = System.currentTimeMillis();
        long started = startedAtMillis.get();
        int pendingLoads = 0;
        int pendingSaves = 0;
        int pendingUnloads = 0;
        int lightingQueue = 0;
        int generationQueue = 0;
        java.util.Map<String, PoolStats> pools = new java.util.HashMap<>();
        for (World world : worlds.values()) {
            WorldImpl w = (WorldImpl) world;
            pendingLoads += w.pendingChunkLoads();
            pendingSaves += w.savesImpl().pendingSaves();
            pendingUnloads += w.pendingChunkUnloads();
            lightingQueue += w.lightingImpl().pendingCount();
            generationQueue += w.generationImpl().pendingCount();
            pools.putAll(w.poolStats());
        }
        return new WorldMetrics(
            started,
            started == 0 ? 0 : now - started,
            scheduler.workerCount(),
            scheduler.activeWorkers(),
            scheduler.totalJobsExecuted(),
            scheduler.totalJobNanos(),
            scheduler.jobStatsSnapshot(),
            scheduler.pendingByPriority(),
            scheduler.ownerNames(),
            pools,
            pendingLoads,
            pendingSaves,
            pendingUnloads,
            lightingQueue,
            generationQueue,
            regionMigrations.get(),
            deadlockDetections.get(),
            scheduler.longRunningJobs(),
            scheduler.jobErrors(),
            scheduler.workerMetrics());
    }

    void recordMigration() {
        regionMigrations.incrementAndGet();
    }

    void recordDeadlock() {
        deadlockDetections.incrementAndGet();
    }
}
