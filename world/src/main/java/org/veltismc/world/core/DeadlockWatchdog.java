package org.veltismc.world.core;

import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorkerMetrics;
import org.veltismc.world.scheduler.RegionSchedulerImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches workers for stalled jobs. If a worker stays on the same job longer
 * than the long-job threshold, the run is counted and logged; if the *same*
 * job is still running at the next check, it is treated as a deadlock and
 * recorded in the engine metrics. Pure monitoring: never interrupts.
 */
final class DeadlockWatchdog implements Runnable {

    private final RegionSchedulerImpl scheduler;
    private final long intervalNanos;
    private final long thresholdNanos;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<Long, String> lastWarned = new HashMap<>();
    private Thread thread;

    DeadlockWatchdog(RegionSchedulerImpl scheduler, WorldConfig config) {
        this.scheduler = scheduler;
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(config.watchdogIntervalMillis());
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(config.longJobThresholdMillis());
    }

    void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this, "veltis-watchdog");
            thread.setDaemon(true);
            thread.start();
        }
    }

    void stop() {
        if (running.compareAndSet(true, false)) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(intervalNanos));
            } catch (InterruptedException e) {
                break;
            }
            long now = System.nanoTime();
            List<WorkerMetrics> workers = scheduler.workerMetrics();
            for (WorkerMetrics w : workers) {
                if (w.currentJob() == null) {
                    lastWarned.remove(w.id());
                    continue;
                }
                long start = scheduler.workerJobStartNanos(w.id());
                if (start == 0 || now - start <= thresholdNanos) {
                    continue;
                }
                scheduler.recordLongJob();
                if (w.currentJob().equals(lastWarned.get(w.id()))) {
                    System.err.println("[veltis-world-engine] suspected deadlock: worker " + w.name()
                        + " stuck on '" + w.currentJob() + "' past the threshold");
                } else {
                    System.err.println("[veltis-world-engine] long-running job: worker " + w.name()
                        + " on '" + w.currentJob() + "' for over the threshold");
                    lastWarned.put(w.id(), w.currentJob());
                }
            }
        }
    }
}
