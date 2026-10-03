package org.veltismc.world.api;

/**
 * Factory entry point. The engine is a standalone module: hosts obtain it through
 * this factory (or later through dependency injection) and communicate only via
 * the interfaces in this package.
 *
 * <pre>{@code
 * WorldEngine engine = WorldEngines.create(WorldConfig.defaults());
 * engine.start();
 * }</pre>
 */
public final class WorldEngines {

    private WorldEngines() {
    }

    /** Creates an engine with default configuration (no threads are started yet). */
    public static WorldEngine create() {
        return create(WorldConfig.defaults());
    }

    /** Creates an engine with the given configuration (no threads are started yet). */
    public static WorldEngine create(WorldConfig config) {
        return new org.veltismc.world.core.DefaultWorldEngine(config);
    }
}
