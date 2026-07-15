package org.veltismc.veltis.server.metrics;

/**
 * JVM runtime metrics for monitoring server resource usage.
 *
 * <p>Provides access to memory, thread, and CPU metrics using
 * JMX MBeans. CPU load requires {@code com.sun.management.OperatingSystemMXBean}
 * and returns -1.0 if unavailable.
 */
public interface RuntimeMetrics {

    /**
     * Returns the amount of used heap memory in bytes.
     */
    long usedMemory();

    /**
     * Returns the maximum heap memory in bytes.
     */
    long maxMemory();

    /**
     * Returns the amount of free heap memory in bytes.
     */
    long freeMemory();

    /**
     * Returns the total heap memory currently committed in bytes.
     */
    long totalMemory();

    /**
     * Returns the heap memory usage as a percentage (0.0 to 1.0).
     */
    double memoryUsagePercent();

    /**
     * Returns the number of live daemon threads.
     */
    int daemonThreadCount();

    /**
     * Returns the total number of threads (daemon + non-daemon).
     */
    int totalThreadCount();

    /**
     * Returns the peak thread count since the JVM started.
     */
    int peakThreadCount();

    /**
     * Returns the current JVM CPU load as a fraction (0.0 to 1.0),
     * or -1.0 if the platform MBean is not available.
     */
    double cpuLoad();

    /**
     * Returns the number of available processors.
     */
    int availableProcessors();

    /**
     * Returns the uptime of the JVM in milliseconds.
     */
    long jvmUptimeMs();

    /**
     * Returns the number of seconds since the JVM started.
     */
    long jvmUptimeSeconds();
}


