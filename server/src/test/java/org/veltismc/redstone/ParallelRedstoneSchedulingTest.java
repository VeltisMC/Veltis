package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Measurement-driven scheduling gates of {@link ParallelRedstone}. Each scenario builds a
 * controlled workload on the frozen server, steps exactly four game ticks (so the single
 * due collect at +2gt plus its empty neighbour phases fall inside one mark window) and
 * reads the {@code -Dveltis.parallelRedstoneStats=1} phase lines.
 *
 * <p>The classification gates are exhaustive and mutually exclusive
 * (parallel | serialSmall | serialSingleChunk | serialUnverified | serialSingleRegion),
 * so every scenario asserts {@code >=1} on the expected gate and {@code ==0} on all four
 * others, which pins the workload to exactly one path. Empty phases only ever increment
 * {@code serialSmall}, hence {@code serialSmall} is asserted with {@code >=1} only.
 * {@code serialUnverified} cannot fire on a live server (kept-in-memory border chunks
 * make every discovery window verifiable) and is covered by
 * {@link ParallelRedstoneRegionFinderTest} at the seam instead.
 *
 * <p>The threshold scenario boots a second server with
 * {@code -Dveltis.parallelRedstoneMinTicks=1000000}: the very same two-region workload
 * that parallelizes on the default threshold must take the serial path instead, proving
 * the property is honoured end to end without changing redstone behaviour.
 */
public class ParallelRedstoneSchedulingTest {

    private static RedstoneServerHarness h;
    /** Y of the circuit layer: one above the flat world's top solid layer. */
    private static int y;

    @BeforeAll
    static void boot() throws Exception {
        h = RedstoneServerHarness.start("sched", false, "-Dveltis.parallelRedstoneStats=1");
        boolean chunkCoords = h.detectForceloadChunkCoords();
        h.configureForceloadChunkCoords(chunkCoords);
        // Chunks (0,0)-(7,7) cover every scenario band and every 25-chunk discovery
        // window, so all windows are verifiable (see the note above the threshold
        // scenario for why an unloaded window cannot be built live).
        h.forceLoadChunks(0, 0, 7, 7);
        h.awaitLoaded(2, 2, 2);
        int surface = h.highestNonAir(2, 2, 5, -64);
        assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
        y = surface + 1;
        h.freeze();
        // Drain boot-time scheduled ticks so scenario windows start from empty state.
        h.step(4);
    }

