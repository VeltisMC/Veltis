package org.veltismc.world.scheduler;

import org.veltismc.world.api.JobHandle;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.JobTypeStats;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.RegionScheduler;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorkerMetrics;
import org.veltismc.world.util.LockFreeQueue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The engine's job scheduler.
 *
 * <p>Region-bound jobs go into the target region's inbox and are only executed by
 * the worker that owns the region. Migratable jobs enter one of five global
 * priority queues (CRITICAL first) and are stolen by any idle worker. Delayed jobs
 * (region ticks, autosave flushes) sit in a {@link DelayQueue} until due. There is
 * no busy waiting: workers park and are unparked by the {@link Parker}.
 */
public final class RegionSchedulerImpl implements RegionScheduler {

    private final WorldConfig config;
    private final Parker parker = new Parker();
    private final WorkerPool pool;
    private final LockFreeQueue<JobEnvelope>[] global;
    private final DelayQueue<DelayedJob> delayed = new DelayQueue<>();

    private final ConcurrentHashMap<Long, RegionInbox> regions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Worker> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> jobStats = new ConcurrentHashMap<>();

    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicLong totalJobsExecuted = new AtomicLong();
    private final AtomicLong totalJobNanos = new AtomicLong();
    private final AtomicLong longRunningJobs = new AtomicLong();
    private final AtomicLong jobErrors = new AtomicLong();

    @SuppressWarnings("unchecked")
    public RegionSchedulerImpl(WorldConfig config) {
        this.config = config;
        JobPriority[] priorities = JobPriority.values();
        this.global = new LockFreeQueue[priorities.length];
        for (int i = 0; i < priorities.length; i++) {
            this.global[i] = new LockFreeQueue<>();
        }
        this.pool = new WorkerPool(this, config.minWorkers(), config.maxWorkers(), config.adaptiveWorkers());
    }

    public void start() {
        pool.start();
    }

    @Override
    public JobHandle schedule(RegionJob job) {
        JobHandleImpl handle = new JobHandleImpl();
        submitGlobal(new JobEnvelope(job, handle));
        return handle;
    }

    @Override
    public JobHandle schedule(Region region, RegionJob job) {
        JobHandleImpl handle = new JobHandleImpl();
        route(new JobEnvelope(region, job, handle));
        return handle;
    }

    /**
     * Schedules a delayed region-bound job (region tick cadence, autosave flush).
     * The job is routed through the region's inbox once due.
     */
    public JobHandle scheduleDelayed(Region region, RegionJob job, long delayNanos) {
        JobHandleImpl handle = new JobHandleImpl();
        if (shutdown.get()) {
            handle.markDone();
            return handle;
        }
        delayed.offer(new DelayedJob(new JobEnvelope(region, job, handle), System.nanoTime() + delayNanos));
        parker.signal();
        return handle;
    }

    /**
     * Routes a job to its destination: a region inbox for region-bound jobs, or
     * the global priority queue otherwise. Assigns an owner if the region has none.
     */
    void route(JobEnvelope env) {
        if (shutdown.get()) {
            env.handle.markDone();
            return;
        }
        Region region = env.region;
        if (region == null) {
            submitGlobal(env);
            return;
        }
        RegionInbox inbox = regions.get(region.pos().key());
        if (inbox == null) {
            // Region unloaded between scheduling and execution: drop the job.
            env.handle.markDone();
            return;
        }
        inbox.inbox().add(env);
        Worker owner = owners.get(region.pos().key());
        if (owner == null) {
            assignOwner(inbox);
            owner = owners.get(region.pos().key());
        }
        if (owner != null) {
            parker.signal(owner.thread());
        }
        pool.considerScaling();
    }

    private void submitGlobal(JobEnvelope env) {
        if (shutdown.get()) {
            env.handle.markDone();
            return;
        }
        global[env.job.priority().ordinal()].add(env);
        parker.signal();
        pool.considerScaling();
    }

    JobEnvelope steal() {
        JobPriority[] priorities = JobPriority.values();
        for (int i = 0; i < priorities.length; i++) {
            JobEnvelope env = global[i].poll();
            if (env != null) {
                return env;
            }
        }
        return null;
    }

    boolean hasGlobalWork() {
        for (LockFreeQueue<JobEnvelope> queue : global) {
            if (queue.hasItems()) {
                return true;
            }
        }
        return false;
    }

    boolean hasDueDelayed() {
        DelayedJob dj = delayed.peek();
        return dj != null && dj.getDelay(TimeUnit.NANOSECONDS) <= 0;
    }

    DelayQueue<DelayedJob> delayedQueue() {
        return delayed;
    }

    Parker parker() {
        return parker;
    }

    long parkTimeoutNanos() {
        return config.workerParkTimeoutNanos();
    }

    long longJobThresholdNanos() {
        return TimeUnit.MILLISECONDS.toNanos(config.longJobThresholdMillis());
    }

    /** Registers a region with the scheduler. Called by the region package. */
    public void registerRegion(RegionInbox inbox) {
        regions.put(inbox.pos().key(), inbox);
    }

    /** Unregisters a region and revokes ownership. Pending jobs are dropped. */
    public void unregisterRegion(RegionInbox inbox) {
        regions.remove(inbox.pos().key());
        Worker owner = owners.remove(inbox.pos().key());
        if (owner != null) {
            owner.removeRegion(inbox);
        }
    }

