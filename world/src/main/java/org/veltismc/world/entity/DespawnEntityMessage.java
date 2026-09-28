package org.veltismc.world.entity;

import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.region.RegionMessage;

/** Region-bound message: remove a despawned entity from the region's registry. */
public final class DespawnEntityMessage implements RegionMessage {

    private final EntityHandleImpl handle;

    public DespawnEntityMessage(EntityHandleImpl handle) {
        this.handle = handle;
    }

    @Override
    public String name() {
        return "entity-despawn";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.HIGH;
    }

    @Override
    public void execute(JobContext ctx) {
        RegionImpl region = (RegionImpl) ctx.region();
        if (region == handle.region()) {
            region.removeEntity(handle);
            handle.setRegion(null);
        }
    }
}
