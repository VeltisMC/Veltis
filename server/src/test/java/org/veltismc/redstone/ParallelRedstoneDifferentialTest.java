package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Differential test: the same deterministic, fully specified circuit is built twice -
 * once on a forked server with {@code -Dveltis.parallelRedstone=false} (the vanilla
 * serial drain) and once with region-parallel dispatch - and every probed position of
 * the resulting worlds must be byte-identical, first for the settled circuit and then
 * again after two forced cache invalidations (a wire removed and restored, cutting and
 * rebuilding the propagation chain).
 *
 * <p>Each world contains two disjoint clusters (12 repeaters, a wire row over their
 * outputs, a north spur and a fence pair each) whose pending ticks sit in chunks (2,2)
 * and (5,4): distance 3, so the serial server drains them in two phases while the
 * parallel server must split them into two regions and dispatch them concurrently.
 * The serial fingerprints are additionally compared against values derived from the
 * circuit layout itself, so the test cannot pass vacuously if both worlds degrade in
 * the same way.
 */
public class ParallelRedstoneDifferentialTest {

    private static RedstoneServerHarness serial;
    private static RedstoneServerHarness parallel;
    private static int serialY;
    private static int parallelY;

    /** Repeater probe order: powered first, so a live row reads uniformly. */
    private static final String[] REPEATER_CANDIDATES = {
        "minecraft:repeater[powered=true]", "minecraft:repeater[powered=false]"
    };
    /** Wire probe order: every power level, then air as the removal witness. */
    private static final String[] WIRE_CANDIDATES = wireCandidates();
    /** Fence probe order: all 16 connection masks, then air. */
    private static final String[] FENCE_CANDIDATES = fenceCandidates();

    @BeforeAll
    static void bootBothHarnesses() throws Exception {
        serial = RedstoneServerHarness.start("diffSerial", false, "-Dveltis.parallelRedstone=false");
        serialY = configure(serial);
        parallel = RedstoneServerHarness.start("diffParallel", false, "-Dveltis.parallelRedstoneStats=1");
        parallelY = configure(parallel);
        assertEquals(serialY, parallelY,
                "identical flat generators must place the circuit layer at the same Y");
    }

    @AfterAll
    static void shutdown() {
        if (serial != null) {
            serial.close();
        }
        if (parallel != null) {
            parallel.close();
        }
    }

