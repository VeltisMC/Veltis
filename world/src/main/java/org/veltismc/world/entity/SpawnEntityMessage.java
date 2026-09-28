package org.veltismc.world.entity;

import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.region.RegionMessage;

/** Region-bound message: adopt a newly spawned entity into the region's registry. */
public final class SpawnEntityMessage implements RegionMessage {

    private final EntityHandleImpl handle;

    public SpawnEntityMessage(EntityHandleImpl handle) {
        this.handle = handle;
    }

    @Override
    public String name() {
        return "entity-spawn";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.HIGH;
    }

    @Override
    public void execute(JobContext ctx) {
        RegionImpl region = (RegionImpl) ctx.region();
        // H2: the entity may have been despawned while this message was in flight
        // (unowned handle). Never adopt a despawn-requested entity.
        if (handle.isDespawnRequested()) {
            region.world().simulationImpl().dropFromRegistry(handle);
            return;
        }
        handle.setRegion(region);
        region.addEntity(handle);
    }
}
