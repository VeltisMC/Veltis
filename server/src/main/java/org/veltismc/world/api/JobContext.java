package org.veltismc.world.api;

/**
 * Context passed to a {@link RegionJob} at execution time.
 */
public interface JobContext {

    /** The region this job runs for; {@code null} for migratable engine-level jobs. */
    Region region();

    /** Whether this job was cancelled. Jobs should check this frequently and bail out. */
    boolean cancelled();
}
