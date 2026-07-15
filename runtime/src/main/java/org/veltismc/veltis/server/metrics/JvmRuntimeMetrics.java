package org.veltismc.veltis.server.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * JVM-backed runtime metrics.
 */
public final class JvmRuntimeMetrics implements RuntimeMetrics {

    private final Runtime runtime;
    private final ThreadMXBean threads;
    private final java.lang.management.RuntimeMXBean runtimeBean;

    public JvmRuntimeMetrics() {
        this.runtime = Runtime.getRuntime();
        this.threads = ManagementFactory.getThreadMXBean();
        this.runtimeBean = ManagementFactory.getRuntimeMXBean();
    }

    @Override
    public long usedMemory() {
        return totalMemory() - freeMemory();
    }

    @Override
    public long maxMemory() {
        return runtime.maxMemory();
    }

    @Override
    public long freeMemory() {
        return runtime.freeMemory();
    }

    @Override
    public long totalMemory() {
        return runtime.totalMemory();
    }

    @Override
    public double memoryUsagePercent() {
        var max = maxMemory();
        return max <= 0 ? 0 : (double) usedMemory() / max;
    }

    @Override
    public int daemonThreadCount() {
        return threads.getDaemonThreadCount();
    }

    @Override
    public int totalThreadCount() {
        return threads.getThreadCount();
    }

    @Override
    public int peakThreadCount() {
        return threads.getPeakThreadCount();
    }

    @Override
    public double cpuLoad() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return sunOs.getCpuLoad();
        }
        return -1.0;
    }

    @Override
    public int availableProcessors() {
        return runtime.availableProcessors();
    }

    @Override
    public long jvmUptimeMs() {
        return runtimeBean.getUptime();
    }

    @Override
    public long jvmUptimeSeconds() {
        return jvmUptimeMs() / 1000;
    }
}
