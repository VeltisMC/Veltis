package org.veltismc.world.entity;

import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.region.RegionMessage;

/**
 * Region-bound migration message. The old region drops ownership (registry
 * removal, handle in flight) and posts this to the destination region, whose
 * worker adopts the entity. An entity never has two owners, and only the owner
 * ever touches the registry.
 */
public final class EntityMigrationMessage implements RegionMessage {

    private final EntityHandleImpl handle;

    public EntityMigrationMessage(EntityHandleImpl handle) {
        this.handle = handle;
    }

    @Override
    public String name() {
        return "entity-migrate";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.NORMAL;
    }

    @Override
    public void execute(JobContext ctx) {
        RegionImpl target = (RegionImpl) ctx.region();
        // H2: a despawn racing this migration is flagged on the handle; the
        // destination must not adopt it. The world registry entry is removed here
        // because the despawn path skipped the unowned handle.
        if (handle.isDespawnRequested()) {
            target.world().simulationImpl().dropFromRegistry(handle);
            return;
        }
        handle.setRegion(target);
        target.addEntity(handle);
        target.world().recordMigration();
    }
}
