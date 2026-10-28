package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Multi-threaded execution of the parallel phase on a live forked server. Every
 * scenario forces 32 scheduled ticks to come due in one collect across two disjoint
 * regions (chunks 2 and 5), so the region workers really run concurrently, while the
 * workloads deliberately touch shared structures: falling-sand entity creation goes
 * through the deferred entity-add queue, piston extension consumes level randomness
 * for its sounds, and torch shutoff runs the synchronized burnout bookkeeping.
 *
 * <p>Each scenario uses its own z band, so the tests are order-independent, and all
 * assertions are end-state invariants (never timing based). The ThreadingDetector in
 * the vanilla code paths plus the PalettedContainer guards would loudly report any
 * illegal concurrent access with the markers asserted absent at the end.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ParallelRedstoneConcurrencyTest {

    private static RedstoneServerHarness h;
    /** Y of the circuit layer: one above the flat world's top solid layer. */
    private static int y;
    /** Game time right after the boot drain; the last test checks the exact delta. */
    private static long frozenBaseline;

    @BeforeAll
    static void boot() throws Exception {
        h = RedstoneServerHarness.start("concurrency", false, "-Dveltis.parallelRedstoneStats=1");
        boolean chunkCoords = h.detectForceloadChunkCoords();
        h.configureForceloadChunkCoords(chunkCoords);
        h.forceLoadChunks(0, 0, 7, 7);
        h.awaitLoaded(2, 2, 2);
        // Forceload tickets materialize over several server ticks; wait for every chunk
        // the scenarios build in before freezing, or a setblock racing an unloaded chunk
        // would silently place nothing.
        h.awaitLoaded(80, 2, 34);
        h.awaitLoaded(32, 2, 54);
        h.awaitLoaded(80, 2, 54);
        int surface = h.highestNonAir(2, 2, 5, -64);
        assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
        y = surface + 1;
        h.freeze();
        h.step(4);
        frozenBaseline = h.gameTime();
    }

    @AfterAll
    static void shutdown() {
        if (h != null) {
            h.close();
        }
    }

    // ------------------------------------------------------------- helpers

    /** Steps {@code ticks} and returns all stats lines emitted inside that window. */
    private static String stepCollectingStats(int ticks) throws IOException, InterruptedException {
        int mark = h.mark();
        h.step(ticks);
        return h.textSinceQuiet(mark, 800, 15_000);
    }

    private static long statSum(String text, String field) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "=(\\d+)").matcher(text);
        long sum = 0;
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static void assertParallelDispatched(String stats) {
        assertTrue(stats.contains("block-tick phases"), "stats line missing; window:\n" + stats);
        assertTrue(statSum(stats, "parallel") >= 1,
                "32 ticks across chunks 2 and 5 should dispatch in parallel; window:\n" + stats);
    }

    private static void set(String block, int x, int z) throws IOException {
        h.cmd("setblock " + x + " " + y + " " + z + " " + block);
    }

    // -------------------------------------------------- C1: falling sand

    @Order(1)
    @Test
    public void fallingSandEntitiesSpawnInsideTheParallelPhase() throws Exception {
        // 32 unsupported sand blocks: placement schedules a fall tick per block, all due
        // in one collect. The ticks spawn FallingBlockEntity objects, whose creation is
        // deferred to the end-of-tick entity queue - exactly the kind of shared mutable
        // state the parallel phase must not disturb.
        for (int x = 32; x <= 47; x++) {
            h.cmd("setblock " + x + " " + (y + 1) + " " + 34 + " minecraft:sand");
        }
        for (int x = 80; x <= 95; x++) {
            h.cmd("setblock " + x + " " + (y + 1) + " " + 34 + " minecraft:sand");
        }
        assertTrue(h.blockMatches("32 " + (y + 1) + " 34 minecraft:sand"),
                "sand should still be frozen at y+1 before the step; tail:\n" + h.tail(1200));
        assertTrue(h.blockMatches("80 " + (y + 1) + " 34 minecraft:sand"),
                "band-2 sand should still be frozen at y+1 before the step; tail:\n" + h.tail(1200));

        String stats = stepCollectingStats(16);
        assertParallelDispatched(stats);

        // Every column fell exactly one block onto the floor and converted back to a
        // placed block: entity spawn and landing both survived the parallel phase.
        for (int x = 32; x <= 47; x++) {
            assertTrue(h.blockMatches(x + " " + y + " " + 34 + " minecraft:sand"),
                    "sand (" + x + ",34) should have landed at the circuit layer; tail:\n" + h.tail(1200));
        }
        for (int x = 80; x <= 95; x++) {
            assertTrue(h.blockMatches(x + " " + y + " " + 34 + " minecraft:sand"),
                    "sand (" + x + ",34) should have landed at the circuit layer; tail:\n" + h.tail(1200));
        }
    }

    // -------------------------------------------------- C2: sticky pistons

    @Order(2)
    @Test
    public void pistonExtensionsStayConsistentAcrossRegions() throws Exception {
        // 32 sticky pistons facing south with a stone block in front, each driven by a
        // repeater behind it (repeater at z44 facing north reads the redstone block at
        // z43 and outputs into the piston). The repeater's input change schedules its
        // flip tick, and the flip synchronously extends the piston inside the block-tick
        // phase - consuming level randomness for the push sound and moving blocks through
        // the chunk write path, all from a worker thread.
        for (int x = 32; x <= 47; x++) {
            set("minecraft:stone", x, 46);
        }
        for (int x = 80; x <= 95; x++) {
            set("minecraft:stone", x, 46);
        }
        for (int x = 32; x <= 47; x++) {
            set("minecraft:sticky_piston[facing=south]", x, 45);
        }
        for (int x = 80; x <= 95; x++) {
            set("minecraft:sticky_piston[facing=south]", x, 45);
        }
        assertTrue(h.blockMatches("32 " + y + " 45 minecraft:sticky_piston[extended=false]"),
                "piston should start unextended; tail:\n" + h.tail(1200));
        for (int x = 32; x <= 47; x++) {
            set("minecraft:repeater[facing=north]", x, 44);
        }
        for (int x = 80; x <= 95; x++) {
            set("minecraft:repeater[facing=north]", x, 44);
        }
        for (int x = 32; x <= 47; x++) {
            set("minecraft:redstone_block", x, 43);
        }
        for (int x = 80; x <= 95; x++) {
            set("minecraft:redstone_block", x, 43);
        }

        String stats = stepCollectingStats(8);
        assertParallelDispatched(stats);

        for (int x = 32; x <= 47; x++) {
            assertTrue(h.blockMatches(x + " " + y + " 45 minecraft:sticky_piston[extended=true]"),
                    "piston (" + x + ",45) should be extended; tail:\n" + h.tail(1200));
            assertTrue(h.blockMatches(x + " " + y + " 47 minecraft:stone"),
                    "stone (" + x + ",47) should have been pushed; tail:\n" + h.tail(1200));
        }
        for (int x = 80; x <= 95; x++) {
            assertTrue(h.blockMatches(x + " " + y + " 45 minecraft:sticky_piston[extended=true]"),
                    "piston (" + x + ",45) should be extended; tail:\n" + h.tail(1200));
            assertTrue(h.blockMatches(x + " " + y + " 47 minecraft:stone"),
                    "stone (" + x + ",47) should have been pushed; tail:\n" + h.tail(1200));
        }
    }

    // -------------------------------------------------- C3: redstone torches

    @Order(3)
    @Test
    public void torchBurnoutBookkeepingSurvivesParallelShutoff() throws Exception {
        // 32 lit torches standing on the stone floor: the only input a redstone torch
        // reads is the block below it, so replacing that floor block with a redstone
        // block (sources placed last) schedules each shutoff tick. The shutoff writes
        // through LevelChunk.setBlockState and touches the synchronized (shared, so
        // cross-thread by design) RECENT_TOGGLES burnout list.
        for (int x = 32; x <= 47; x++) {
            set("minecraft:redstone_torch", x, 54);
        }
        for (int x = 80; x <= 95; x++) {
            set("minecraft:redstone_torch", x, 54);
        }
        assertTrue(h.blockMatches("32 " + y + " 54 minecraft:redstone_torch[lit=true]"),
                "torch should start lit; tail:\n" + h.tail(1200));
        assertTrue(h.blockMatches("80 " + y + " 54 minecraft:redstone_torch[lit=true]"),
                "band-2 torch should start lit; tail:\n" + h.tail(1200));
        for (int x = 32; x <= 47; x++) {
            h.cmd("setblock " + x + " " + (y - 1) + " " + 54 + " minecraft:redstone_block");
        }
        for (int x = 80; x <= 95; x++) {
            h.cmd("setblock " + x + " " + (y - 1) + " " + 54 + " minecraft:redstone_block");
        }

        String stats = stepCollectingStats(8);
        assertParallelDispatched(stats);

        for (int x = 32; x <= 47; x++) {
            assertTrue(h.blockMatches(x + " " + y + " 54 minecraft:redstone_torch[lit=false]"),
                    "torch (" + x + ",54) should have turned off; tail:\n" + h.tail(1200));
        }
        for (int x = 80; x <= 95; x++) {
            assertTrue(h.blockMatches(x + " " + y + " 54 minecraft:redstone_torch[lit=false]"),
                    "torch (" + x + ",54) should have turned off; tail:\n" + h.tail(1200));
        }
    }

    // -------------------------------------------------- global invariants

    @Order(4)
    @Test
    public void noThreadingViolationsAcrossTheWholeSession() throws Exception {
        // Runs after the three scenarios (ordered above) in this same server session;
        // the vanilla ThreadingDetector and the PalettedContainer guards both report
        // through these exact markers when a structure is touched from several threads.
        long before = h.gameTime();
        String all = h.tail(500_000);
        assertFalse(all.contains("from multiple threads"),
                "ThreadingDetector fired; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
        assertFalse(all.contains("Thread dumps"),
                "ThreadingDetector fired; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
        // The frozen server only advances through our explicit steps: the boot drain
        // (4 ticks) is inside the baseline, the scenarios add 16 + 8 + 8 = 32 ticks.
        assertEquals(32, before - frozenBaseline,
                "all scenario steps should have advanced game time by exactly 32 ticks");
    }
}
