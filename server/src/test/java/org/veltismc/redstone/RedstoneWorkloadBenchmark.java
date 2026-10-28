package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Redstone workload benchmark: sixteen repeater-ring clocks in four disjoint clusters
 * (each cluster at least three chunks from the next), running on a live (unfrozen) server.
 *
 * <p>Reports:
 * <ul>
 *   <li>{@code /tick query} percentiles (avg, p50, p95, p99 ms/tick) over the measurement
 *       window,</li>
 *   <li>JFR execution-sample distribution: per-thread sample counts and the share of samples
 *       inside {@code alternate.current} frames.</li>
 * </ul>
 *
 * <p>Run the same test with {@code -Dveltis.parallelRedstone=false} for the serial baseline:
 * the property is forwarded verbatim to the forked server child.
 *
 * <p>Gated behind {@code -PveltisBench} (which sets {@code veltis.bench=true}), so normal test
 * runs skip it.
 */
@EnabledIfSystemProperty(named = "veltis.bench", matches = "true")
public class RedstoneWorkloadBenchmark {

    private static final int[][] CLUSTERS = { { 8, 8 }, { 8, 72 }, { 72, 8 }, { 72, 72 } };

    @Test
    public void benchmarkDisjointClockClusters() throws Exception {
        String mode = System.getProperty("veltis.parallelRedstone", "true");
        System.out.println("BENCH_mode parallelRedstone=" + mode);

        // Optional knobs forwarded verbatim to the child so serial / 1-worker /
        // N-worker runs are selectable from the Gradle command line
        // (-PveltisParallelRedstoneThreads=N, -PveltisParallelRedstoneStats=N).
        List<String> childArgs = new ArrayList<>();
        childArgs.add("-Dveltis.parallelRedstone=" + mode);
        String threads = System.getProperty("veltis.parallelRedstoneThreads");
        if (threads != null) {
            childArgs.add("-Dveltis.parallelRedstoneThreads=" + threads);
            System.out.println("BENCH_mode parallelRedstoneThreads=" + threads);
        }
        String stats = System.getProperty("veltis.parallelRedstoneStats");
        if (stats != null) {
            childArgs.add("-Dveltis.parallelRedstoneStats=" + stats);
            System.out.println("BENCH_mode parallelRedstoneStats=" + stats);
        }

        RedstoneServerHarness h = RedstoneServerHarness.start("bench", true,
                childArgs.toArray(new String[0]));
        // Grid dimension per cluster: 2 (default) = 4 rings/cluster, 16 total — a
        // small workload that hovers around the dispatch threshold; 4 = 16 rings
        // per cluster, 64 total (~32 scheduled ticks per game tick spread over the
        // four clusters), which keeps every drain above the threshold so the
        // parallel path is exercised continuously.
        int grid = Integer.getInteger("veltis.bench.grid", 2);
        System.out.println("BENCH_mode grid=" + grid);
        try {
            boolean chunkCoords = h.detectForceloadChunkCoords();
            h.configureForceloadChunkCoords(chunkCoords);
            h.forceLoadChunks(0, 0, 5, 5);
            h.awaitLoaded(2, 2, 2);
            // Circuit layer = surface + 1 (custom flat layers stack from y=-64, so y=2
            // would be deep underground air with nothing to support the repeaters).
            int surface = h.highestNonAir(2, 2, 5, -64);
            assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
            int y = surface + 1;

            // Build a grid of rings per cluster (pitch 4; clusters are 64 apart, so
            // even a 4x4 grid never bridges two clusters).
            for (int[] cluster : CLUSTERS) {
                int cx0 = cluster[0];
                int cz0 = cluster[1];
                for (int dx = 0; dx < grid; dx++) {
                    for (int dz = 0; dz < grid; dz++) {
                        buildRing(h, y, cx0 + dx * 4, cz0 + dz * 4);
                    }
                }
            }
            // Inject every ring: place all sources beside each ring's input wire, hold
            // them across the repeater's +2gt schedule (~5 ticks), then retract. A
            // same-tick pulse would leave the ring start timing-dependent.
            for (int[] cluster : CLUSTERS) {
                for (int dx = 0; dx < grid; dx++) {
                    for (int dz = 0; dz < grid; dz++) {
                        int cx = cluster[0] + dx * 4 + 2;
                        int cz = cluster[1] + dz * 4;
                        h.cmd("setblock " + cx + " " + y + " " + (cz - 1) + " minecraft:redstone_block");
                    }
                }
            }
            Thread.sleep(250);
            for (int[] cluster : CLUSTERS) {
                for (int dx = 0; dx < grid; dx++) {
                    for (int dz = 0; dz < grid; dz++) {
                        int cx = cluster[0] + dx * 4 + 2;
                        int cz = cluster[1] + dz * 4;
                        h.cmd("setblock " + cx + " " + y + " " + (cz - 1) + " minecraft:air");
                    }
                }
            }

            // Liveness gate: each cluster's injection-point wire also serves as that
            // ring's third-repeater output, so it must be seen at full power at least
            // once (it is high 2 ticks out of every 6). Without this, the benchmark
            // could silently measure an idle server.
            for (int[] cluster : CLUSTERS) {
                int gx = cluster[0] + 2;
                int gz = cluster[1];
                boolean sawHigh = false;
                for (int i = 0; i < 30 && !sawHigh; i++) {
                    int mark = h.mark();
                    String token = "BENCHHIGH_" + cluster[0] + "_" + cluster[1] + "_" + i;
                    h.cmd("execute if block " + gx + " " + y + " " + gz
                            + " minecraft:redstone_wire[power=15] run say " + token);
                    sawHigh = h.await(mark, "\\[Server\\] " + Pattern.quote(token), 400);
                }
                assertTrue(sawHigh, "cluster (" + cluster[0] + "," + cluster[1]
                        + ") ring wire never reached power 15; tail:\n" + h.tail(1500));
            }

            String before = h.tickQuery();
            System.out.println("BENCH_tickQuery_before\n" + before);
            Thread.sleep(10_000);
            String after = h.tickQuery();
            System.out.println("BENCH_tickQuery_after\n" + after);
            printMspt("BENCH_mspt", after);
        } finally {
            h.close();
        }

        // The child has exited; bench.jfr is complete. Analyse sample distribution.
        Path jfr = h.home().resolve("bench.jfr");
        assertTrue(Files.exists(jfr), "JFR recording missing: " + jfr);
        analyseJfr(jfr);
    }

