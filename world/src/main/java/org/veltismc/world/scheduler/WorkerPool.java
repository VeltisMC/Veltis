package org.veltismc.world.scheduler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lifecycle and adaptive sizing of scheduler workers.
 *
 * <p>Starts with {@code minWorkers}; scales up under load (checked on submit) and
 * lets idle workers retire back down to {@code minWorkers}. Worker count changes
 * are rare, so a copy-on-write list is sufficient.
 */
public final class WorkerPool {

    private final RegionSchedulerImpl scheduler;
    private final int minWorkers;
    private final int maxWorkers;
    private final boolean adaptive;
    private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();
    private long lastSpawnCheckNanos;
    private long lastShrinkCheckNanos;

    WorkerPool(RegionSchedulerImpl scheduler, int minWorkers, int maxWorkers, boolean adaptive) {
        this.scheduler = scheduler;
        this.minWorkers = minWorkers;
        this.maxWorkers = maxWorkers;
        this.adaptive = adaptive;
    }

    void start() {
        for (int i = 0; i < minWorkers; i++) {
            spawn();
        }
    }

    private Worker spawn() {
        Worker worker = new Worker(seq.incrementAndGet(), scheduler);
        workers.add(worker);
        worker.start();
        return worker;
    }

    List<Worker> workers() {
        return workers;
    }

    int size() {
        return workers.size();
    }

    /**
     * Spawns an extra worker when demand is high. Called from the submit path;
     * the check is rate-limited so it is cheap in the steady state.
     */
    boolean considerScaling() {
        if (!adaptive || workers.size() >= maxWorkers) {
            return false;
        }
        long now = System.nanoTime();
        synchronized (this) {
            if (now - lastSpawnCheckNanos < 50_000_000L) {
                return false;
            }
            lastSpawnCheckNanos = now;
            if (scheduler.pendingJobs() > workers.size() * 4L) {
                spawn();
                return true;
            }
        }
        return false;
    }

    /**
     * Approves an idle worker's request to retire. Reassigns its regions before
     * it leaves so no region is ever owned by a dying worker.
     */
    boolean tryShrink(Worker worker) {
        if (!adaptive || workers.size() <= minWorkers) {
            return false;
        }
        long now = System.nanoTime();
        synchronized (this) {
            if (now - lastShrinkCheckNanos < 250_000_000L) {
                return false;
            }
            if (scheduler.pendingJobs() > 0) {
                return false;
            }
            lastShrinkCheckNanos = now;
        }
        scheduler.reassignRegions(worker);
        workers.remove(worker);
        return true;
    }

    /** Reassigns a departed worker's regions and removes it from the roster. */
    void onWorkerExit(Worker worker) {
        workers.remove(worker);
        scheduler.reassignRegions(worker);
        scheduler.parker().signalAll();
    }

    void stopAll() {
        for (Worker worker : workers) {
            worker.stop();
        }
        for (Worker worker : workers) {
            worker.join();
        }
        workers.clear();
    }
}
