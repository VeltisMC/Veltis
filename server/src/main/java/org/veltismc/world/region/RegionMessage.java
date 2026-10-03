package org.veltismc.world.region;

import org.veltismc.world.api.RegionJob;

/**
 * A message delivered to a region's inbox. Messages are region-bound jobs: they
 * are executed only by the owning worker, which makes cross-region communication
 * safe without locks. Entity migration, cross-region block updates, and explosion
 * fragments are all delivered as messages.
 */
public interface RegionMessage extends RegionJob {

    @Override
    default boolean regionBound() {
        return true;
    }
}