    /**
     * Closed ring of three repeaters — a two-repeater ring would latch both repeaters on,
     * the third preserves the travelling gap. Wires (cx,cz),(cx+2,cz),(cx,cz+1),(cx,cz+2),
     * (cx+2,cz+2); repeater (cx+1,cz) facing east (input = (cx+2,cz)), repeater (cx+1,cz+2)
     * facing west (input = (cx,cz+2)), repeater (cx+2,cz+1) facing south (input =
     * (cx+2,cz+2)). A pulse injected beside (cx+2,cz) circulates indefinitely.
     */
    private static void buildRing(RedstoneServerHarness h, int y, int cx, int cz) throws Exception {
        int[][] wires = { { cx, cz }, { cx + 2, cz }, { cx, cz + 1 }, { cx, cz + 2 }, { cx + 2, cz + 2 } };
        for (int[] w : wires) {
            h.cmd("setblock " + w[0] + " " + y + " " + w[1] + " minecraft:redstone_wire");
        }
        h.cmd("setblock " + (cx + 1) + " " + y + " " + cz + " minecraft:repeater[facing=east]");
        h.cmd("setblock " + (cx + 1) + " " + y + " " + (cz + 2) + " minecraft:repeater[facing=west]");
        h.cmd("setblock " + (cx + 2) + " " + y + " " + (cz + 1) + " minecraft:repeater[facing=south]");
    }

    private static void printMspt(String prefix, String tickQueryOutput) {
        Map<String, Double> values = new HashMap<>();
        Matcher m = Pattern.compile("(?i)(p50|p95|p99|avg|average)\\D{0,20}([0-9]+(?:\\.[0-9]+)?)")
                .matcher(tickQueryOutput);
        while (m.find()) {
            values.putIfAbsent(m.group(1).toLowerCase(), Double.parseDouble(m.group(2)));
        }
        System.out.println(prefix + " " + values);
    }

    private static void analyseJfr(Path jfr) throws Exception {
        long totalSamples = 0;
        long acFrames = 0;
        long mcFrames = 0;
        Map<String, Long> perThread = new HashMap<>();
        Map<String, Long> acMethods = new HashMap<>();
        try (RecordingFile file = new RecordingFile(jfr)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) {
                    continue;
                }
                totalSamples++;
                String thread = "?";
                RecordedThread sampled = event.getThread("sampledThread");
                if (sampled != null && sampled.getJavaName() != null) {
                    thread = sampled.getJavaName();
                }
                perThread.merge(thread, 1L, Long::sum);
                boolean inAc = false;
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    // RecordedFrame.getType() is the interpreter/JIT status; the declaring
                    // class lives in the method's type.
                    String cn = frame.getMethod() != null && frame.getMethod().getType() != null
                            ? frame.getMethod().getType().getName()
                            : "";
                    if (cn.startsWith("alternate.current")) {
                        acFrames++;
                        inAc = true;
                        acMethods.merge(cn + "." + frame.getMethod().getName(), 1L, Long::sum);
                    } else if (cn.startsWith("net.minecraft")) {
                        mcFrames++;
                    }
                }
                if (!inAc) {
                    // keep total only
                }
            }
        }
        System.out.println("BENCH_jfr totalSamples=" + totalSamples
                + " alternateCurrentFrames=" + acFrames
                + " minecraftFrames=" + mcFrames
                + " acFrameShare=" + (totalSamples > 0 ? String.format("%.4f", acFrames / (double) Math.max(1, acFrames + mcFrames)) : "n/a"));
        perThread.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> System.out.println("BENCH_jfr_thread " + e.getKey() + " " + e.getValue()));
        acMethods.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> System.out.println("BENCH_jfr_acFrame " + e.getKey() + " " + e.getValue()));
    }
}
