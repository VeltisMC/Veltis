package org.veltismc.runtime.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultLifecycleManager implements LifecycleManager {

    private static final Logger LOG = LogManager.getLogger(DefaultLifecycleManager.class);

    private final AtomicReference<LifecyclePhase> current;
    private final AtomicReference<LifecyclePhase> previous;
    private final CopyOnWriteArrayList<LifecycleParticipant> participants;

    public DefaultLifecycleManager() {
        this.current = new AtomicReference<>(LifecyclePhase.CREATED);
        this.previous = new AtomicReference<>(null);
        this.participants = new CopyOnWriteArrayList<>();
    }

    @Override
    public LifecyclePhase currentPhase() {
        return current.get();
    }

    @Override
    public LifecyclePhase previousPhase() {
        return previous.get();
    }

    @Override
    public boolean transition(LifecyclePhase target) {
        var currentPhase = current.get();
        if (!currentPhase.allowsTransitionTo(target)) {
            LOG.warn("Invalid transition from {} to {}", currentPhase, target);
            return false;
        }
        notifyLeaving(currentPhase);
        previous.set(currentPhase);
        current.set(target);
        notifyEntering(target);
        notifyChange(currentPhase, target);
        // Phase transitions themselves are internal bookkeeping — logged only
        // at debug so normal startup output stays clean.
        LOG.debug("Lifecycle: {} -> {}", currentPhase, target);
        return true;
    }

    @Override
    public boolean isAtLeast(LifecyclePhase phase) {
        return current.get().isAtLeast(phase);
    }

    @Override
    public boolean isAtMost(LifecyclePhase phase) {
        return current.get().isAtMost(phase);
    }

    @Override
    public boolean hasEverBeen(LifecyclePhase phase) {
        return current.get() == phase || previous.get() == phase;
    }

    @Override
    public void register(LifecycleParticipant participant) {
        participants.add(participant);
    }

    @Override
    public boolean unregister(LifecycleParticipant participant) {
        return participants.remove(participant);
    }

    @Override
    public Collection<LifecycleParticipant> participants() {
        return List.copyOf(participants);
    }

    @Override
    public Optional<LifecycleParticipant> find(String name) {
        return participants.stream()
            .filter(p -> p.name().equals(name))
            .findFirst();
    }

    private void notifyLeaving(LifecyclePhase phase) {
        for (var p : participants) {
            try {
                p.onLeaving(phase);
            } catch (Exception e) {
                LOG.error("LifecycleParticipant {} threw onLeaving", p.name(), e);
            }
        }
    }

    private void notifyEntering(LifecyclePhase phase) {
        for (var p : participants) {
            try {
                p.onEntering(phase);
            } catch (Exception e) {
                LOG.error("LifecycleParticipant {} threw onEntering", p.name(), e);
            }
        }
    }

    private void notifyChange(LifecyclePhase oldPhase, LifecyclePhase newPhase) {
        for (var p : participants) {
            try {
                p.onPhaseChange(oldPhase, newPhase);
            } catch (Exception e) {
                LOG.error("LifecycleParticipant {} threw onPhaseChange", p.name(), e);
            }
        }
    }
}


