package org.veltismc.veltis.server.lifecycle;

/**
 * A participant in the server lifecycle that receives notifications
 * on phase transitions.
 *
 * <p>Implementations override one or more of the default methods
 * to react to specific lifecycle events. All methods are called
 * atomically during a {@link LifecycleManager#transition(LifecyclePhase)}.
 */
public interface LifecycleParticipant {

    /**
     * Returns the name of this participant for identification.
     */
    String name();

    /**
     * Called when the lifecycle transitions from one phase to another.
     *
     * @param oldPhase the phase being left
     * @param newPhase the phase being entered
     */
    default void onPhaseChange(LifecyclePhase oldPhase, LifecyclePhase newPhase) {
    }

    /**
     * Called when the lifecycle is entering the given phase.
     *
     * @param phase the phase being entered
     */
    default void onEntering(LifecyclePhase phase) {
    }

    /**
     * Called when the lifecycle is leaving the given phase.
     *
     * @param phase the phase being left
     */
    default void onLeaving(LifecyclePhase phase) {
    }
}


