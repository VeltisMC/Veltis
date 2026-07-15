package org.veltismc.veltis.server.scheduler;

/**
 * Represents a task scheduled for execution by the {@link TaskScheduler}.
 *
 * <p>Provides status inspection and cancellation of a single scheduled
 * execution. Extended by {@link TaskHandle} for repeating tasks.
 */
public interface ScheduledTask {

    /**
     * Unique identifier for this scheduled task.
     */
    long id();

    /**
     * The runnable to execute.
     */
    Runnable runnable();

    /**
     * Current state of this scheduled task.
     */
    TaskState state();

    /**
     * Whether this task has been cancelled.
     */
    boolean cancelled();

    /**
     * Cancels this task. A cancelled task will not be executed.
     */
    void cancel();

    /**
     * The lifecycle state of a {@link ScheduledTask}.
     */
    enum TaskState {
        SCHEDULED,
        EXECUTING,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}


