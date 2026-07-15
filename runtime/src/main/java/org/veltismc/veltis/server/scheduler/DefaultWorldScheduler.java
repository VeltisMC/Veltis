package org.veltismc.veltis.server.scheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class DefaultWorldScheduler implements WorldScheduler {

    private static final Logger LOG = System.getLogger(DefaultWorldScheduler.class.getName());

    private final String worldName;
    private final ConcurrentLinkedQueue<Runnable> queue;
    private final AtomicBoolean running;
    private final Thread worldThread;
    private volatile boolean shutdown;

    public DefaultWorldScheduler(String worldName) {
        this.worldName = worldName;
        this.queue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(true);
        this.shutdown = false;

        this.worldThread = Thread.ofVirtual()
            .name("veltis-world-" + worldName)
            .unstarted(this::runLoop);
        this.worldThread.start();
    }

    @Override
    public void execute(Runnable task) {
        if (shutdown) return;
        queue.offer(task);
    }

    @Override
    public TaskHandle schedule(Runnable task, long delay, TimeUnit unit) {
        var handle = new WorldTask(task, unit.toMillis(delay), false, 0);
        execute(handle);
        return handle;
    }

    @Override
    public TaskHandle scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        var handle = new WorldTask(task, unit.toMillis(delay), true, unit.toMillis(period));
        execute(handle);
        return handle;
    }

    @Override
    public void cancelAll() {
        queue.clear();
    }

    @Override
    public String worldName() {
        return worldName;
    }

    @Override
    public Thread worldThread() {
        return worldThread;
    }

    @Override
    public boolean isWorldThread() {
        return Thread.currentThread() == worldThread;
    }

    @Override
    public void verifyAccess() {
        if (!isWorldThread()) {
            throw new IllegalStateException(
                "World " + worldName + " accessed from thread " + Thread.currentThread().getName()
                + " (expected: " + worldThread.getName() + ")");
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        running.set(false);
        worldThread.interrupt();
    }

    private void runLoop() {
        while (running.get() && !Thread.interrupted()) {
            var task = queue.poll();
            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    if (e instanceof IllegalStateException ise) {
                        LOG.log(Level.ERROR, "World access violation on {0}: {1}", worldName, ise.getMessage());
                    }
                    LOG.log(Level.WARNING, "World {0} task threw", worldName);
                }
            } else {
                Thread.yield();
            }
        }
    }

    private static final class WorldTask implements Runnable, TaskHandle {
        private final Runnable delegate;
        private final long delayMs;
        private final boolean repeating;
        private final long periodMs;
        private final long scheduledTime;
        private volatile boolean cancelled;

        WorldTask(Runnable delegate, long delayMs, boolean repeating, long periodMs) {
            this.delegate = delegate;
            this.delayMs = delayMs;
            this.repeating = repeating;
            this.periodMs = periodMs;
            this.scheduledTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            if (cancelled) return;
            var elapsed = System.currentTimeMillis() - scheduledTime;
            if (elapsed < delayMs) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs - elapsed));
            }
            if (cancelled) return;
            delegate.run();
        }

        @Override
        public long id() { return scheduledTime; }
        @Override
        public Runnable runnable() { return delegate; }
        @Override
        public TaskState state() { return cancelled ? TaskState.CANCELLED : TaskState.SCHEDULED; }
        @Override
        public boolean cancelled() { return cancelled; }
        @Override
        public void cancel() { cancelled = true; }
        @Override
        public long executionCount() { return 0; }
        @Override
        public long delayMs() { return delayMs; }
        @Override
        public long periodMs() { return periodMs; }
        @Override
        public boolean isRepeating() { return repeating; }
        @Override
        public boolean hasExecuted() { return false; }
    }
}
