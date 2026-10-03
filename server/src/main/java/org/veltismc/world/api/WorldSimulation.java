package org.veltismc.world.api;

/**
 * World simulation control: region ticking, entities, and block behaviors.
 *
 * <p>There is no engine-level main loop: each active region has a recurring
 * region-tick job scheduled through the scheduler, executed by its owner.
 */
public interface WorldSimulation {

    /** Starts simulation for all current and future regions. Idempotent. */
    void start();

    /** Stops scheduling new region ticks. Idempotent. */
    void stop();

    boolean isRunning();

    /** Pauses simulation of a region; its pending messages are still processed. */
    void pause(RegionPos pos);

    /** Resumes simulation of a region (reschedules its tick job). */
    void resume(RegionPos pos);

    boolean isPaused(RegionPos pos);

    /** Spawns an entity at its current position, owned by the containing region. */
    EntityHandle spawnEntity(SimulatedEntity entity);

    /** Removes an entity from its owning region. */
    void despawnEntity(EntityHandle handle);

    /** Number of entities currently alive in this world. */
    int entityCount();

    /** Number of regions with active simulation. */
    int activeRegions();

    /** Registers the block behavior callback (replaces any previous one). */
    void registerBlockSimulator(BlockSimulator simulator);

    /** Explodes a sphere of radius {@code radius} around {@code center} as a region job. */
    void explode(BlockPos center, int radius);
}
