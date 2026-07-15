package org.veltismc.veltis.server.tick;

import org.veltismc.veltis.server.metrics.ServerMetrics;

/**
 * The server tick engine drives the main game loop at a configurable
 * target TPS (default 20).
 *
 * <p>Consumers register via {@link #onTick(TickConsumer)} and receive
 * a {@link TickContext} on every tick. The engine runs on a single
 * virtual thread and uses {@link java.util.concurrent.locks.LockSupport#parkNanos}
 * for precise tick timing.
 */
public interface TickEngine {

    /**
     * Starts the tick loop on a virtual thread.
     */
    void start();

    /**
     * Stops the tick loop and interrupts the tick thread.
     */
    void stop();

    /**
     * Returns true if the tick loop is currently running.
     */
    boolean isRunning();

    /**
     * Returns the configured target TPS.
     */
    int targetTps();

    /**
     * Sets the target TPS. Must be between 1 and 100.
     *
     * @param tps the new target ticks per second
     * @throws IllegalArgumentException if tps is out of range
     */
    void targetTps(int tps);

    /**
     * Returns the current instantaneous TPS.
     */
    double currentTps();

    /**
     * Returns the average milliseconds per tick over the rolling window.
     */
    double averageMsp();

    /**
     * Returns the current tick number.
     */
    long currentTick();

    /**
     * Returns the aggregated server metrics.
     */
    ServerMetrics metrics();

    /**
     * Returns the detailed tick metrics.
     */
    TickMetrics tickMetrics();

    /**
     * Registers a consumer to be called on every tick.
     *
     * @param consumer the tick consumer
     * @return a registration handle for cancellation
     */
    Registration onTick(TickConsumer consumer);

    /**
     * Receives a {@link TickContext} on each server tick.
     */
    @FunctionalInterface
    interface TickConsumer {
        void accept(TickContext context);
    }

    /**
     * Handle for cancelling a tick consumer registration.
     */
    interface Registration {
        void cancel();
    }
}