    /** Forceloads the full circuit area (chunks 0..9 x 0..7), freezes and drains. */
    private static int configure(RedstoneServerHarness server) throws Exception {
        boolean chunkCoords = server.detectForceloadChunkCoords();
        server.configureForceloadChunkCoords(chunkCoords);
        server.forceLoadChunks(0, 0, 9, 7);
        server.awaitLoaded(2, 2, 2);
        server.awaitLoaded(84, 2, 65);
        int surface = server.highestNonAir(2, 2, 5, -64);
        assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + server.tail(1500));
        int circuitY = surface + 1;
        server.freeze();
        server.step(4);
        return circuitY;
    }

    // --------------------------------------------------------------- rounds

    @Test
    public void serialAndParallelWorldsRemainIdenticalThroughCircuitMutation() throws Exception {
        // Round 1: build both worlds command-for-command, then collect one parallel
        // phase (24 pendings over two regions) against the serial drain.
        buildCluster(serial, serialY, 36, 34);
        buildCluster(serial, serialY, 84, 66);
        buildCluster(parallel, parallelY, 36, 34);
        buildCluster(parallel, parallelY, 84, 66);

        int serialMark = serial.mark();
        int parallelMark = parallel.mark();
        serial.step(6);
        parallel.step(6);
        String serialStats = serial.textSinceQuiet(serialMark, 800, 15_000);
        String parallelStats = parallel.textSinceQuiet(parallelMark, 800, 15_000);
        assertFalse(serialStats.contains("block-tick phases"),
                "serial harness must run with parallel redstone disabled; window:\n" + serialStats);
        assertTrue(parallelStats.contains("block-tick phases"),
                "stats line missing on the parallel harness; window:\n" + parallelStats);
        assertTrue(statSum(parallelStats, "parallel") >= 1,
                "two regions 3 chunks apart should dispatch in parallel; window:\n" + parallelStats);

        String serialRound1 = fingerprint(serial, serialY, false);
        assertEquals(expectedRound1(), serialRound1,
                "serial world must match the circuit's known semantics");
        assertEquals(serialRound1, fingerprint(parallel, parallelY, false),
                "round 1: parallel dispatch must yield the identical world state");

        // Round 2: remove the middle spur wire of cluster B. The removal crosses the
        // propagator boundary (cache invalidated) and orphans the last spur wire; both
        // servers must recompute exactly the same powers.
        serial.cmd("setblock 84 " + serialY + " 62 minecraft:air");
        parallel.cmd("setblock 84 " + parallelY + " 62 minecraft:air");
        serial.step(4);
        parallel.step(4);
        String serialRound2 = fingerprint(serial, serialY, true);
        assertEquals(expectedRound2(), serialRound2,
                "serial world after the spur cut must match the circuit's known semantics");
        assertEquals(serialRound2, fingerprint(parallel, parallelY, true),
                "round 2: parallel dispatch must yield the identical world state");

        // Round 3: restore the wire; the chain re-powers to 14/13/12 exactly as before.
        serial.cmd("setblock 84 " + serialY + " 62 minecraft:redstone_wire");
        parallel.cmd("setblock 84 " + parallelY + " 62 minecraft:redstone_wire");
        serial.step(4);
        parallel.step(4);
        String serialRound3 = fingerprint(serial, serialY, false);
        assertEquals(expectedRound1(), serialRound3,
                "serial world after the restore must return to the original semantics");
        assertEquals(serialRound3, fingerprint(parallel, parallelY, false),
                "round 3: parallel dispatch must yield the identical world state");

        for (RedstoneServerHarness server : new RedstoneServerHarness[] {serial, parallel}) {
            String all = server.tail(500_000);
            assertFalse(all.contains("from multiple threads"),
                    "ThreadingDetector fired; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
            assertFalse(all.contains("Thread dumps"),
                    "ThreadingDetector fired; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
        }
    }

    // ------------------------------------------------------------- circuits

    /**
     * One cluster: fence pair at {@code sourceZ + 2}, wire row (12) at
     * {@code sourceZ - 2} directly over the repeater outputs, a three-wire spur north
     * of the row's first cell, repeaters (12) at {@code sourceZ - 1} facing south, and
     * sources at {@code sourceZ} placed last so all 12 pending ticks come due in a
     * single collect.
     */
    private static void buildCluster(RedstoneServerHarness server, int y, int x0, int sourceZ)
            throws IOException {
        server.cmd("setblock " + (x0 + 6) + " " + y + " " + (sourceZ + 2) + " minecraft:oak_fence");
        server.cmd("setblock " + (x0 + 7) + " " + y + " " + (sourceZ + 2) + " minecraft:oak_fence");
        for (int x = x0; x <= x0 + 11; x++) {
            server.cmd("setblock " + x + " " + y + " " + (sourceZ - 2) + " minecraft:redstone_wire");
        }
        for (int spur = 3; spur <= 5; spur++) {
            server.cmd("setblock " + x0 + " " + y + " " + (sourceZ - spur) + " minecraft:redstone_wire");
        }
        for (int x = x0; x <= x0 + 11; x++) {
            server.cmd("setblock " + x + " " + y + " " + (sourceZ - 1) + " minecraft:repeater[facing=south]");
        }
        for (int x = x0; x <= x0 + 11; x++) {
            server.cmd("setblock " + x + " " + y + " " + sourceZ + " minecraft:redstone_block");
        }
    }

    // ---------------------------------------------------------- fingerprints

    /**
     * Position-by-position state probe of both clusters in a fixed order. {@code clusterBCut}
     * selects the round's expectation for cluster B's spur (rounds 1 and 3: intact, round 2:
     * cut), which drives the expected-first probe order below.
     */
    private static String fingerprint(RedstoneServerHarness server, int y, boolean clusterBCut)
            throws IOException {
        StringBuilder fp = new StringBuilder();
        appendClusterFingerprint(fp, server, y, 36, 34, false);
        appendClusterFingerprint(fp, server, y, 84, 66, clusterBCut);
        return fp.toString();
    }

    private static void appendClusterFingerprint(StringBuilder fp, RedstoneServerHarness server, int y,
            int x0, int sourceZ, boolean spurCut) throws IOException {
        for (int x = x0; x <= x0 + 11; x++) {
            fp.append(probe(server, y, x, sourceZ - 1, expectedRepeater(), REPEATER_CANDIDATES));
        }
        for (int x = x0; x <= x0 + 11; x++) {
            fp.append(probe(server, y, x, sourceZ - 2, expectedRowWire(), WIRE_CANDIDATES));
        }
        fp.append(probe(server, y, x0, sourceZ - 3, expectedSpur(3, spurCut), WIRE_CANDIDATES));
        fp.append(probe(server, y, x0, sourceZ - 4, expectedSpur(4, spurCut), WIRE_CANDIDATES));
        fp.append(probe(server, y, x0, sourceZ - 5, expectedSpur(5, spurCut), WIRE_CANDIDATES));
        fp.append(probe(server, y, x0 + 6, sourceZ + 2, expectedFence(0), FENCE_CANDIDATES));
        fp.append(probe(server, y, x0 + 7, sourceZ + 2, expectedFence(1), FENCE_CANDIDATES));
    }

    /**
     * Probes the layout's expected state first (one command in the happy path), then falls
     * back to the full candidate walk on divergence so the fingerprint still pinpoints the
     * actual state. The result is the observed state either way; the assertions compare it
     * against both the layout-derived oracle and the other server's fingerprint, so an
     * unexpected state cannot pass.
     */
    private static String probe(RedstoneServerHarness server, int y, int x, int z,
            String expected, String[] candidates) throws IOException {
        if (server.blockMatches(x + " " + y + " " + z + " " + expected)) {
            return expected;
        }
        for (String candidate : candidates) {
            if (candidate.equals(expected)) {
                continue; // just probed
            }
            if (server.blockMatches(x + " " + y + " " + z + " " + candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("no candidate matched at " + x + "," + y + "," + z
                + " (expected " + expected + "); tail:\n" + server.tail(1500));
    }

    // ------------------------------------------------- expected-state helpers
    //
    // Single source of truth for each probed position's layout-derived state; consumed by
    // both the fingerprint's expected-first probe order and the round oracles below, so the
    // probe order and the oracle can never drift apart.

    private static String expectedRepeater() {
        return "minecraft:repeater[powered=true]";
    }

    private static String expectedRowWire() {
        return "minecraft:redstone_wire[power=15]";
    }

    /** Spur wire at {@code depth} (3..5) north of the row; cutting depth 4 orphans depth 5. */
    private static String expectedSpur(int depth, boolean spurCut) {
        if (depth == 4) {
            return spurCut ? "minecraft:air" : "minecraft:redstone_wire[power=13]";
        }
        if (depth == 5) {
            return spurCut ? "minecraft:redstone_wire[power=0]" : "minecraft:redstone_wire[power=12]";
        }
        return "minecraft:redstone_wire[power=14]";
    }

    /** Fence slot 0 ({@code x0 + 6}) connects east; slot 1 ({@code x0 + 7}) connects west. */
    private static String expectedFence(int slot) {
        return slot == 0
                ? "minecraft:oak_fence[north=false,east=true,south=false,west=false]"
                : "minecraft:oak_fence[north=false,east=false,south=false,west=true]";
    }

    /**
     * Expected fingerprint derived from the layout: every repeater on, every row wire
     * fed 15 by its own repeater, the spur stepping 14/13/12, and the fence pair
     * connected only to each other.
     */
    private static String expectedRound1() {
        StringBuilder expected = new StringBuilder();
        appendExpectedCluster(expected, false);
        appendExpectedCluster(expected, false);
        return expected.toString();
    }

    /** Round 2 expects cluster B's spur cut: 14, air, orphaned 0. */
    private static String expectedRound2() {
        StringBuilder expected = new StringBuilder();
        appendExpectedCluster(expected, false);
        appendExpectedCluster(expected, true);
        return expected.toString();
    }

    private static void appendExpectedCluster(StringBuilder expected, boolean spurCut) {
        for (int i = 0; i < 12; i++) {
            expected.append(expectedRepeater());
        }
        for (int i = 0; i < 12; i++) {
            expected.append(expectedRowWire());
        }
        expected.append(expectedSpur(3, spurCut)); // sourceZ - 3
        expected.append(expectedSpur(4, spurCut)); // sourceZ - 4 (air when cut)
        expected.append(expectedSpur(5, spurCut)); // sourceZ - 5 (orphaned when cut)
        expected.append(expectedFence(0));
        expected.append(expectedFence(1));
    }

    // -------------------------------------------------------------- helpers

    private static long statSum(String text, String field) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "=(\\d+)").matcher(text);
        long sum = 0;
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static String[] wireCandidates() {
        String[] powers = new String[17];
        for (int i = 0; i < 16; i++) {
            powers[i] = "minecraft:redstone_wire[power=" + (15 - i) + "]";
        }
        powers[16] = "minecraft:air";
        return powers;
    }

    private static String[] fenceCandidates() {
        String[] combos = new String[17];
        for (int mask = 0; mask < 16; mask++) {
            combos[mask] = "minecraft:oak_fence[north=" + ((mask & 1) != 0)
                    + ",east=" + ((mask & 2) != 0)
                    + ",south=" + ((mask & 4) != 0)
                    + ",west=" + ((mask & 8) != 0) + "]";
        }
        combos[16] = "minecraft:air";
        return combos;
    }
}
