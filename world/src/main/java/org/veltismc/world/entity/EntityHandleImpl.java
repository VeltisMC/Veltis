package org.veltismc.world.entity;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.EntityHandle;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.SimulatedEntity;
import org.veltismc.world.region.RegionImpl;

/**
 * Handle to a simulated entity. Exactly one region owns an entity at any time;
 * the owning region's worker is the only thread that ticks it. Cross-region
 * moves go through the migration protocol (a {@link EntityMigrationMessage}),
 * never through direct ownership transfer.
 *
 * <p>Despawn protocol (Phase A, H2): a despawn of an unowned (in-flight) entity
 * cannot know where it will land, so the handle is merely flagged
 * ({@link #markDespawnRequested()}). Every possible adopter/owner — the spawn
 * region's worker, the migration destination's worker, and the ticking region's
 * worker — checks the flag on contact and purges the entity instead of adopting
 * or ticking it. Thus a despawn races neither the spawn path nor a migration.
 */
public final class EntityHandleImpl implements EntityHandle {

    private final long id;
    private final SimulatedEntity entity;
    private volatile RegionImpl region;
    private volatile boolean despawnRequested;

    public EntityHandleImpl(long id, SimulatedEntity entity) {
        this.id = id;
        this.entity = entity;
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public SimulatedEntity entity() {
        return entity;
    }

    @Override
    public BlockPos position() {
        return entity.position();
    }

    @Override
    public Region region() {
        return region;
    }

    @Override
    public boolean isOwned() {
        return region != null;
    }

    /** Ownership transfers happen on the owning worker (spawn, migration). */
    public void setRegion(RegionImpl region) {
        this.region = region;
    }

    /** Flags the entity for removal; visible to every adopter and owner. */
    public void markDespawnRequested() {
        despawnRequested = true;
    }

    /** Whether {@code markDespawnRequested} was called. */
    public boolean isDespawnRequested() {
        return despawnRequested;
    }
}
