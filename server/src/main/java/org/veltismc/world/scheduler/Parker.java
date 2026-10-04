package org.veltismc.world.scheduler;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Shared parking facility for workers. Workers register before parking and
 * double-check for work afterwards, so a signal that races the park never gets
 * lost. There is no busy waiting anywhere in the engine.
 */
public final class Parker {

    private final ConcurrentLinkedQueue<Thread> parked = new ConcurrentLinkedQueue<>();

    /**
     * Parks the current thread for at most {@code timeoutNanos}, or returns
     * immediately if {@code recheck} reports work.
     */
    public void park(long timeoutNanos, BooleanSupplier recheck) {
        parked.add(Thread.currentThread());
        if (recheck.getAsBoolean()) {
            parked.remove(Thread.currentThread());
            return;
        }
        LockSupport.parkNanos(timeoutNanos);
        parked.remove(Thread.currentThread());
    }

    /** Wakes one parked worker. */
    public void signal() {
        Thread t = parked.poll();
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /** Wakes a specific worker if it is parked; no-op otherwise. */
    public void signal(Thread target) {
        if (parked.remove(target)) {
            LockSupport.unpark(target);
        }
    }

    /** Wakes all parked workers. */
    public void signalAll() {
        Thread t;
        while ((t = parked.poll()) != null) {
            LockSupport.unpark(t);
        }
    }

    public int parkedCount() {
        return parked.size();
    }
}
