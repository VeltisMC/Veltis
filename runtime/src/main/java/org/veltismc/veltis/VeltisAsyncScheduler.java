package org.veltismc.veltis;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.veltismc.veltis.server.scheduler.TaskHandle;
import org.veltismc.veltis.server.scheduler.TaskScheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class VeltisAsyncScheduler implements AsyncScheduler {

    private static final Logger LOG = System.getLogger(VeltisAsyncScheduler.class.getName());

    private final TaskScheduler delegate;
    private final ConcurrentHashMap<Plugin, ConcurrentHashMap.KeySetView<Long, Boolean>> pluginTasks = new ConcurrentHashMap<>();

    public VeltisAsyncScheduler(TaskScheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NotNull ScheduledTask runNow(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
        var wrapperTask = new VeltisScheduledTaskImpl(plugin, null, false);
        var handleHolder = new org.veltismc.veltis.server.scheduler.TaskHandle[1];
        handleHolder[0] = delegate.schedule(() -> {
            wrapperTask.onStart();
            try {
                task.accept(wrapperTask);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisAsyncScheduler failed", e);
                throw e;
            } finally {
                wrapperTask.onFinish();
            }
        }, 0, TimeUnit.MILLISECONDS);
        wrapperTask.attachHandle(handleHolder[0]);
        track(plugin, handleHolder[0]);
        return wrapperTask;
    }

    @Override
    public @NotNull ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delay, @NotNull TimeUnit unit) {
        var wrapperTask = new VeltisScheduledTaskImpl(plugin, null, false);
        var handleHolder = new org.veltismc.veltis.server.scheduler.TaskHandle[1];
        handleHolder[0] = delegate.schedule(() -> {
            wrapperTask.onStart();
            try {
                task.accept(wrapperTask);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisAsyncScheduler failed", e);
                throw e;
            } finally {
                wrapperTask.onFinish();
            }
        }, delay, unit);
        wrapperTask.attachHandle(handleHolder[0]);
        track(plugin, handleHolder[0]);
        return wrapperTask;
    }

    @Override
    public @NotNull ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelay, long period, @NotNull TimeUnit unit) {
        var wrapperTask = new VeltisScheduledTaskImpl(plugin, null, true);
        var handleHolder = new org.veltismc.veltis.server.scheduler.TaskHandle[1];
        handleHolder[0] = delegate.scheduleRepeating(() -> {
            wrapperTask.onStart();
            try {
                task.accept(wrapperTask);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisAsyncScheduler failed", e);
                throw e;
            } finally {
                wrapperTask.onFinish();
            }
        }, initialDelay, period, unit);
        wrapperTask.attachHandle(handleHolder[0]);
        track(plugin, handleHolder[0]);
        return wrapperTask;
    }

    @Override
    public void cancelTasks(@NotNull Plugin plugin) {
        var tasks = pluginTasks.remove(plugin);
        if (tasks != null) {
            for (var id : tasks) {
                delegate.pendingTasks().stream()
                    .filter(t -> t.id() == id)
                    .findFirst().ifPresent(TaskHandle::cancel);
            }
        }
    }

    private void track(Plugin plugin, TaskHandle handle) {
        pluginTasks.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(handle.id());
    }
}
