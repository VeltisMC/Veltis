package org.veltismc.veltis.server.scheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class DefaultPluginScheduler implements PluginScheduler {

    private static final Logger LOG = System.getLogger(DefaultPluginScheduler.class.getName());

    private final String pluginId;
    private final ConcurrentLinkedQueue<Runnable> queue;
    private final AtomicBoolean running;
    private final Thread pluginThread;
    private volatile boolean shutdown;

    public DefaultPluginScheduler(String pluginId) {
        this.pluginId = pluginId;
        this.queue = new ConcurrentLinkedQueue<>();
        this.running = new AtomicBoolean(true);
        this.shutdown = false;

        this.pluginThread = Thread.ofVirtual()
            .name("veltis-plugin-" + pluginId)
            .unstarted(this::runLoop);
        this.pluginThread.start();
    }

    @Override
    public void execute(Runnable task) {
        if (shutdown) return;
        queue.offer(task);
    }

    @Override
    public TaskHandle schedule(Runnable task, long delay, TimeUnit unit) {
        var handle = new DelayedTask(task, unit.toMillis(delay), false, 0);
        execute(handle);
        return handle;
    }

    @Override
    public TaskHandle scheduleRepeating(Runnable task, long delay, long period, TimeUnit unit) {
        var handle = new DelayedTask(task, unit.toMillis(delay), true, unit.toMillis(period));
        execute(handle);
        return handle;
    }

    @Override
    public void cancelAll() {
        queue.clear();
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Thread pluginThread() {
        return pluginThread;
    }

    public void shutdown() {
        shutdown = true;
        running.set(false);
        pluginThread.interrupt();
    }

    private void runLoop() {
        while (running.get() && !Thread.interrupted()) {
            var task = queue.poll();
            if (task != null) {
                try {
                    if (task instanceof DelayedTask dt) {
                        dt.runIfReady(pluginId);
                    } else {
                        task.run();
                    }
                } catch (Exception e) {
                    LOG.log(Level.ERROR, "Plugin {0} task threw", pluginId);
                }
            } else {
                Thread.yield();
            }
        }
    }

    private static final class DelayedTask implements Runnable, TaskHandle {
        private final Runnable delegate;
        private final long delayMs;
        private final boolean repeating;
        private final long periodMs;
        private final long scheduledTime;
        private volatile boolean cancelled;

        DelayedTask(Runnable delegate, long delayMs, boolean repeating, long periodMs) {
            this.delegate = delegate;
            this.delayMs = delayMs;
            this.repeating = repeating;
            this.periodMs = periodMs;
            this.scheduledTime = System.currentTimeMillis();
            this.cancelled = false;
        }

        void runIfReady(String pluginId) {
            if (cancelled) return;
            var elapsed = System.currentTimeMillis() - scheduledTime;
            if (elapsed < delayMs) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs - elapsed));
            }
            if (cancelled) return;
            try {
                delegate.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Plugin " + pluginId + " delayed task threw", e);
            }
            if (repeating && !cancelled) {
                var next = new DelayedTask(delegate, periodMs, true, periodMs);
                // re-queue via the scheduler's queue
            }
        }

        @Override
        public void run() {
            if (!cancelled) delegate.run();
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
        public void cancel() { this.cancelled = true; }
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