    /** Assigns the least-loaded worker as owner of an unowned region. */
    private void assignOwner(RegionInbox inbox) {
        long key = inbox.pos().key();
        if (owners.containsKey(key)) {
            return;
        }
        Worker best = null;
        int least = Integer.MAX_VALUE;
        for (Worker worker : pool.workers()) {
            int owned = worker.ownedCount();
            if (owned < least) {
                least = owned;
                best = worker;
            }
        }
        if (best != null && owners.putIfAbsent(key, best) == null) {
            best.addRegion(inbox);
        }
    }

    /** Moves every region owned by {@code leaving} to another worker. */
    void reassignRegions(Worker leaving) {
        for (RegionInbox inbox : leaving.ownedSnapshot()) {
            if (owners.remove(inbox.pos().key(), leaving)) {
                assignOwner(inbox);
            }
        }
    }

    boolean shouldShrinkWorker(Worker worker, int idleParks) {
        return idleParks > 8 && pool.tryShrink(worker);
    }

    void onWorkerExit(Worker worker) {
        pool.onWorkerExit(worker);
    }

    void recordJob(String name, long nanos) {
        while (true) {
            long[] cur = jobStats.computeIfAbsent(name, k -> new long[3]);
            long[] next = {cur[0] + 1, cur[1] + nanos, Math.max(cur[2], nanos)};
            if (jobStats.replace(name, cur, next)) {
                break;
            }
        }
        totalJobsExecuted.incrementAndGet();
        totalJobNanos.addAndGet(nanos);
    }

    public void recordLongJob() {
        longRunningJobs.incrementAndGet();
    }

    void recordJobError(Throwable t, String jobName) {
        jobErrors.incrementAndGet();
        System.err.println("[veltis-world-engine] job '" + jobName + "' failed: " + t);
        t.printStackTrace(System.err);
    }

    @Override
    public int pendingJobs() {
        int total = delayed.size();
        for (LockFreeQueue<JobEnvelope> queue : global) {
            total += queue.size();
        }
        for (RegionInbox inbox : regions.values()) {
            total += inbox.inbox().size();
        }
        return total;
    }

    @Override
    public int activeWorkers() {
        int active = 0;
        for (Worker worker : pool.workers()) {
            if (worker.isBusy()) {
                active++;
            }
        }
        return active;
    }

    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        pool.stopAll();
        for (LockFreeQueue<JobEnvelope> queue : global) {
            JobEnvelope env;
            while ((env = queue.poll()) != null) {
                env.handle.markDone();
            }
        }
        DelayedJob dj;
        while ((dj = delayed.poll()) != null) {
            dj.env().handle.markDone();
        }
        regions.clear();
        owners.clear();
    }

    /** Snapshot of per-job-type execution stats for metrics. */
    public Map<String, JobTypeStats> jobStatsSnapshot() {
        Map<String, JobTypeStats> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : jobStats.entrySet()) {
            long[] v = e.getValue();
            out.put(e.getKey(), new JobTypeStats(v[0], v[1], v[0] == 0 ? 0 : (double) v[1] / (double) v[0], v[2]));
        }
        return out;
    }

    /** Snapshot of queued migratable jobs per priority, for metrics. */
    public Map<JobPriority, Integer> pendingByPriority() {
        Map<JobPriority, Integer> out = new EnumMap<>(JobPriority.class);
        for (JobPriority priority : JobPriority.values()) {
            out.put(priority, global[priority.ordinal()].size());
        }
        return out;
    }

    /** Snapshot of per-worker utilization, for metrics. */
    public List<WorkerMetrics> workerMetrics() {
        List<WorkerMetrics> out = new ArrayList<>(pool.size());
        for (Worker worker : pool.workers()) {
            out.add(worker.metrics());
        }
        return out;
    }

    /** Nanos at which the given worker's current job started (0 if idle/unknown); for the watchdog. */
    public long workerJobStartNanos(long workerId) {
        for (Worker worker : pool.workers()) {
            if (worker.id() == workerId) {
                return worker.currentJobStartNanos();
            }
        }
        return 0;
    }

    /** Snapshot of region ownership (region position -> owning worker name). */
    public Map<RegionPos, String> ownerNames() {
        Map<RegionPos, String> out = new HashMap<>();
        for (Map.Entry<Long, Worker> e : owners.entrySet()) {
            RegionInbox inbox = regions.get(e.getKey());
            if (inbox != null) {
                out.put(inbox.pos(), e.getValue().name());
            }
        }
        return out;
    }

    /** The thread of the worker currently owning a region, or {@code null}. */
    public Thread ownerThread(RegionPos pos) {
        Worker owner = owners.get(pos.key());
        return owner == null ? null : owner.thread();
    }

    /** The name of the worker currently owning a region, or "unowned". */
    public String ownerName(RegionPos pos) {
        Worker owner = owners.get(pos.key());
        return owner == null ? "unowned" : owner.name();
    }

    public long totalJobsExecuted() {
        return totalJobsExecuted.get();
    }

    public long totalJobNanos() {
        return totalJobNanos.get();
    }

    public long longRunningJobs() {
        return longRunningJobs.get();
    }

    public long jobErrors() {
        return jobErrors.get();
    }

    public int workerCount() {
        return pool.size();
    }
}
