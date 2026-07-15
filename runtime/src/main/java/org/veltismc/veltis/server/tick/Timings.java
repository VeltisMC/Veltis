package org.veltismc.veltis.server.tick;

import java.lang.System.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Timings {

    private static final Logger LOG = System.getLogger("VeltisMC.Timing");
    private static final ConcurrentHashMap<String, AtomicLong> ACCUMULATORS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ACTIVE_TIMERS = new ConcurrentHashMap<>();
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L; // 5 seconds
    private static final AtomicLong lastReportNs = new AtomicLong(System.nanoTime());

    private Timings() {}

    public static AutoCloseable start(String name) {
        var startNs = System.nanoTime();
        var key = name.intern();
        return () -> {
            var elapsed = System.nanoTime() - startNs;
            ACCUMULATORS.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(elapsed);
            checkReport();
        };
    }

    public static void record(String name, long elapsedNs) {
        ACCUMULATORS.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(elapsedNs);
        checkReport();
    }

    private static void checkReport() {
        var now = System.nanoTime();
        var last = lastReportNs.get();
        if (now - last >= REPORT_INTERVAL_NS && lastReportNs.compareAndSet(last, now)) {
            for (var entry : ACCUMULATORS.entrySet()) {
                var totalNs = entry.getValue().getAndSet(0);
                if (totalNs > 0) {
                    var totalMs = totalNs / 1_000_000L;
                    if (totalMs >= 1) {
                        LOG.log(System.Logger.Level.INFO, "[VeltisMC Timing] {0}: {1}ms", entry.getKey(), totalMs);
                    }
                }
            }
        }
    }
}
