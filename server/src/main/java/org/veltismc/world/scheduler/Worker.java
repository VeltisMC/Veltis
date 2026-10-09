package org.veltismc.world.scheduler;

import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.api.WorkerMetrics;
import org.veltismc.world.util.LockFreeQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One scheduler thread. Owns a set of regions (the sole mutator for their chunks,
 * entities, and simulation state) and steals migratable work from the shared
 * priority queues. Never busy-waits: parks and is unparked by the {@link Parker}.
 */
public final class Worker implements Runnable {

    private final long id;
    private final String name;
    private final RegionSchedulerImpl scheduler;
    private final Thread thread;

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong jobsExecuted = new AtomicLong();
    private final AtomicLong busyNanos = new AtomicLong();
    private final AtomicLong idleNanos = new AtomicLong();
    private final AtomicInteger ownedCount = new AtomicInteger();

    private volatile String currentJob;
    private volatile long currentJobStartNanos;
    private volatile List<RegionInbox> owned = List.of();
    private volatile boolean running = true;

    Worker(long id, RegionSchedulerImpl scheduler) {
        this.id = id;
        this.name = "veltis-worker-" + id;
        this.scheduler = scheduler;
        this.thread = new Thread(this, name);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
    }

    void join() {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Thread thread() {
        return thread;
    }

    @Override
    public void run() {
        int idleParks = 0;
        while (running && !thread.isInterrupted()) {
            long loopStart = System.nanoTime();
            boolean worked = drainDelayed();
            worked |= drainOwnedRegions();
            if (!worked) {
                worked = stealAndRun();
            }
            if (worked) {
                idleParks = 0;
            } else {
                idleNanos.addAndGet(System.nanoTime() - loopStart);
                if (scheduler.shouldShrinkWorker(this, idleParks)) {
                    break;
                }
                idleParks++;
                scheduler.parker().park(scheduler.parkTimeoutNanos(), this::hasWork);
            }
        }
        scheduler.onWorkerExit(this);
    }

    /** Executes every due delayed job, re-routing them through the normal paths. */
    private boolean drainDelayed() {
        boolean any = false;
        DelayedJob dj;
        while ((dj = scheduler.delayedQueue().poll()) != null) {
            any = true;
            scheduler.route(dj.env());
        }
        return any;
    }

    /** Drains the inboxes of all owned regions (single consumer per queue). */
    private boolean drainOwnedRegions() {
        boolean any = false;
        for (RegionInbox inbox : owned) {
            LockFreeQueue<JobEnvelope> queue = inbox.inbox();
            JobEnvelope env;
            while ((env = queue.poll()) != null) {
                any = true;
                execute(env);
            }
        }
        return any;
    }

    /** Steals one migratable job from the shared priority queues. */
    private boolean stealAndRun() {
        JobEnvelope env = scheduler.steal();
        if (env == null) {
            return false;
        }
        execute(env);
        return true;
    }

    private boolean hasWork() {
        return scheduler.hasDueDelayed() || scheduler.hasGlobalWork() || hasOwnedWork();
    }

    private boolean hasOwnedWork() {
        for (RegionInbox inbox : owned) {
            if (inbox.inbox().hasItems()) {
                return true;
            }
        }
        return false;
    }

    private void execute(JobEnvelope env) {
        RegionJob job = env.job;
        JobHandleImpl handle = env.handle;
        
        // Atomically attempt to start the job. This prevents race conditions where
        // the job could be cancelled between the initial check and execution.
        if (!handle.tryStart()) {
            handle.markDone();
            return;
        }
        
        long start = System.nanoTime();
        env.startNanos = start;
        busy.set(true);
        currentJob = job.name();
        currentJobStartNanos = start;
        try {
            job.execute(new JobContextImpl(env.region, handle));
            if (scheduler.longJobThresholdNanos() > 0
                    && System.nanoTime() - start > scheduler.longJobThresholdNanos()) {
                scheduler.recordLongJob();
            }
        } catch (Throwable t) {
            scheduler.recordJobError(t, job.name());
        } finally {
            currentJob = null;
            busy.set(false);
            handle.markDone();
            long nanos = System.nanoTime() - start;
            jobsExecuted.incrementAndGet();
            busyNanos.addAndGet(nanos);
            scheduler.recordJob(job.name(), nanos);
        }
    }

    /** Grants ownership of a region to this worker. Scheduler callers only. */
    void addRegion(RegionInbox inbox) {
        synchronized (this) {
            List<RegionInbox> next = new ArrayList<>(owned.size() + 1);
            next.addAll(owned);
            next.add(inbox);
            owned = List.copyOf(next);
        }
        ownedCount.incrementAndGet();
    }

    /** Revokes ownership of a region. Scheduler callers only. */
    void removeRegion(RegionInbox inbox) {
        synchronized (this) {
            List<RegionInbox> next = new ArrayList<>(owned);
            if (next.remove(inbox)) {
                owned = List.copyOf(next);
                ownedCount.decrementAndGet();
            }
        }
    }

    List<RegionInbox> ownedSnapshot() {
        return owned;
    }

    int ownedCount() {
        return ownedCount.get();
    }

    boolean isBusy() {
        return busy.get();
    }

    long id() {
        return id;
    }

    String name() {
        return name;
    }

    /** Nanos at which the current job started (0 when idle); for the watchdog. */
    long currentJobStartNanos() {
        return currentJobStartNanos;
    }

    WorkerMetrics metrics() {
        long local = 0;
        for (RegionInbox inbox : owned) {
            local += inbox.inbox().size();
        }
        return new WorkerMetrics(
            id,
            name,
            busy.get(),
            jobsExecuted.get(),
            busyNanos.get(),
            idleNanos.get(),
            (int) local,
            owned.size(),
            currentJob);
    }
}
