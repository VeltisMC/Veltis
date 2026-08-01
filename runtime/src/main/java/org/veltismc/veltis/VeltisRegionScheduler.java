package org.veltismc.veltis;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.veltismc.veltis.server.scheduler.TaskHandle;
import org.veltismc.veltis.server.scheduler.TaskScheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class VeltisRegionScheduler implements RegionScheduler {

    private static final Logger LOG = System.getLogger(VeltisRegionScheduler.class.getName());
    private static final long TICK_MS = 50;

    private final TaskScheduler delegate;
    private final ConcurrentHashMap<Plugin, ConcurrentHashMap.KeySetView<Long, Boolean>> pluginTasks = new ConcurrentHashMap<>();

    public VeltisRegionScheduler(TaskScheduler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Runnable run) {
        var handle = delegate.schedule(run, 0, TimeUnit.MILLISECONDS);
        track(plugin, handle);
    }

    @Override
    public @NotNull ScheduledTask run(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task) {
        return runDelayed(plugin, world, chunkX, chunkZ, task, 0);
    }

    @Override
    public @NotNull ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
        var wrapper = new VeltisScheduledTaskImpl[1];
        wrapper[0] = new VeltisScheduledTaskImpl(plugin, null, false);
        TaskHandle handle = delegate.schedule(() -> {
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisRegionScheduler failed", e);
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
    public @NotNull ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
        var wrapper = new VeltisScheduledTaskImpl[1];
        wrapper[0] = new VeltisScheduledTaskImpl(plugin, null, true);
        TaskHandle handle = delegate.scheduleRepeating(() -> {
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " task in VeltisRegionScheduler failed", e);
                throw e;
            } finally {
                wrapper[0].onFinish();
            }
        }, initialDelayTicks * TICK_MS, periodTicks * TICK_MS, TimeUnit.MILLISECONDS);
        wrapper[0].attachHandle(handle);
        track(plugin, handle);
        return wrapper[0];
    }

    private void track(Plugin plugin, TaskHandle handle) {
        pluginTasks.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(handle.id());
    }
}