    @AfterAll
    static void shutdown() {
        if (h != null) {
            h.close();
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Places a repeater row: repeaters at {@code sourceZ - 1} facing south (FACING is the
     * input side, so each source sits on its input), redstone-block sources at
     * {@code sourceZ} <b>last</b>. Setblock-placed repeaters never self-schedule, so the
     * source's placement is the neighbourChanged that schedules each diode: exactly one
     * scheduled tick per source, all due at +2gt, one collect.
     */
    private static void repeaterRow(RedstoneServerHarness server, int circuitY, int x0, int x1, int sourceZ)
            throws IOException {
        for (int x = x0; x <= x1; x++) {
            server.cmd("setblock " + x + " " + circuitY + " " + (sourceZ - 1) + " minecraft:repeater[facing=south]");
        }
        for (int x = x0; x <= x1; x++) {
            server.cmd("setblock " + x + " " + circuitY + " " + sourceZ + " minecraft:redstone_block");
        }
    }

    /** Steps {@code ticks} and returns all stats lines emitted inside that window. */
    private static String stepCollectingStats(int ticks) throws IOException, InterruptedException {
        int mark = h.mark();
        h.step(ticks);
        return h.textSinceQuiet(mark, 800, 15_000);
    }

    /** Sums one integer stats field over every phase line in the window. */
    private static long statSum(String text, String field) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "=(\\d+)").matcher(text);
        long sum = 0;
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static void assertStatsPresent(String stats) {
        assertTrue(stats.contains("block-tick phases"),
                "stats line missing (veltis.parallelRedstoneStats=1 not honoured?); window:\n" + stats);
    }

    private static void assertRowPowered(int x0, int x1, int repeaterZ) throws IOException {
        for (int x = x0; x <= x1; x++) {
            assertTrue(h.blockMatches(x + " " + y + " " + repeaterZ + " minecraft:repeater[powered=true]"),
                    "repeater (" + x + "," + repeaterZ + ") should be powered; tail:\n" + h.tail(1200));
        }
    }

    // ---------------------------------------------------------- A1: tiny set

    @Test
    public void tinyPendingSetRunsSerially() throws Exception {
        // 6 ticks in one chunk: below the threshold, must take serialSmall and can never
        // reach any later gate (asserted ==0).
        repeaterRow(h, y, 32, 37, 35);
        String stats = stepCollectingStats(4);
        assertStatsPresent(stats);
        assertTrue(statSum(stats, "serialSmall") >= 1, "phase should be serialSmall; window:\n" + stats);
        assertEquals(0, statSum(stats, "parallel"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleChunk"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialUnverified"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleRegion"), "window:\n" + stats);
        assertRowPowered(32, 37, 34);
    }

    // ------------------------------------------------- A2: single chunk, >=16

    @Test
    public void singleChunkNeverParallelizes() throws Exception {
        // 16 ticks, all in chunk (2,2): passes the min-tick gate, then the single-chunk
        // gate proves there is nothing to split.
        repeaterRow(h, y, 32, 47, 42);
        String stats = stepCollectingStats(4);
        assertStatsPresent(stats);
        assertTrue(statSum(stats, "serialSingleChunk") >= 1,
                "single-chunk phase should fall back to serial; window:\n" + stats);
        assertEquals(0, statSum(stats, "parallel"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialUnverified"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleRegion"), "window:\n" + stats);
        assertRowPowered(32, 47, 41);
    }

    // -------------------------------------------- A3: two adjacent chunks = 1

    @Test
    public void adjacentChunksFormOneRegion() throws Exception {
        // 16 ticks spanning chunks (2,3) and (3,3): clustering merges them (distance 1),
        // so parallel dispatch is impossible and serialSingleRegion is the only gate.
        repeaterRow(h, y, 40, 55, 50);
        String stats = stepCollectingStats(4);
        assertStatsPresent(stats);
        assertTrue(statSum(stats, "serialSingleRegion") >= 1,
                "adjacent chunks must merge into one region; window:\n" + stats);
        assertEquals(0, statSum(stats, "parallel"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialUnverified"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleChunk"), "window:\n" + stats);
        assertRowPowered(40, 55, 49);
    }

    // --------------------------------- A4: distant chunks -> parallel dispatch

    @Test
    public void distantPendingChunksParallelize() throws Exception {
        // 32 ticks in chunks (2,3) and (5,3): Chebyshev distance 3 keeps them apart, both
        // windows stay inside the forceloaded area, no propagators link them => 2 regions.
        repeaterRow(h, y, 32, 39, 58);
        repeaterRow(h, y, 80, 87, 58);
        String stats = stepCollectingStats(4);
        assertStatsPresent(stats);
        assertTrue(statSum(stats, "parallel") >= 1,
                "two disjoint regions should dispatch in parallel; window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleRegion"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialUnverified"), "window:\n" + stats);
        assertEquals(0, statSum(stats, "serialSingleChunk"), "window:\n" + stats);
        assertRowPowered(32, 39, 57);
        assertRowPowered(80, 87, 57);
    }

    // A5 (unloaded window -> serialUnverified) has no live-server scenario: a forceloaded
    // area's neighbours stay in memory as FULL border chunks (getChunkNow returns them
    // even though /execute if loaded reports false, because hasChunk checks ticket level),
    // so every 25-chunk discovery window around live pendings is verifiable by construction.
    // ParallelRedstoneRegionFinderTest covers the fallback deterministically at the seam:
    // unloadedWindowChunkFailsClosed, unloadedWindowChunkInsideFloodFailsClosed and 40
    // randomized worlds with unloaded chunks, each asserting computeRegions returns null.

    // ------------------------------------------------------- threshold property

    @Test
    public void minTicksThresholdForcesSerialDispatch() throws Exception {
        RedstoneServerHarness hb = null;
        try {
            hb = RedstoneServerHarness.start("schedThreshold", false,
                    "-Dveltis.parallelRedstoneStats=1", "-Dveltis.parallelRedstoneMinTicks=1000000");
            boolean chunkCoords = hb.detectForceloadChunkCoords();
            hb.configureForceloadChunkCoords(chunkCoords);
            hb.forceLoadChunks(0, 0, 7, 7);
            hb.awaitLoaded(2, 2, 2);
            int surface = hb.highestNonAir(2, 2, 5, -64);
            assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + hb.tail(1500));
            int by = surface + 1;
            hb.freeze();
            hb.step(4);

            // Exactly the workload of distantPendingChunksParallelize (32 pending ticks in
            // two far-apart chunks): parallel on the default threshold of 16, but here the
            // configured threshold is 1000000, so the small-set gate must take it serial.
            repeaterRow(hb, by, 32, 39, 58);
            repeaterRow(hb, by, 80, 87, 58);
            int mark = hb.mark();
            hb.step(4);
            String stats = hb.textSinceQuiet(mark, 800, 15_000);
            assertStatsPresent(stats);
            assertTrue(statSum(stats, "serialSmall") >= 1,
                    "raised threshold must keep the phase serial; window:\n" + stats);
            assertEquals(0, statSum(stats, "parallel"), "window:\n" + stats);
            assertEquals(0, statSum(stats, "serialSingleChunk"), "window:\n" + stats);
            assertEquals(0, statSum(stats, "serialUnverified"), "window:\n" + stats);
            assertEquals(0, statSum(stats, "serialSingleRegion"), "window:\n" + stats);

            // Behaviour is unchanged on the serial path: both clusters still fire.
            for (int x = 32; x <= 39; x++) {
                assertTrue(hb.blockMatches(x + " " + by + " " + 57 + " minecraft:repeater[powered=true]"),
                        "repeater (" + x + ",57) should be powered; tail:\n" + hb.tail(1200));
            }
            for (int x = 80; x <= 87; x++) {
                assertTrue(hb.blockMatches(x + " " + by + " " + 57 + " minecraft:repeater[powered=true]"),
                        "repeater (" + x + ",57) should be powered; tail:\n" + hb.tail(1200));
            }
        } finally {
            if (hb != null) {
                hb.close();
            }
        }
    }

    // ----------------------------------------------------- threshold parsing

    @Test
    public void parseMinTicksValidatesConfiguration() {
        // Absent, zero and non-positive values fall back to the default of 16;
        // positive values pass through untouched.
        assertEquals(16, ParallelRedstone.parseMinTicks(null, 16), "absent property must fall back");
        assertEquals(16, ParallelRedstone.parseMinTicks(0, 16), "zero must fall back");
        assertEquals(16, ParallelRedstone.parseMinTicks(-1, 16), "negative must fall back");
        assertEquals(16, ParallelRedstone.parseMinTicks(Integer.MIN_VALUE, 16), "negative must fall back");
        assertEquals(1, ParallelRedstone.parseMinTicks(1, 16), "1 is a valid threshold");
        assertEquals(1_000_000, ParallelRedstone.parseMinTicks(1_000_000, 16), "valid value must pass through");
    }
}
