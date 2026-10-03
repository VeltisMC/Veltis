package org.veltismc.world.api;

/**
 * Scheduling priority of a job. Lower ordinals are drained first.
 */
public enum JobPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}
