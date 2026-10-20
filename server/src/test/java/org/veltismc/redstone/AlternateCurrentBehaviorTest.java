package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behavioral regression suite for Alternate Current redstone, driven through a real
 * forked Veltis server. Covers the eleven required scenario categories:
 *
 * <ol>
 *   <li>Dust lines and branching networks</li>
 *   <li>Repeaters and comparators (delays, subtract mode)</li>
 *   <li>Observers and pistons</li>
 *   <li>Clocks (repeater ring oscillator)</li>
 *   <li>Wire placement and removal</li>
 *   <li>Independent circuits (no cross-talk)</li>
 *   <li>Interconnected circuits (multi-source network)</li>
 *   <li>Chunk-boundary propagation</li>
 *   <li>Unloaded-chunk behavior</li>
 *   <li>Multiple heavy regions</li>
 *   <li>Shutdown with queued work (own harness, see {@link #shutdownWithQueuedScheduledTicks})</li>
 * </ol>
 *
 * <p>The whole suite runs on a frozen world ({@code tick freeze} + {@code tick step Nt}), so
 * every assertion is exact-tick deterministic. Each scenario owns a private coordinate band;
 * bands are separated so no scenario's circuit can interact with another's.
 */
public class AlternateCurrentBehaviorTest {

    private static RedstoneServerHarness h;
    /** Y of the circuit layer: one above the flat world's top solid layer. */
    private static int y;

    @BeforeAll
    static void boot() throws Exception {
        System.out.println("CP_BEGIN\n" + System.getProperty("java.class.path") + "\nCP_END");
        h = RedstoneServerHarness.start("suite");
        boolean chunkCoords = h.detectForceloadChunkCoords();
        h.configureForceloadChunkCoords(chunkCoords);
        // Chunks (0,0)-(3,3) cover every scenario zone (x,z in 0..63). Chunk (4,2) is
        // deliberately never force-loaded: scenario 9 needs an unloaded chunk.
        h.forceLoadChunks(0, 0, 3, 3);
        h.awaitLoaded(2, 2, 2);
        // Find the flat world's surface: custom flat layers stack from the bottom of the
        // world (y=-64), so scan the whole range for the highest non-air y.
        int surface = h.highestNonAir(2, 2, 5, -64);
        assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
        y = surface + 1;
        h.freeze();
        System.out.println("suite: forceload=" + (chunkCoords ? "chunk" : "block")
                + " coords, surfaceY=" + surface + ", circuitY=" + y);
    }

    @AfterAll
    static void shutdown() {
        if (h != null) {
            h.close();
        }
    }

    // ------------------------------------------------------------- helpers

    private static void set(int x, int z, String block) throws IOException {
        h.cmd("setblock " + x + " " + y + " " + z + " " + block);
    }

    private static void wire(int x, int z) throws IOException {
        set(x, z, "minecraft:redstone_wire");
    }

    private static boolean wirePower(int x, int z, int power) throws IOException {
        return h.blockMatches(x + " " + y + " " + z + " minecraft:redstone_wire[power=" + power + "]");
    }

    private static void assertWirePower(int x, int z, int power) throws IOException {
        assertTrue(wirePower(x, z, power),
                "wire (" + x + "," + z + ") should have power " + power + "; tail:\n" + h.tail(1200));
    }

    private static void assertWirePowerNot(int x, int z, int power) throws IOException {
        assertFalse(wirePower(x, z, power),
                "wire (" + x + "," + z + ") should NOT have power " + power + "; tail:\n" + h.tail(1200));
    }

    // ------------------------------------------------- 1. dust lines/branches

    @Test
    public void dustLineDecayAndBranching() throws Exception {
        // Straight line: redstone block source, 7 wires of decay.
        set(2, 2, "minecraft:redstone_block");
        for (int x = 3; x <= 9; x++) {
            wire(x, 2);
        }
        assertWirePower(3, 2, 15);
        assertWirePower(5, 2, 13);
        assertWirePower(9, 2, 9);
        assertWirePowerNot(9, 2, 15);

        // T-junction at z=6: source (2,6), trunk to junction (6,6), three arms.
        set(2, 6, "minecraft:redstone_block");
        for (int x = 3; x <= 9; x++) {
            wire(x, 6);
        }
        for (int z = 4; z <= 5; z++) {
            wire(6, z);
        }
        for (int z = 7; z <= 8; z++) {
            wire(6, z);
        }
        assertWirePower(6, 6, 12);      // junction
        assertWirePower(9, 6, 9);       // east arm end
        assertWirePower(6, 5, 11);      // north arm
        assertWirePower(6, 4, 10);
        assertWirePower(6, 7, 11);      // south arm (symmetric)
        assertWirePower(6, 8, 10);
    }

    // ------------------------------------------- 2. repeaters and comparators

    @Test
    public void repeaterDelaysAndComparatorModes() throws Exception {
        // Each row: active component and wires first, source LAST. Setblock-placed
        // repeaters/comparators never self-schedule (DiodeBlock.onPlace only notifies its
        // front), so the source's placement is the neighborChanged that schedules the diode.
        // FACING = input side (source sits on the FACING side); output flows east.
        // delay=1 repeater row, z=11
        set(3, 11, "minecraft:repeater[facing=west]");
        for (int x = 4; x <= 8; x++) {
            wire(x, 11);
        }
        set(2, 11, "minecraft:redstone_block");
        // delay=4 repeater row, z=13
        set(3, 13, "minecraft:repeater[facing=west,delay=4]");
        for (int x = 4; x <= 8; x++) {
            wire(x, 13);
        }
        set(2, 13, "minecraft:redstone_block");
        // comparator (compare mode) row, z=15
        set(3, 15, "minecraft:comparator[facing=west]");
        for (int x = 4; x <= 6; x++) {
            wire(x, 15);
        }
        set(2, 15, "minecraft:redstone_block");
        // comparator (subtract mode) row, z=17, side input powered by a redstone block
        set(3, 17, "minecraft:comparator[facing=west,mode=subtract]");
        set(3, 18, "minecraft:redstone_block"); // side input (south face)
        for (int x = 4; x <= 6; x++) {
            wire(x, 17);
        }
        set(2, 17, "minecraft:redstone_block");

        // Before any ticks: nothing has fired yet (frozen world).
        assertWirePower(4, 11, 0);
        assertWirePower(4, 15, 0);

        // +2t: delay-1 repeater and comparator have fired; delay-4 has not.
        h.step(2);
        assertWirePower(4, 11, 15);
        assertWirePower(8, 11, 11); // 15 after the repeater, then one decay step per wire
        assertWirePower(4, 15, 15);
        assertWirePower(6, 15, 13);
        assertWirePower(4, 13, 0);  // delay-4 still pending
        assertWirePower(4, 17, 0);  // subtract 15-15 = 0

        // +6t more (8t total): delay-4 repeater has fired.
        h.step(6);
        assertWirePower(4, 13, 15);
        assertWirePower(8, 13, 11);
        assertWirePower(4, 17, 0); // subtract output stays 0 while the side input holds

        // Removing the side power re-evaluates subtract: 15 - 0 = 15. This proves the
        // side input was genuinely read (compare mode would have fired with output 15 above).
        set(3, 18, "minecraft:air");
        h.step(2);
        assertWirePower(4, 17, 15);
    }

    // --------------------------------------------- 3. observers and pistons

    @Test
    public void observerPulseAndPistonExtension() throws Exception {
        // Observer at (20,z=20) facing west: FACING is the detection (eyes) side, so
        // (19,20) triggers it; the output emerges from the opposite (east) side (21,20).
        set(20, 20, "minecraft:observer[facing=west]");
        wire(19, 20); // detection-side block change starts the +2gt pulse
        wire(21, 20); // output-side wire
        // Pulse fires +2gt: observer powers its output side for 2gt.
        h.step(2);
        assertTrue(h.blockMatches("20 " + y + " 20 minecraft:observer[powered=true]"),
                "observer should be powered after 2t; tail:\n" + h.tail(1200));
        assertWirePower(21, 20, 15);
        assertWirePower(19, 20, 0); // detection side must not be back-powered
        // +2gt: pulse over.
        h.step(2);
        assertTrue(h.blockMatches("20 " + y + " 20 minecraft:observer[powered=false]"),
                "observer should have decayed after 4t; tail:\n" + h.tail(1200));
        assertWirePower(21, 20, 0);

        // Piston at (25,z=22) facing east: stone support behind it, side power kept
        // separate from the support so retracting only removes power (the support stays,
        // otherwise the base would break instead of retracting).
        set(24, 22, "minecraft:stone");
        set(25, 22, "minecraft:piston[facing=east]");
        set(25, 21, "minecraft:redstone_block"); // side (north) power
        // Extension = queued block event + the moving-head animation; empirically the
        // head is present after 3 stepped ticks.
        h.step(3);
        assertTrue(h.blockMatches("26 " + y + " 22 minecraft:piston_head"),
                "piston should be extended; tail:\n" + h.tail(1200));
        // Remove the side power only.
        set(25, 21, "minecraft:air");
        h.step(1);
        assertFalse(h.blockMatches("26 " + y + " 22 minecraft:piston_head"),
                "piston should have retracted; tail:\n" + h.tail(1200));
    }

    // ----------------------------------------------------------- 4. clocks

    @Test
    public void repeaterRingClockOscillatesDeterministically() throws Exception {
        // A closed ring of three repeaters: an injected pulse circulates forever.
        // A two-repeater ring cannot oscillate — the trailing repeater re-powers the
        // leading one's input before its turn-off fires, latching both on — so the third
        // repeater preserves the travelling gap. Layout (top-down):
        //   z=24:                     I    (injection block, transient)
        //   z=25:  W  R(e)  W         R(e) at (31,25): input east=(32,25), output west=(30,25)
        //   z=26:  W  .  R(s)         R(s) at (32,26): input south=(32,27), output north=(32,25)
        //   z=27:  W  R(w)  W         R(w) at (31,27): input west=(30,27), output east=(32,27)
        for (int[] wirePos : new int[][] { { 30, 25 }, { 30, 26 }, { 30, 27 }, { 32, 25 }, { 32, 27 } }) {
            wire(wirePos[0], wirePos[1]);
        }
        set(31, 25, "minecraft:repeater[facing=east]");
        set(31, 27, "minecraft:repeater[facing=west]");
        set(32, 26, "minecraft:repeater[facing=south]");

        // Hold the injected pulse across the repeater's +2gt schedule so its first fire
        // sees a genuinely-powered input — the ring start is then deterministic.
        set(32, 24, "minecraft:redstone_block");
        h.step(2);
        set(32, 24, "minecraft:air");

        // Sample a ring wire once per game tick for two laps. The ring must oscillate
        // strictly between 0 and 15 and repeat with a stable period >= 2 samples.
        List<Integer> samples = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            boolean high = wirePower(32, 25, 15);
            boolean low = wirePower(32, 25, 0);
            assertTrue(high ^ low, "ring wire must be exactly 0 or 15 at sample " + i
                    + "; tail:\n" + h.tail(1200));
            samples.add(high ? 15 : 0);
            h.step(1);
        }
        boolean anyHigh = samples.stream().anyMatch(v -> v == 15);
        boolean anyLow = samples.stream().anyMatch(v -> v == 0);
        assertTrue(anyHigh && anyLow, "ring clock must oscillate, got " + samples);
        int period = -1;
        for (int p = 1; p <= 6; p++) {
            boolean repeats = true;
            for (int i = 0; i + p < samples.size(); i++) {
                if (!samples.get(i).equals(samples.get(i + p))) {
                    repeats = false;
                    break;
                }
            }
            if (repeats) {
                period = p;
                break;
            }
        }
        assertTrue(period >= 1, "ring clock sequence must be periodic, got " + samples);
        System.out.println("suite: ring clock samples=" + samples + " period=" + period);
    }

    // -------------------------------------------------- 5. wire place/remove

    @Test
    public void wirePlaceRemoveAndReextend() throws Exception {
        set(40, 30, "minecraft:redstone_block");
        wire(41, 30);
        wire(42, 30);
        wire(43, 30);
        assertWirePower(43, 30, 13);
        // Remove the end wire: upstream power is unchanged.
        set(43, 30, "minecraft:air");
        assertWirePower(42, 30, 14);
        // Re-place it: network extends again with the same powers.
        wire(43, 30);
        assertWirePower(43, 30, 13);
        // Extend further.
        wire(44, 30);
        assertWirePower(44, 30, 12);
        // Remove the source entirely: everything collapses to 0.
        set(40, 30, "minecraft:air");
        assertWirePower(41, 30, 0);
        assertWirePower(44, 30, 0);
    }

    // --------------------------------------- 6. independent circuits (no cross-talk)

    @Test
    public void independentCircuitsDoNotInteract() throws Exception {
        set(2, 33, "minecraft:redstone_block");
        for (int x = 3; x <= 5; x++) {
            wire(x, 33);
        }
        set(20, 33, "minecraft:redstone_block");
        for (int x = 21; x <= 23; x++) {
            wire(x, 33);
        }
        assertWirePower(5, 33, 13);
        assertWirePower(23, 33, 13);
        // Collapse circuit A; circuit B must be untouched.
        set(2, 33, "minecraft:air");
        assertWirePower(5, 33, 0);
        assertWirePower(23, 33, 13);
        assertWirePower(21, 33, 15);
    }

    // ------------------------------------ 7. interconnected circuit (two sources)

    @Test
    public void interconnectedNetworkConflictResolution() throws Exception {
        // 13-wire line with a source at each end (x=39 and x=53).
        set(39, 36, "minecraft:redstone_block");
        set(53, 36, "minecraft:redstone_block");
        for (int x = 40; x <= 52; x++) {
            wire(x, 36);
        }
        assertWirePower(40, 36, 15);
        assertWirePower(52, 36, 15);
        assertWirePower(46, 36, 9);  // left gives 9, right gives 9
        assertWirePower(42, 36, 13); // left dominant on this side
        // Remove the left source: the right source re-powers the whole line.
        set(39, 36, "minecraft:air");
        assertWirePower(42, 36, 5);  // 15 - (52-42) = 5 (nearest right-side wire is x=52)
        assertWirePower(46, 36, 9);  // 15 - (53-46) = 9 (was already 9)
        assertWirePower(52, 36, 15);
    }

    // -------------------------------------------------- 8. chunk boundary

    @Test
    public void propagationAcrossChunkBoundary() throws Exception {
        // Line from x=45 to x=50 crosses the chunk border at x=47|48 (chunks 2|3).
        set(45, 39, "minecraft:redstone_block");
        for (int x = 46; x <= 50; x++) {
            wire(x, 39);
        }
        assertWirePower(46, 39, 15);
        assertWirePower(48, 39, 13); // first block of chunk 3; power crosses the border
        assertWirePower(49, 39, 12); // still chunk 3
        assertWirePower(50, 39, 11);
    }

    // ------------------------------------------------ 9. unloaded chunk

    @Test
    public void unloadedChunkRejectsAndRecovers() throws Exception {
        // Chunk (4,2) (x=64..79, z=32..47) is not force-loaded.
        int mark = h.mark();
        h.cmd("setblock 65 " + y + " 42 minecraft:stone");
        assertTrue(h.await(mark, "not loaded", 15_000),
                "setblock into an unloaded chunk should be rejected; tail:\n" + h.tail(1200));
        // Force-load it; the same command must now succeed.
        h.forceLoadChunks(4, 2, 4, 2);
        h.awaitLoaded(65, y, 42);
        String token = "UNLD" + System.nanoTime();
        int mark2 = h.mark();
        h.cmd("setblock 65 " + y + " 42 minecraft:redstone_block");
        h.cmd("say " + token);
        assertTrue(h.await(mark2, java.util.regex.Pattern.quote(token), 15_000),
                "setblock into a now-loaded chunk should succeed; tail:\n" + h.tail(1200));
    }

    // -------------------------------------------- 10. multiple heavy regions

    @Test
    public void multipleHeavyRegionsReachSteadyState() throws Exception {
        // Two repeater-ladder grids: grid A x=2..13, grid B x=20..31, rows z=45..47.
        // Repeaters first (facing west = input side), wires between, source LAST: the
        // source's placement powers wire(base+1), whose recalc schedules repeater
        // base+3; each fired repeater then schedules the next.
        for (int z = 45; z <= 47; z++) {
            for (int base : new int[] { 2, 20 }) {
                wire(base + 1, z);
                wire(base + 2, z);
                set(base + 3, z, "minecraft:repeater[facing=west]");
                wire(base + 4, z);
                wire(base + 5, z);
                set(base + 6, z, "minecraft:repeater[facing=west]");
                wire(base + 7, z);
                wire(base + 8, z);
                set(base + 9, z, "minecraft:repeater[facing=west]");
                wire(base + 10, z);
                wire(base + 11, z);
                set(base, z, "minecraft:redstone_block");
            }
        }
        h.step(10);
        for (int z = 45; z <= 47; z++) {
            for (int base : new int[] { 2, 20 }) {
                assertTrue(h.blockMatches((base + 3) + " " + y + " " + z + " minecraft:repeater[powered=true]"),
                        "repeater at (" + (base + 3) + "," + z + ") should fire; tail:\n" + h.tail(1200));
                assertWirePower(base + 4, z, 15);  // output wire of the first repeater
                assertWirePower(base + 7, z, 15);  // output wire of the second repeater
                assertWirePower(base + 10, z, 15); // output wire of the third repeater
                assertWirePower(base + 11, z, 14); // one decay step past the refresh
            }
        }
    }

    // ------------------------------- 11. shutdown with queued scheduled ticks

    @Test
    public void shutdownWithQueuedScheduledTicks() throws Exception {
        RedstoneServerHarness h2 = RedstoneServerHarness.start("shutdown");
        try {
            h2.forceLoadChunks(0, 0, 1, 1);
            h2.awaitLoaded(2, 2, 2);
            // Circuit layer = surface + 1 (custom flat layers stack from y=-64); repeaters
            // need a sturdy block below, so a hardcoded y would break them instantly.
            int surface = h2.highestNonAir(2, 2, 5, -64);
            assertTrue(surface > Integer.MIN_VALUE, "no surface in shutdown harness; tail:\n" + h2.tail(1500));
            int y2 = surface + 1;
            // Build a chain of delay-4 repeaters (facing west = input side), then power
            // it — the source's placement is the neighborChanged that queues the first
            // scheduled tick (+8gt = 400ms, far beyond the shutdown command). Stop while
            // ticks are still queued; the server must exit cleanly (0).
            for (int x = 3; x <= 9; x++) {
                h2.cmd("setblock " + x + " " + y2 + " 2 minecraft:repeater[facing=west,delay=4]");
            }
            h2.cmd("setblock 2 " + y2 + " 2 minecraft:redstone_block");
            int code = h2.stopAndExit();
            assertEquals(0, code, "server with queued scheduled ticks must stop with exit code 0");
        } finally {
            h2.close();
        }
    }
}
