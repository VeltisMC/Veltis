package org.veltismc.veltis.server.scheduler;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous task scheduler backed by virtual threads.
 *
 * <p>Supports one-shot delayed tasks and repeating tasks with a
 * fixed delay between executions. Each task runs on its own
 * virtual thread created via {@link Thread#ofVirtual()}.
 *
 * <p>All submitted tasks return a {@link TaskHandle} for status
 * inspection and cancellation.
 */
public interface TaskScheduler {

    /**
     * Schedules a one-shot task after the specified delay.
     *
     * @param runnable the task to execute
     * @param delay    the delay before execution
     * @param unit     the time unit of the delay
     * @return a handle for the scheduled task
     */
    TaskHandle schedule(Runnable runnable, long delay, TimeUnit unit);

    /**
     * Schedules a repeating task with a fixed delay between executions.
     *
     * @param runnable the task to execute
     * @param delay    the initial delay before first execution
     * @param period   the delay between consecutive executions
     * @param unit     the time unit for both delay and period
     * @return a handle for the scheduled task
     */
    TaskHandle scheduleRepeating(Runnable runnable, long delay, long period, TimeUnit unit);

    /**
     * Returns the number of currently scheduled (not completed or cancelled) tasks.
     */
    int pendingCount();

    /**
     * Returns an immutable snapshot of all scheduled and active tasks.
     */
    Collection<TaskHandle> pendingTasks();

    /**
     * Cancels all pending tasks and shuts down the scheduler.
     * Running tasks are interrupted.
     */
    void shutdown();

    /**
     * Returns true if the scheduler has been shut down.
     */
    boolean isShutdown();
}


