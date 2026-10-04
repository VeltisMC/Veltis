package org.veltismc.runtime.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class DefaultTaskScheduler implements TaskScheduler {

    private static final Logger LOG = LogManager.getLogger(DefaultTaskScheduler.class);

    private final ConcurrentHashMap<Long, TaskHandleImpl> tasks;
    private final AtomicLong idCounter;
    private volatile boolean shutdown;

    public DefaultTaskScheduler() {
        this.tasks = new ConcurrentHashMap<>();
        this.idCounter = new AtomicLong(0);
        this.shutdown = false;
    }

    @Override
    public TaskHandle schedule(Runnable runnable, long delay, TimeUnit unit) {
        var id = idCounter.incrementAndGet();
        var delayMs = unit.toMillis(delay);
        var handle = new TaskHandleImpl(id, runnable, false, delayMs, 0);
        tasks.put(id, handle);
        var thread = Thread.ofVirtual()
            .name("nova-scheduler-" + id)
            .unstarted(() -> executeDelayed(handle));
        handle.thread(thread);
        thread.start();
        return handle;
    }

    @Override
    public TaskHandle scheduleRepeating(Runnable runnable, long delay, long period, TimeUnit unit) {
        var id = idCounter.incrementAndGet();
        var delayMs = unit.toMillis(delay);
        var periodMs = unit.toMillis(period);
        var handle = new TaskHandleImpl(id, runnable, true, delayMs, periodMs);
        tasks.put(id, handle);
        var thread = Thread.ofVirtual()
            .name("nova-scheduler-repeating-" + id)
            .unstarted(() -> executeRepeating(handle));
        handle.thread(thread);
        thread.start();
        return handle;
    }

    @Override
    public int pendingCount() {
        return tasks.size();
    }

    @Override
    public Collection<TaskHandle> pendingTasks() {
        return List.copyOf(tasks.values());
    }

    @Override
    public void shutdown() {
        shutdown = true;
        tasks.values().forEach(h -> h.cancel());
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    private void executeDelayed(TaskHandleImpl handle) {
        try {
            if (handle.delayMs > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(handle.delayMs));
            }
            if (handle.state() != ScheduledTask.TaskState.CANCELLED) {
                handle.setTaskState(ScheduledTask.TaskState.EXECUTING);
                handle.executionCount.incrementAndGet();
                handle.runnable.run();
                handle.setTaskState(ScheduledTask.TaskState.COMPLETED);
            }
        } catch (Exception e) {
            handle.setTaskState(ScheduledTask.TaskState.FAILED);
            LOG.error("Scheduled task {} failed", handle.id(), e);
        } finally {
            tasks.remove(handle.id());
        }
    }

    private void executeRepeating(TaskHandleImpl handle) {
        try {
            if (handle.delayMs > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(handle.delayMs));
            }
            while (!handle.cancelled() && !Thread.interrupted()) {
                handle.setTaskState(ScheduledTask.TaskState.EXECUTING);
                handle.executionCount.incrementAndGet();
                try {
                    handle.runnable.run();
                } catch (Exception e) {
                    LOG.error("Repeating task {} threw", handle.id(), e);
                }
                handle.setTaskState(ScheduledTask.TaskState.SCHEDULED);
                if (handle.periodMs > 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(handle.periodMs));
                }
            }
        } finally {
            tasks.remove(handle.id());
        }
    }

    private static final class TaskHandleImpl implements TaskHandle {
        private final long id;
        private final Runnable runnable;
        private final boolean repeating;
        private final long delayMs;
        private final long periodMs;
        private final AtomicLong executionCount;

        private volatile TaskState taskState;
        private volatile Thread taskThread;

        TaskHandleImpl(
            long id, Runnable runnable, boolean repeating,
            long delayMs, long periodMs
        ) {
            this.id = id;
            this.runnable = runnable;
            this.repeating = repeating;
            this.delayMs = delayMs;
            this.periodMs = periodMs;
            this.taskState = TaskState.SCHEDULED;
            this.executionCount = new AtomicLong(0);
        }

        void thread(Thread t) {
            this.taskThread = t;
        }

        void setTaskState(TaskState state) {
            this.taskState = state;
        }

        @Override
        public long id() { return id; }

        @Override
        public Runnable runnable() { return runnable; }

        @Override
        public TaskState state() { return taskState; }

        @Override
        public boolean cancelled() {
            return taskState == TaskState.CANCELLED;
        }

        @Override
        public void cancel() {
            taskState = TaskState.CANCELLED;
            if (taskThread != null) {
                taskThread.interrupt();
            }
        }

        @Override
        public long executionCount() { return executionCount.get(); }

        @Override
        public long delayMs() { return delayMs; }

        @Override
        public long periodMs() { return periodMs; }

        @Override
        public boolean isRepeating() { return repeating; }

        @Override
        public boolean hasExecuted() { return executionCount.get() > 0; }
    }
}


