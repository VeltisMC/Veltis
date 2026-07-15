package org.veltismc.veltis;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.veltismc.veltis.server.scheduler.TaskHandle;
import org.veltismc.veltis.server.scheduler.TaskScheduler;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class VeltisEntityScheduler implements EntityScheduler {

    private static final Logger LOG = System.getLogger(VeltisEntityScheduler.class.getName());
    private static final long TICK_MS = 50;

    private final TaskScheduler delegate;
    private final Object entity;

    public VeltisEntityScheduler(TaskScheduler delegate, Object entity) {
        this.delegate = delegate;
        this.entity = entity;
    }

    private boolean isEntityAlive() {
        if (entity == null) return false;
        try {
            return (boolean) entity.getClass().getMethod("isAlive").invoke(entity);
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public boolean execute(@NotNull Plugin plugin, @NotNull Runnable run, @Nullable Runnable retired, long delay) {
        scheduleTask(() -> {
            if (!isEntityAlive()) {
                if (retired != null) retired.run();
                return;
            }
            run.run();
        }, delay * TICK_MS);
        return true;
    }

    @Override
    public @Nullable ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired) {
        return runDelayed(plugin, task, retired, 1);
    }

    @Override
    public @Nullable ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired, long delayTicks) {
        var wrapper = new VeltisPaperScheduledTask[1];
        wrapper[0] = new VeltisPaperScheduledTask(plugin, null, false);
        TaskHandle handle = delegate.schedule(() -> {
            if (!isEntityAlive()) {
                if (retired != null) {
                    retired.run();
                }
                return;
            }
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " entity task failed", e);
                throw e;
            } finally {
                wrapper[0].onFinish();
            }
        }, delayTicks * TICK_MS, TimeUnit.MILLISECONDS);
        wrapper[0].attachHandle(handle);
        return wrapper[0];
    }

    @Override
    public @Nullable ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, @Nullable Runnable retired, long initialDelayTicks, long periodTicks) {
        var wrapper = new VeltisPaperScheduledTask[1];
        wrapper[0] = new VeltisPaperScheduledTask(plugin, null, true);
        TaskHandle handle = delegate.scheduleRepeating(() -> {
            if (!isEntityAlive()) {
                if (retired != null) {
                    retired.run();
                }
                wrapper[0].cancel();
                return;
            }
            wrapper[0].onStart();
            try {
                task.accept(wrapper[0]);
            } catch (Exception e) {
                LOG.log(Level.ERROR, "Plugin " + plugin.getName() + " entity repeating task failed", e);
                throw e;
            } finally {
                wrapper[0].onFinish();
            }
        }, initialDelayTicks * TICK_MS, periodTicks * TICK_MS, TimeUnit.MILLISECONDS);
        wrapper[0].attachHandle(handle);
        return wrapper[0];
    }

    private TaskHandle scheduleTask(Runnable run, long delayMs) {
        if (delayMs <= 0) {
            run.run();
            return null;
        }
        return delegate.schedule(run, delayMs, TimeUnit.MILLISECONDS);
    }
}
