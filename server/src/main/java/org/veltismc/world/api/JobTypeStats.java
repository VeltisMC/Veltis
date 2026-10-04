package org.veltismc.world.api;

/** Aggregated execution stats for one job name. */
public record JobTypeStats(long count, long totalNanos, double avgNanos, long maxNanos) {
}
