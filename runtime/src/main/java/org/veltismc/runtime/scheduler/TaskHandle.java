package org.veltismc.runtime.scheduler;

/**
 * Extended task handle for repeating or delayed tasks.
 *
 * <p>Provides additional metadata about execution count, delay, and
 * period for tasks submitted to the {@link TaskScheduler}.
 */
public interface TaskHandle extends ScheduledTask {

    /**
     * Number of times this task has been executed so far.
     */
    long executionCount();

    /**
     * The initial delay in milliseconds before the first execution.
     */
    long delayMs();

    /**
     * The period in milliseconds between consecutive executions.
     * Zero indicates a one-shot task.
     */
    long periodMs();

    /**
     * Returns true if this task is a repeating task (period > 0).
     */
    boolean isRepeating();

    /**
     * Returns true if this task has been executed at least once.
     */
    boolean hasExecuted();
}


