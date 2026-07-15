package org.veltismc.veltis.runtime;

import org.veltismc.veltis.server.lifecycle.LifecyclePhase;
import org.veltismc.veltis.server.runtime.ServerRuntime;

/**
 * Represents the lifecycle state of the Minecraft runtime.
 *
 * <p>This is distinct from {@link LifecyclePhase}
 * and {@link ServerRuntime.RuntimeState} — it
 * tracks the state of the underlying Mojang server process specifically.
 *
 * <p>Valid transitions:
 * <pre>
 * CREATED → BOOTSTRAPPING → STARTING → RUNNING → STOPPING → STOPPED
 *                                            ↘
 *                                              CRASHED
 * </pre>
 */
public enum RuntimeState {

    CREATED,
    BOOTSTRAPPING,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    CRASHED;

    /**
     * Returns true if this state represents an active runtime
     * (STARTING or RUNNING).
     */
    public boolean isActive() {
        return this == STARTING || this == RUNNING;
    }

    /**
     * Returns true if this state is a terminal state (STOPPED or CRASHED).
     */
    public boolean isTerminal() {
        return this == STOPPED || this == CRASHED;
    }

    /**
     * Returns true if a transition from this state to the target is valid.
     */
    public boolean allowsTransitionTo(RuntimeState target) {
        return switch (this) {
            case CREATED -> target == BOOTSTRAPPING;
            case BOOTSTRAPPING -> target == STARTING || target == CRASHED;
            case STARTING -> target == RUNNING || target == CRASHED;
            case RUNNING -> target == STOPPING || target == CRASHED;
            case STOPPING -> target == STOPPED || target == CRASHED;
            case STOPPED, CRASHED -> false;
        };
    }
}


