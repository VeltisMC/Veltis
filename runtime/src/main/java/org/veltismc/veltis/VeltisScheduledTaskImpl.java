package org.veltismc.veltis;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

class VeltisScheduledTaskImpl implements ScheduledTask {

    private final Plugin plugin;
    private volatile org.veltismc.veltis.server.scheduler.TaskHandle handle;
    private final boolean repeating;
    private volatile ExecutionState state;

    VeltisScheduledTaskImpl(Plugin plugin, org.veltismc.veltis.server.scheduler.TaskHandle handle, boolean repeating) {
        this.plugin = plugin;
        this.handle = handle;
        this.repeating = repeating;
        if (handle != null) {
            updateState(handle);
        } else {
            this.state = ExecutionState.IDLE;
        }
    }

    void attachHandle(org.veltismc.veltis.server.scheduler.TaskHandle handle) {
        this.handle = handle;
        if (this.state == ExecutionState.IDLE) {
            updateState(handle);
        }
    }

    private void updateState(org.veltismc.veltis.server.scheduler.TaskHandle h) {
        switch (h.state()) {
            case SCHEDULED -> this.state = ExecutionState.IDLE;
            case EXECUTING -> this.state = ExecutionState.RUNNING;
            case COMPLETED -> this.state = ExecutionState.FINISHED;
            case CANCELLED -> this.state = ExecutionState.CANCELLED;
            case FAILED -> this.state = ExecutionState.CANCELLED;
        }
    }

    void onStart() { this.state = ExecutionState.RUNNING; }
    void onFinish() { if (!repeating) this.state = ExecutionState.FINISHED; else this.state = ExecutionState.IDLE; }

    @Override public @NotNull Plugin getOwningPlugin() { return plugin; }
    @Override public boolean isRepeatingTask() { return repeating; }

    @Override
    public @NotNull CancelledState cancel() {
        var h = this.handle;
        if (h == null) {
            this.state = ExecutionState.CANCELLED;
            return CancelledState.CANCELLED_BY_CALLER;
        }
        if (h.state() == org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.COMPLETED) {
            return CancelledState.ALREADY_EXECUTED;
        }
        if (h.state() == org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.CANCELLED) {
            if (state == ExecutionState.CANCELLED_RUNNING) return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
            return CancelledState.CANCELLED_ALREADY;
        }
        var wasRunning = h.state() == org.veltismc.veltis.server.scheduler.ScheduledTask.TaskState.EXECUTING;
        h.cancel();
        if (repeating && wasRunning) {
            this.state = ExecutionState.CANCELLED_RUNNING;
            return CancelledState.NEXT_RUNS_CANCELLED;
        }
        if (wasRunning) {
            return CancelledState.RUNNING;
        }
        this.state = ExecutionState.CANCELLED;
        return CancelledState.CANCELLED_BY_CALLER;
    }

    @Override public @NotNull ExecutionState getExecutionState() { return state; }
}
