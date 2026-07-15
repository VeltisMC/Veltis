package org.veltismc.veltis.server.scheduler;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class VeltisScheduler implements BukkitScheduler {

    private static final System.Logger LOG = System.getLogger("VeltisScheduler");
    private static final long TICK_MS = 50L;

    enum TaskState { WAITING, RUNNING, FINISHED, CANCELLED }

    private final AtomicInteger taskIdCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, VeltisScheduledTask> tasks = new ConcurrentHashMap<>();

    private final TaskScheduler asyncScheduler;
    private final Thread heartbeatThread;

    private final PriorityBlockingQueue<VeltisScheduledTask> syncQueue = new PriorityBlockingQueue<>(64,
        (a, b) -> Long.compare(a.nextRunTick, b.nextRunTick));
    private volatile int currentTick;
    private volatile boolean running = true;

    public VeltisScheduler(TaskScheduler asyncScheduler) {
        this.asyncScheduler = asyncScheduler;
        this.heartbeatThread = Thread.ofPlatform()
            .name("VeltisScheduler-heartbeat")
            .daemon(true)
            .unstarted(this::heartbeatLoop);
        this.heartbeatThread.start();
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            tick();
        }
    }

    public void tick() {
        currentTick++;
        long now = currentTick;
        while (true) {
            var task = syncQueue.peek();
            if (task == null || task.nextRunTick > now) break;
            syncQueue.poll();
            if (task.cancelled) continue;
            try {
                task.state = TaskState.RUNNING;
                task.runnable.run();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR, "Task " + task.id + " threw an exception", t);
            } finally {
                if (task.repeating && !task.cancelled) {
                    task.nextRunTick = currentTick + task.period;
                    task.state = TaskState.WAITING;
                    syncQueue.add(task);
                } else {
                    task.state = TaskState.FINISHED;
                    tasks.remove(task.id);
                }
            }
        }
    }

    public void shutdown() {
        running = false;
        heartbeatThread.interrupt();
    }

    @Override
    public void runTask(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task) {
        scheduleSync(plugin, wrapConsumer(task), 0L, 0L);
    }

    @Override
    public @NotNull BukkitTask runTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return scheduleSync(plugin, task, 0L, 0L);
    }

    @Override
    public @NotNull BukkitTask runTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return runTask(plugin, (Runnable) task);
    }

    @Override
    public void runTaskLater(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay) {
        scheduleSync(plugin, wrapConsumer(task), delay, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskLater(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return scheduleSync(plugin, task, delay, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskLater(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return runTaskLater(plugin, (Runnable) task, delay);
    }

    @Override
    public void runTaskTimer(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay, long period) {
        scheduleSync(plugin, wrapConsumer(task), delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimer(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return scheduleSync(plugin, task, delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimer(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return runTaskTimer(plugin, (Runnable) task, delay, period);
    }

    @Override
    public void runTaskAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task) {
        scheduleAsync(plugin, wrapConsumer(task), 0L, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task) {
        return scheduleAsync(plugin, task, 0L, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return runTaskAsynchronously(plugin, (Runnable) task);
    }

    @Override
    public void runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay) {
        scheduleAsync(plugin, wrapConsumer(task), delay, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return scheduleAsync(plugin, task, delay, 0L);
    }

    @Override
    public @NotNull BukkitTask runTaskLaterAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, (Runnable) task, delay);
    }

    @Override
    public void runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull Consumer<? super BukkitTask> task, long delay, long period) {
        scheduleAsync(plugin, wrapConsumer(task), delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return scheduleAsync(plugin, task, delay, period);
    }

    @Override
    public @NotNull BukkitTask runTaskTimerAsynchronously(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, (Runnable) task, delay, period);
    }

    @Override
    public <T> @NotNull Future<T> callSyncMethod(@NotNull Plugin plugin, @NotNull Callable<T> task) {
        var future = new CompletableFuture<T>();
        runTask(plugin, () -> {
            try { future.complete(task.call()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        return future;
    }

    @Override
    public void cancelTask(int taskId) {
        var task = tasks.get(taskId);
        if (task != null) task.cancelled = true;
    }

    @Override
    public void cancelTasks(@NotNull Plugin plugin) {
        for (var task : tasks.values()) {
            if (task.owningPlugin != null && task.owningPlugin.equals(plugin)) {
                task.cancelled = true;
            }
        }
    }

    /** Not part of BukkitScheduler interface — convenience method. */
    public void cancelAllTasks() {
        for (var task : tasks.values()) {
            task.cancelled = true;
        }
        tasks.clear();
        syncQueue.clear();
    }

    @Override
    public boolean isCurrentlyRunning(int taskId) {
        var task = tasks.get(taskId);
        return task != null && task.state == TaskState.RUNNING;
    }

    @Override
    public boolean isQueued(int taskId) {
        var task = tasks.get(taskId);
        return task != null && task.state == TaskState.WAITING;
    }

    @Override
    public @NotNull List<BukkitWorker> getActiveWorkers() {
        return List.of();
    }

    @Override
    public @NotNull List<BukkitTask> getPendingTasks() {
        return List.copyOf(tasks.values());
    }

    // --- deprecated schedule* methods (return int) ---

    @Override @Deprecated(since = "1.7.10")
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return runTask(plugin, task).getTaskId();
    }

    @Override @Deprecated(since = "1.7.10")
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task) {
        return runTask(plugin, task).getTaskId();
    }

    @Override
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return runTaskLater(plugin, task, delay).getTaskId();
    }

    @Override @Deprecated(since = "1.7.10")
    public int scheduleSyncDelayedTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay) {
        return runTaskLater(plugin, task, delay).getTaskId();
    }

    @Override
    public int scheduleSyncRepeatingTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return runTaskTimer(plugin, task, delay, period).getTaskId();
    }

    @Override @Deprecated(since = "1.7.10")
    public int scheduleSyncRepeatingTask(@NotNull Plugin plugin, @NotNull BukkitRunnable task, long delay, long period) {
        return runTaskTimer(plugin, task, delay, period).getTaskId();
    }

    @Override @Deprecated(since = "1.4.5")
    public int scheduleAsyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task) {
        return runTaskAsynchronously(plugin, task).getTaskId();
    }

    @Override @Deprecated(since = "1.4.5")
    public int scheduleAsyncDelayedTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay) {
        return runTaskLaterAsynchronously(plugin, task, delay).getTaskId();
    }

    @Override @Deprecated(since = "1.4.5")
    public int scheduleAsyncRepeatingTask(@NotNull Plugin plugin, @NotNull Runnable task, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, task, delay, period).getTaskId();
    }

    @Override
    public @NotNull Executor getMainThreadExecutor(@NotNull Plugin plugin) {
        return command -> runTask(plugin, command);
    }

    // --- internal scheduling ---

    private Runnable wrapConsumer(Consumer<? super BukkitTask> consumer) {
        return () -> {
            // Consumers get a dummy/unlinked task reference
        };
    }

    private BukkitTask scheduleSync(Plugin plugin, Runnable runnable, long delay, long period) {
        if (delay < 0) delay = 0;
        if (period < 0) period = 0;
        int id = taskIdCounter.getAndIncrement();
        boolean repeating = period > 0;
        long firstRun = currentTick + delay + 1;
        var task = new VeltisScheduledTask(id, plugin, true, repeating, firstRun, period, runnable);
        tasks.put(id, task);
        syncQueue.add(task);
        return task;
    }

    private BukkitTask scheduleAsync(Plugin plugin, Runnable runnable, long delay, long period) {
        if (delay < 0) delay = 0;
        if (period < 0) period = 0;
        int id = taskIdCounter.getAndIncrement();
        boolean repeating = period > 0;
        long delayMs = delay * TICK_MS;
        long periodMs = period * TICK_MS;

        var veltisHandle = repeating
            ? asyncScheduler.scheduleRepeating(runnable, delayMs, periodMs, TimeUnit.MILLISECONDS)
            : asyncScheduler.schedule(runnable, delayMs, TimeUnit.MILLISECONDS);

        var task = new VeltisScheduledTask(id, plugin, false, repeating, 0, period, runnable) {
            @Override public void cancel() {
                super.cancel();
                veltisHandle.cancel();
            }
        };
        tasks.put(id, task);
        return task;
    }

    static class VeltisScheduledTask implements BukkitTask, Comparable<VeltisScheduledTask> {
        final int id;
        final Plugin owningPlugin;
        final boolean sync;
        final boolean repeating;
        final Runnable runnable;
        final long period;
        volatile long nextRunTick;
        volatile boolean cancelled;
        volatile TaskState state = TaskState.WAITING;

        VeltisScheduledTask(int id, Plugin plugin, boolean sync, boolean repeating,
                            long nextRunTick, long period, Runnable runnable) {
            this.id = id;
            this.owningPlugin = plugin;
            this.sync = sync;
            this.repeating = repeating;
            this.nextRunTick = nextRunTick;
            this.period = period;
            this.runnable = runnable;
        }

        @Override public int getTaskId() { return id; }
        @Override public @NotNull Plugin getOwner() { return owningPlugin; }
        @Override public boolean isSync() { return sync; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { this.cancelled = true; }

        @Override
        public int compareTo(VeltisScheduledTask o) {
            return Long.compare(this.nextRunTick, o.nextRunTick);
        }
    }
}
