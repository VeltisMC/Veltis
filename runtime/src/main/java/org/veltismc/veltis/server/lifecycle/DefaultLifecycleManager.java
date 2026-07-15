package org.veltismc.veltis.server.lifecycle;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class DefaultLifecycleManager implements LifecycleManager {

    private static final Logger LOG = System.getLogger(DefaultLifecycleManager.class.getName());

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
            LOG.log(Level.WARNING,
                "Invalid transition from {0} to {1}", currentPhase, target);
            return false;
        }
        notifyLeaving(currentPhase);
        previous.set(currentPhase);
        current.set(target);
        notifyEntering(target);
        notifyChange(currentPhase, target);
        LOG.log(Level.INFO, "Lifecycle: {0} -> {1}", currentPhase, target);
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
                LOG.log(Level.ERROR, "LifecycleParticipant {0} threw onLeaving", p.name());
            }
        }
    }

    private void notifyEntering(LifecyclePhase phase) {
        for (var p : participants) {
            try {
                p.onEntering(phase);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "LifecycleParticipant {0} threw onEntering", p.name());
            }
        }
    }

    private void notifyChange(LifecyclePhase oldPhase, LifecyclePhase newPhase) {
        for (var p : participants) {
            try {
                p.onPhaseChange(oldPhase, newPhase);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "LifecycleParticipant {0} threw onPhaseChange", p.name());
            }
        }
    }
}


