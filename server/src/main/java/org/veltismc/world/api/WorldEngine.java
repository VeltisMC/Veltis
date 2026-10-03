package org.veltismc.world.api;

import java.util.Set;

/**
 * Top-level entry point of the VeltisMC world engine.
 *
 * <p>The engine is a multithreaded, region-isolated world simulation. It has no main thread:
 * every piece of simulation work is dispatched as a job to a worker that owns the target region.
 *
 * <p>Typical usage:
 * <pre>{@code
 * WorldEngine engine = WorldEngines.create(config);
 * engine.start();
 * World world = engine.createWorld("overworld");
 * // ... interact through world / chunk handles ...
 * engine.stop();
 * }</pre>
 */
public interface WorldEngine {

    /** Starts workers, the scheduler, and the watchdog. Idempotent. */
    void start();

    /** Stops simulation: flushes saves, drains queues, and stops all workers. Idempotent. */
    void stop();

    /** Returns {@code true} if {@link #start()} has been called and {@link #stop()} has not. */
    boolean isRunning();

    /** Creates a world with the engine's default configuration. */
    World createWorld(String name);

    /** Creates a world with an explicit configuration. */
    World createWorld(String name, WorldConfig config);

    /** Returns the world with the given name, or {@code null}. */
    World world(String name);

    /** Returns the names of all worlds managed by this engine. */
    Set<String> worlds();

    /** Returns the engine-wide scheduler used to dispatch jobs to regions and workers. */
    RegionScheduler scheduler();

    /** Returns a live metrics snapshot of the whole engine. */
    WorldMetrics metrics();
}
