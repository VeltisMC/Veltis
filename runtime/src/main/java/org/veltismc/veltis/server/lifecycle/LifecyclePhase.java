package org.veltismc.veltis.server.lifecycle;

/**
 * Represents a phase in the VeltisMC server lifecycle.
 *
 * <p>Phases follow a strict deterministic order:
 * <pre>
 * CREATED → INITIALIZING → STARTING → RUNNING → STOPPING → STOPPED
 *                                              ↘
 *                                                CRASHED
 * </pre>
 *
 * <p>Each phase only allows transitions to specific target phases
 * as defined by {@link #allowsTransitionTo(LifecyclePhase)}.
 */
public enum LifecyclePhase {

    CREATED(0, "Created"),
    INITIALIZING(1, "Initializing"),
    STARTING(2, "Starting"),
    RUNNING(3, "Running"),
    STOPPING(4, "Stopping"),
    STOPPED(5, "Stopped"),
    CRASHED(6, "Crashed");

    private final int order;
    private final String displayName;

    LifecyclePhase(int order, String displayName) {
        this.order = order;
        this.displayName = displayName;
    }

    /**
     * Returns the numeric ordering of this phase (0-6).
     */
    public int order() {
        return order;
    }

    /**
     * Returns a human-readable display name for this phase.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns true if this phase is at or after the given phase in the lifecycle.
     */
    public boolean isAtLeast(LifecyclePhase other) {
        return this.order >= other.order;
    }

    /**
     * Returns true if this phase is at or before the given phase in the lifecycle.
     */
    public boolean isAtMost(LifecyclePhase other) {
        return this.order <= other.order;
    }

    /**
     * Returns true if a transition from this phase to the target is valid.
     *
     * <p>Valid transitions:
     * <ul>
     *   <li>CREATED → INITIALIZING</li>
     *   <li>INITIALIZING → STARTING, CRASHED</li>
     *   <li>STARTING → RUNNING, CRASHED</li>
     *   <li>RUNNING → STOPPING, CRASHED</li>
     *   <li>STOPPING → STOPPED, CRASHED</li>
     *   <li>STOPPED → (none)</li>
     *   <li>CRASHED → (none)</li>
     * </ul>
     */
    public boolean allowsTransitionTo(LifecyclePhase target) {
        return switch (this) {
            case CREATED -> target == INITIALIZING;
            case INITIALIZING -> target == STARTING || target == CRASHED;
            case STARTING -> target == RUNNING || target == CRASHED;
            case RUNNING -> target == STOPPING || target == CRASHED;
            case STOPPING -> target == STOPPED || target == CRASHED;
            case STOPPED, CRASHED -> false;
        };
    }
}



