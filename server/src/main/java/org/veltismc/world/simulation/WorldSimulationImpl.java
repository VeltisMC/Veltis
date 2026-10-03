package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockSimulator;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.EntityHandle;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.SimulatedEntity;
import org.veltismc.world.api.WorldSimulation;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.entity.DespawnEntityMessage;
import org.veltismc.world.entity.EntityHandleImpl;
import org.veltismc.world.entity.SpawnEntityMessage;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.util.IdGenerator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link WorldSimulation}. There is no engine-level main loop: each
 * active region has a recurring {@link RegionTickJob} scheduled through the
 * scheduler and executed by its owner worker.
 */
public final class WorldSimulationImpl implements WorldSimulation {

    private final WorldImpl world;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentHashMap<Long, EntityHandleImpl> allEntities = new ConcurrentHashMap<>();
    private final IdGenerator entityIds = new IdGenerator();
    private volatile BlockSimulator blockSimulator;

    public WorldSimulationImpl(WorldImpl world) {
        this.world = world;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            for (RegionImpl region : world.regionsSnapshot()) {
                region.setActive(true);
                scheduleRegionTick(region);
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void pause(RegionPos pos) {
        RegionImpl region = world.regionFor(pos);
        if (region != null) {
            region.setActive(false);
        }
    }

    @Override
    public void resume(RegionPos pos) {
        RegionImpl region = world.regionFor(pos);
        if (region != null) {
            region.setActive(true);
            scheduleRegionTick(region);
        }
    }

    @Override
    public boolean isPaused(RegionPos pos) {
        RegionImpl region = world.regionFor(pos);
        return region != null && !region.isActive();
    }

    @Override
    public EntityHandle spawnEntity(SimulatedEntity entity) {
        if (entity.position() == null) {
            throw new IllegalArgumentException("entity must have a position to spawn");
        }
        EntityHandleImpl handle = new EntityHandleImpl(entityIds.next(), entity);
        allEntities.put(handle.id(), handle);
        RegionImpl region = world.regionImpl(ChunkPos.containing(entity.position()));
        region.deliver(new SpawnEntityMessage(handle));
        return handle;
    }

    @Override
    public void despawnEntity(EntityHandle handle) {
        if (!(handle instanceof EntityHandleImpl impl)) {
            return;
        }
        allEntities.remove(impl.id());
        // H2: flag first so an in-flight spawn/migration message (executed on a
        // destination worker at any time) can observe the despawn. Then, if the
        // entity currently has an owner, ask that region's worker to purge it.
        impl.markDespawnRequested();
        if (impl.isOwned()) {
            ((RegionImpl) impl.region()).deliver(new DespawnEntityMessage(impl));
        }
    }

    /** Removes an entity from the world registry; called by the migration message when it drops a despawn-raced entity. */
    public void dropFromRegistry(EntityHandleImpl handle) {
        allEntities.remove(handle.id());
    }

    @Override
    public int entityCount() {
        return allEntities.size();
    }

    @Override
    public int activeRegions() {
        int active = 0;
        for (RegionImpl region : world.regionsSnapshot()) {
            if (region.isActive()) {
                active++;
            }
        }
        return active;
    }

    @Override
    public void registerBlockSimulator(BlockSimulator simulator) {
        this.blockSimulator = simulator;
    }

    @Override
    public void explode(BlockPos center, int radius) {
        world.region(ChunkPos.containing(center)).submit(new ExplosionJob(world, center, radius));
    }

    /** Schedules the region's next tick (single-flight via {@code tickScheduled}). */
    public void scheduleRegionTick(RegionImpl region) {
        if (!running.get() || !region.isActive() || !region.tryMarkTickScheduled()) {
            return;
        }
        world.scheduler().scheduleDelayed(region, new RegionTickJob(world, region),
            TimeUnit.MILLISECONDS.toNanos(world.config().simTickIntervalMillis()));
    }

    /** Called when a chunk becomes ready so freshly generated regions start ticking. */
    public void onChunkReady(RegionImpl region) {
        if (running.get() && region.isActive()) {
            scheduleRegionTick(region);
        }
    }

    public BlockSimulator simulator() {
        return blockSimulator;
    }
}
