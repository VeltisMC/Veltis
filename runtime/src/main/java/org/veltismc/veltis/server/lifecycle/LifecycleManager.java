package org.veltismc.veltis.server.lifecycle;

import java.util.Collection;
import java.util.Optional;

/**
 * Manages the server lifecycle phase transitions.
 *
 * <p>Provides deterministic, validated transitions between
 * {@link LifecyclePhase} values and notifies registered
 * {@link LifecycleParticipant} instances on every phase change.
 */
public interface LifecycleManager {

    /**
     * Returns the current lifecycle phase.
     */
    LifecyclePhase currentPhase();

    /**
     * Returns the phase that preceded the current one, or null if
     * no transition has occurred.
     */
    LifecyclePhase previousPhase();

    /**
     * Attempts a validated transition to the target phase.
     *
     * <p>If the transition is not allowed by the current phase's
     * transition rules, this method returns false and logs a warning.
     * On success, all registered participants are notified.
     *
     * @param target the phase to transition to
     * @return true if the transition was performed
     */
    boolean transition(LifecyclePhase target);

    /**
     * Returns true if the current phase is at or after the given phase.
     */
    boolean isAtLeast(LifecyclePhase phase);

    /**
     * Returns true if the current phase is at or before the given phase.
     */
    boolean isAtMost(LifecyclePhase phase);

    /**
     * Returns true if the server has ever reached the given phase.
     */
    boolean hasEverBeen(LifecyclePhase phase);

    /**
     * Registers a participant to receive lifecycle notifications.
     *
     * @param participant the participant to register
     */
    void register(LifecycleParticipant participant);

    /**
     * Unregisters a participant from receiving lifecycle notifications.
     *
     * @param participant the participant to unregister
     * @return true if the participant was found and removed
     */
    boolean unregister(LifecycleParticipant participant);

    /**
     * Returns an immutable view of all registered participants.
     */
    Collection<LifecycleParticipant> participants();

    /**
     * Finds a registered participant by name.
     *
     * @param name the participant name
     * @return the participant, or empty if not found
     */
    Optional<LifecycleParticipant> find(String name);
}


