package org.veltismc.veltis;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
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

public class VeltisGlobalRegionScheduler implements GlobalRegionScheduler {

    private static final Logger LOG = System.getLogger(VeltisGlobalRegionScheduler.class.getName());
    private static final long TICK_MS = 50;

    private final TaskScheduler delegate;
    private final ConcurrentHashMap<Plugin, ConcurrentHashMap.KeySetView<Long, Boolean>> pluginTasks = new ConcurrentHashMap<>();

    public VeltisGlobalRegionScheduler(TaskScheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(@NotNull Plugin plugin, @NotNull Runnable run) {
        var handle = delegate.schedule(run, 0, TimeUnit.MILLISECONDS);
        track(plugin, handle);
    }

    @Override
    public @NotNull ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
        return runDelayed(plugin, task, 0);
    }

    @Override
    public @NotNull ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
        var wrapper = new VeltisPaperScheduledTask[1];
        wrapper[0] = new VeltisPaperScheduledTask(plugin, null, false);
        TaskHandle handle = delegate.schedule(() -> {
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisGlobalRegionScheduler failed", e);
                throw e;
            } finally {
                wrapper[0].onFinish();
            }
        }, delayTicks * TICK_MS, TimeUnit.MILLISECONDS);
        wrapper[0].attachHandle(handle);
        track(plugin, handle);
        return wrapper[0];
    }

    @Override
    public @NotNull ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
        var wrapper = new VeltisPaperScheduledTask[1];
        wrapper[0] = new VeltisPaperScheduledTask(plugin, null, true);
        TaskHandle handle = delegate.scheduleRepeating(() -> {
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisGlobalRegionScheduler failed", e);
                throw e;
            } finally {
                wrapper[0].onFinish();
            }
        }, initialDelayTicks * TICK_MS, periodTicks * TICK_MS, TimeUnit.MILLISECONDS);
        wrapper[0].attachHandle(handle);
        track(plugin, handle);
        return wrapper[0];
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
