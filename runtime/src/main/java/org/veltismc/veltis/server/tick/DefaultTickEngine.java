package org.veltismc.veltis.server.tick;

import org.veltismc.veltis.server.metrics.MetricsSnapshot;
import org.veltismc.veltis.server.metrics.ServerMetrics;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class DefaultTickEngine implements TickEngine {

    private static final Logger LOG = System.getLogger(DefaultTickEngine.class.getName());
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long LAG_SPIKE_THRESHOLD_NS = 50_000_000L;
    private static final int TPS_WINDOW_SIZE = 100;

    private final AtomicLong targetTps;
    private final MetricsImpl metrics;
    private final CopyOnWriteArrayList<TickConsumer> consumers;
    private final AtomicBoolean running;
    private final AtomicLong tickNumber;
    private final RingBuffer tickDurations;

    private Thread tickThread;
    private long tickIntervalNs;

    public DefaultTickEngine() {
        this.targetTps = new AtomicLong(20);
        this.metrics = new MetricsImpl();
        this.consumers = new CopyOnWriteArrayList<>();
        this.running = new AtomicBoolean(false);
        this.tickNumber = new AtomicLong(0);
        this.tickDurations = new RingBuffer(TPS_WINDOW_SIZE);
        this.tickIntervalNs = NANOS_PER_SECOND / 20;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        tickIntervalNs = NANOS_PER_SECOND / targetTps.get();
        metrics.startedAt(System.nanoTime());
        tickThread = Thread.ofVirtual()
            .name("nova-tick-engine")
            .unstarted(this::tickLoop);
        tickThread.start();
        LOG.log(Level.INFO, "Tick engine started at {0} TPS", targetTps.get());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (tickThread != null) {
            tickThread.interrupt();
        }
        LOG.log(Level.INFO, "Tick engine stopped after {0} ticks", tickNumber.get());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int targetTps() {
        return (int) targetTps.get();
    }

    @Override
    public void targetTps(int tps) {
        if (tps < 1 || tps > 100) {
            throw new IllegalArgumentException("TPS must be between 1 and 100");
        }
        targetTps.set(tps);
        tickIntervalNs = NANOS_PER_SECOND / tps;
    }

    @Override
    public double currentTps() {
        return metrics.currentTps();
    }

    @Override
    public double averageMsp() {
        return metrics.averageMsp();
    }

    @Override
    public long currentTick() {
        return tickNumber.get();
    }

    @Override
    public ServerMetrics metrics() {
        return metrics;
    }

    @Override
    public TickMetrics tickMetrics() {
        return metrics;
    }

    @Override
    public Registration onTick(TickConsumer consumer) {
        consumers.add(consumer);
        return () -> consumers.remove(consumer);
    }

    private void tickLoop() {
        var lastTickTime = System.nanoTime();

        while (running.get() && !Thread.interrupted()) {
            var tickStart = System.nanoTime();
            var tick = tickNumber.getAndIncrement();
            var elapsedNs = tickStart - lastTickTime;
            lastTickTime = tickStart;

            tickDurations.add(elapsedNs);

            var currentTps = elapsedNs > 0
                ? (double) NANOS_PER_SECOND / elapsedNs
                : 20.0;
            var context = new TickContext(
                tick, tickStart, elapsedNs, currentTps);

            metrics.recordTick(context, tickDurations.average());

            for (var consumer : consumers) {
                try {
                    consumer.accept(context);
                } catch (Exception e) {
                    LOG.log(Level.ERROR,
                        "Tick consumer threw on tick " + tick, e);
                }
            }

            var tickDuration = System.nanoTime() - tickStart;
            var sleepNs = tickIntervalNs - tickDuration;

            if (sleepNs > 0) {
                LockSupport.parkNanos(sleepNs);
            } else {
                if (tickDuration > LAG_SPIKE_THRESHOLD_NS) {
                    metrics.recordLagSpike(tick, tickDuration);
                }
            }
        }
    }

    private static final class RingBuffer {
        private final long[] buffer;
        private final int size;
        private int index;
        private int count;
        private long sum;

        RingBuffer(int size) {
            this.buffer = new long[size];
            this.size = size;
            this.index = 0;
            this.count = 0;
            this.sum = 0;
        }

        void add(long value) {
            sum += value;
            if (count < size) {
                buffer[index] = value;
                count++;
            } else {
                sum -= buffer[index];
                buffer[index] = value;
            }
            index = (index + 1) % size;
        }

        long average() {
            return count > 0 ? sum / count : 0;
        }
    }

    private static final class MetricsImpl
        implements ServerMetrics, TickMetrics {

        private volatile double currentTps;
        private volatile double currentMsp;
        private volatile double averageMsp;
        private volatile double minMsp;
        private volatile double maxMsp;
        private volatile long currentTick;
        private volatile int playerCount;
        private volatile int maxPlayers;
        private volatile int worldCount;
        private volatile long startedAtNanos;
        private volatile TickContext lastContext;

        private final AtomicLong lagSpikeCount;
        private final AtomicLong lagSpikeTotalNs;
        private final AtomicLong lastLagSpikeTick;
        private final AtomicLong totalTickDuration;

        MetricsImpl() {
            this.lagSpikeCount = new AtomicLong(0);
            this.lagSpikeTotalNs = new AtomicLong(0);
            this.lastLagSpikeTick = new AtomicLong(-1);
            this.totalTickDuration = new AtomicLong(0);
            this.minMsp = Double.MAX_VALUE;
            this.maxMsp = 0.0;
            this.maxPlayers = 100;
        }

        void startedAt(long nanos) {
            this.startedAtNanos = nanos;
        }

        void recordTick(TickContext context, long avgNs) {
            this.currentTick = context.tickNumber();
            this.lastContext = context;
            this.currentTps = context.currentTps();
            this.currentMsp = context.elapsedMs();
            this.averageMsp = avgNs / 1_000_000.0;
            var ms = context.elapsedMs();
            if (ms < this.minMsp) this.minMsp = ms;
            if (ms > this.maxMsp) this.maxMsp = ms;
            this.totalTickDuration.addAndGet(context.elapsedNanos());
        }

        void recordLagSpike(long tick, long durationNs) {
            lagSpikeCount.incrementAndGet();
            lagSpikeTotalNs.addAndGet(durationNs);
            lastLagSpikeTick.set(tick);
        }

        public double currentTps() { return currentTps; }

        @Override
        public double tps() { return currentTps; }

        @Override
        public double msp() { return currentMsp; }

        @Override
        public double averageMsp() { return averageMsp; }

        @Override
        public double averageMs() { return averageMsp; }

        @Override
        public double minMs() { return minMsp; }

        @Override
        public double maxMs() { return maxMsp; }

        @Override
        public double minMsp() { return minMsp; }

        @Override
        public double maxMsp() { return maxMsp; }

        @Override
        public int lagSpikeCount() {
            return (int) lagSpikeCount.get();
        }

        @Override
        public long currentTick() { return currentTick; }

        @Override
        public int playerCount() { return playerCount; }

        @Override
        public void playerCount(int count) { this.playerCount = count; }

        @Override
        public int maxPlayers() { return maxPlayers; }

        @Override
        public void maxPlayers(int max) { this.maxPlayers = max; }

        @Override
        public int worldCount() { return worldCount; }

        @Override
        public void worldCount(int count) { this.worldCount = count; }

        @Override
        public long uptimeMs() {
            return startedAtNanos > 0
                ? (System.nanoTime() - startedAtNanos) / 1_000_000
                : 0;
        }

        @Override
        public TickContext currentContext() {
            return lastContext;
        }

        @Override
        public long totalTicks() { return currentTick; }

        @Override
        public long lagSpikeTotalNs() { return lagSpikeTotalNs.get(); }

        @Override
        public long lastLagSpikeTick() { return lastLagSpikeTick.get(); }

        @Override
        public long totalTickDurationNs() { return totalTickDuration.get(); }

        @Override
        public MetricsSnapshot snapshot() {
            return new MetricsSnapshot(
                currentTick,
                currentTps,
                averageMsp,
                minMsp,
                maxMsp,
                averageMsp,
                playerCount,
                maxPlayers,
                worldCount,
                uptimeMs(),
                (int) lagSpikeCount.get(),
                lastLagSpikeTick.get()
            );
        }
    }
}


