package org.veltismc.redstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Cache lifecycle of the per-chunk unbounded-propagator position set that
 * {@link ParallelRedstone#unboundedPropagatorsOf(LevelChunk)} maintains, and the
 * {@link LevelChunk#setBlockState} invalidation hook that keeps it honest.
 *
 * <p>The core tests drive the real {@code LevelChunk.setBlockState} on a real chunk
 * built over an Unsafe-allocated {@link ServerLevel} stand-in, so the exact production
 * write path runs in-process without a server: flags 512 skip placement side effects,
 * the section is kept non-empty and every state pair keeps identical light properties,
 * which leaves the pure write + cache-invalidation path. A compact forked-server
 * scenario then proves the same invalidation loop holds on live chunks (wire removal
 * and restore, fence connection flips) without losing parallel dispatch or behaviour.
 */
public class ParallelRedstoneCacheInvalidationTest {

    /** Circuit layer of the synthetic chunk; y=0 lives in section index (0-minY)/16 = 4. */
    private static final int CIRCUIT_Y = 0;

    @BeforeAll
    static void bootstrapMinecraft() {
        // Any Minecraft class touched below (Blocks, DimensionType, ...) requires the
        // shared registries; bootStrap() is idempotent across test classes.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ------------------------------------------------------------- predicates

    @Test
    public void propagatorPredicateMatchesNetworkBlocksOnly() {
        // Unbounded same-tick update chains: wire networks, fence/wall/bars shape
        // chains, rails, tripwire lines.
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.REDSTONE_WIRE.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.OAK_FENCE.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.COBBLESTONE_WALL.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.IRON_BARS.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.RAIL.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.TRIPWIRE.defaultBlockState()));
        assertTrue(ParallelRedstone.isUnboundedPropagator(Blocks.TRIPWIRE_HOOK.defaultBlockState()));

        // Bounded or scheduled reactions stay outside the set.
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.REDSTONE_TORCH.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.REPEATER.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.COMPARATOR.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.OBSERVER.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.PISTON.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.REDSTONE_LAMP.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.REDSTONE_BLOCK.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.AIR.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.STONE.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(ParallelRedstone.isUnboundedPropagator(Blocks.SHORT_GRASS.defaultBlockState()));
    }

    @Test
    public void membershipChangeRequiresCrossingTheBoundary() {
        BlockState wireHot = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.POWER, 15);
        BlockState wireCold = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.POWER, 0);
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState repeater = Blocks.REPEATER.defaultBlockState();

        // Crossing the boundary invalidates.
        assertTrue(ParallelRedstone.propagatorMembershipChanged(wireHot, air), "removal");
        assertTrue(ParallelRedstone.propagatorMembershipChanged(air, wireCold), "addition");
        assertTrue(ParallelRedstone.propagatorMembershipChanged(wireHot, stone), "propagator to solid");
        assertTrue(ParallelRedstone.propagatorMembershipChanged(stone, fence), "solid to propagator");
        assertTrue(ParallelRedstone.propagatorMembershipChanged(null, wireCold),
                "null old state with an added propagator");

        // Power changes and propagator-to-propagator swaps keep membership.
        assertFalse(ParallelRedstone.propagatorMembershipChanged(wireHot, wireCold), "power only");
        assertFalse(ParallelRedstone.propagatorMembershipChanged(wireHot, wireHot), "identical state");
        assertFalse(ParallelRedstone.propagatorMembershipChanged(wireHot, fence), "propagator to propagator");

        // Non-propagator changes and null old states without propagators keep the cache.
        assertFalse(ParallelRedstone.propagatorMembershipChanged(air, stone), "air to solid");
        assertFalse(ParallelRedstone.propagatorMembershipChanged(null, air), "null old state, air");
        assertFalse(ParallelRedstone.propagatorMembershipChanged(repeater, air), "repeater to air");
    }

    // ----------------------------------------------- workload audit model

    @Test
    public void parallelIsDefaultAndOnlyExplicitFalseDisables() {
        assertTrue(ParallelRedstone.defaultEnabled(), "parallel redstone must ship enabled");
        assertTrue(ParallelRedstone.resolveEnabled(null), "absent property means enabled");
        assertTrue(ParallelRedstone.resolveEnabled("true"), "explicit true");
        assertTrue(ParallelRedstone.resolveEnabled(""), "empty value is not the opt-out");
        assertTrue(ParallelRedstone.resolveEnabled("garbage"), "only the literal false opts out");
        assertFalse(ParallelRedstone.resolveEnabled("false"), "explicit false");
        assertFalse(ParallelRedstone.resolveEnabled("FALSE"), "case-insensitive false");
    }

    @Test
    public void tickAllowlistCoversRedstoneMechanismsButNotDispenserOrFire() {
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.REDSTONE_WIRE));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.REPEATER));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.COMPARATOR));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.OBSERVER));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.REDSTONE_TORCH));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.SAND));
        assertTrue(ParallelRedstone.isAuditedTickType(Blocks.PISTON));

        // Dispensers schedule a 4gt tick from neighborChanged and are deliberately not
        // audited: their tick dispatches items/entities. Fire spreads beyond its chunk.
        assertFalse(ParallelRedstone.isAuditedTickType(Blocks.DISPENSER));
        assertFalse(ParallelRedstone.isAuditedTickType(Blocks.FIRE));
        assertFalse(ParallelRedstone.isAuditedTickType(Blocks.COMMAND_BLOCK));
    }

    @Test
    public void auditClassifiesPassiveVanillaAndThirdPartyClasses() {
        assertEquals(ParallelRedstone.BlockAudit.PASSIVE,
                ParallelRedstone.auditClass(Object.class), "no reactive callbacks");
        assertEquals(ParallelRedstone.BlockAudit.AUDITED,
                ParallelRedstone.auditClass(Blocks.REDSTONE_WIRE.getClass()),
                "vanilla reactive block");
        assertEquals(ParallelRedstone.BlockAudit.AUDITED,
                ParallelRedstone.auditClass(Blocks.FIRE.getClass()),
                "vanilla reactive is trusted, but not tick-allowlisted");
        assertEquals(ParallelRedstone.BlockAudit.UNAUDITED,
                ParallelRedstone.auditClass(FakeReactive.class),
                "a non-platform override must force serial");
    }

    /** Plain class (never instantiated as a block) that declares a reactive callback. */
    private static final class FakeReactive {
        @SuppressWarnings("unused")
        public void neighborChanged() {
        }
    }

    @Test
    public void neighbourhoodInvalidationOnlyCrossesTheUnauditedBoundary() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState wireHot = wire.setValue(RedstoneWireBlock.POWER, 15);
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();

        assertFalse(ParallelRedstone.neighbourhoodAuditChanged(air, stone));
        assertFalse(ParallelRedstone.neighbourhoodAuditChanged(stone, air));
        assertFalse(ParallelRedstone.neighbourhoodAuditChanged(wire, wireHot), "state-only change");
        assertFalse(ParallelRedstone.neighbourhoodAuditChanged(wire, stone), "audited to passive");

        ParallelRedstone.forceUnauditedForTest(Blocks.SHORT_GRASS.getClass(), true);
        try {
            assertTrue(ParallelRedstone.neighbourhoodAuditChanged(stone, grass), "entering unaudited");
            assertTrue(ParallelRedstone.neighbourhoodAuditChanged(grass, air), "leaving unaudited");
            assertTrue(ParallelRedstone.neighbourhoodAuditChanged(null, grass), "placement");
            assertFalse(ParallelRedstone.neighbourhoodAuditChanged(grass, grass), "same class");
        } finally {
            ParallelRedstone.forceUnauditedForTest(Blocks.SHORT_GRASS.getClass(), false);
        }
    }

    @Test
    public void chunkAuditScansAndRealWriteHookInvalidates() {
        ServerLevel level = syntheticLevel();
        LevelChunk chunk = syntheticChunk(level, new ChunkPos(0, 0));
        Class<?> grassClass = Blocks.SHORT_GRASS.getClass();
        try {
            ParallelRedstone.forceUnauditedForTest(grassClass, true);
            assertTrue(ParallelRedstone.chunkNeighbourhoodSafe(chunk), "stone/air-only chunk is safe");
            assertEquals(1, chunk.veltisNeighbourhoodAudit, "safe answer is cached");

            // The real LevelChunk.setBlockState hook must drop the cache when the write
            // enters the unaudited class; the next scan then finds the block.
            change(chunk, pos(7, 0), Blocks.SHORT_GRASS.defaultBlockState());
            assertEquals(0, chunk.veltisNeighbourhoodAudit, "crossing into unaudited must invalidate");
            assertFalse(ParallelRedstone.chunkNeighbourhoodSafe(chunk),
                    "an unaudited reactive block anywhere in the chunk is unsafe");
            assertEquals(2, chunk.veltisNeighbourhoodAudit, "unsafe answer is cached");

            // Leaving the class invalidates again and the scan recovers.
            change(chunk, pos(7, 0), Blocks.AIR.defaultBlockState());
            assertEquals(0, chunk.veltisNeighbourhoodAudit, "crossing out of unaudited must invalidate");
            assertTrue(ParallelRedstone.chunkNeighbourhoodSafe(chunk), "removal restores safety");
        } finally {
            ParallelRedstone.forceUnauditedForTest(grassClass, false);
        }
    }

    // ------------------------------------- synthetic chunk cache lifecycle

    @Test
    public void chunkPropagatorCacheComputesRetainsAndInvalidates() {
        ServerLevel level = syntheticLevel();
        LevelChunk chunk = syntheticChunk(level, new ChunkPos(0, 0));

        BlockState wireHot = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.POWER, 15);
        BlockState wireCold = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.POWER, 0);
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState tripwire = Blocks.TRIPWIRE.defaultBlockState();
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        assertNull(chunk.veltisUnboundedPropagators, "cache must start uncomputed");

        // Fill the circuit cells (they start as air inside the stone-anchored section).
        change(chunk, pos(1, 0), wireHot);
        change(chunk, pos(2, 0), wireCold);
        change(chunk, pos(3, 0), fence);
        change(chunk, pos(4, 0), tripwire);
        change(chunk, pos(5, 0), grass); // non-propagator, no membership crossing

        // First computation: exact positions, cached on the chunk, returned verbatim.
        LongOpenHashSet first = ParallelRedstone.unboundedPropagatorsOf(chunk);
        assertSame(first, chunk.veltisUnboundedPropagators, "compute must publish the cache");
        assertEquals(expectedPositions(pos(1, 0), pos(2, 0), pos(3, 0), pos(4, 0)), first,
                "wire, second wire, fence and tripwire positions; grass and anchors excluded");
        assertSame(first, ParallelRedstone.unboundedPropagatorsOf(chunk), "second read must hit the cache");

        // Power-only change on an existing propagator: membership identical, retained.
        change(chunk, pos(1, 0), wireCold);
        assertSame(first, chunk.veltisUnboundedPropagators, "power change must not rescan");
        assertEquals(4, first.size(), "position set is power-independent");

        // Non-propagator replacement: retained.
        change(chunk, pos(5, 0), air);
        assertSame(first, chunk.veltisUnboundedPropagators, "solid/air churn must not rescan");

        // Propagator swapped for a different propagator block: same position, retained.
        change(chunk, pos(2, 0), fence);
        assertSame(first, chunk.veltisUnboundedPropagators, "propagator-to-propagator keeps membership");

        // Propagator removed: invalidated, next read recomputes without it.
        change(chunk, pos(3, 0), air);
        assertNull(chunk.veltisUnboundedPropagators, "removal must drop the cache");
        LongOpenHashSet second = ParallelRedstone.unboundedPropagatorsOf(chunk);
        assertNotSame(first, second, "invalidation must force a fresh set");
        assertEquals(expectedPositions(pos(1, 0), pos(2, 0), pos(4, 0)), second);

        // Propagator added: invalidated again.
        change(chunk, pos(6, 0), fence);
        assertNull(chunk.veltisUnboundedPropagators, "addition must drop the cache");
        LongOpenHashSet third = ParallelRedstone.unboundedPropagatorsOf(chunk);
        assertNotSame(second, third, "addition must force a fresh set");
        assertEquals(expectedPositions(pos(1, 0), pos(2, 0), pos(4, 0), pos(6, 0)), third);

        // Anchor stones are never reported as propagators.
        assertFalse(third.contains(BlockPos.asLong(14, CIRCUIT_Y, 15)), "anchor stone excluded");
        assertFalse(third.contains(BlockPos.asLong(15, CIRCUIT_Y, 14)), "anchor stone excluded");
    }

    // -------------------------------------------------- live-server scenario

    @Test
    public void liveServerInvalidationKeepsParallelDispatchAndBehaviour() throws Exception {
        RedstoneServerHarness h = null;
        try {
            h = RedstoneServerHarness.start("cache", false, "-Dveltis.parallelRedstoneStats=1");
            boolean chunkCoords = h.detectForceloadChunkCoords();
            h.configureForceloadChunkCoords(chunkCoords);
            h.forceLoadChunks(0, 0, 7, 7);
            h.awaitLoaded(2, 2, 2);
            int surface = h.highestNonAir(2, 2, 5, -64);
            assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
            int y = surface + 1;
            h.freeze();
            h.step(4);

            // Two disjoint repeater banks with wire rows over their outputs (24 pending
            // ticks in chunks 2 and 5 => one parallel phase), plus a fence pair whose
            // connection state flips below.
            buildBank(h, y, 32, 47, 42);
            buildBank(h, y, 80, 87, 42);
            h.cmd("setblock 44 " + y + " 36 minecraft:oak_fence");
            h.cmd("setblock 45 " + y + " 36 minecraft:oak_fence");

            int mark = h.mark();
            h.step(6);
            String stats = h.textSinceQuiet(mark, 800, 15_000);
            assertTrue(stats.contains("block-tick phases"), "stats line missing; window:\n" + stats);
            assertTrue(statSum(stats, "parallel") >= 1,
                    "bank phase should dispatch in parallel; window:\n" + stats);
            for (int x = 32; x <= 47; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 40 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",40) should carry 15; tail:\n" + h.tail(1200));
            }
            for (int x = 80; x <= 87; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 40 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",40) should carry 15; tail:\n" + h.tail(1200));
            }
            assertTrue(h.blockMatches("44 " + y + " 36 minecraft:oak_fence[east=true]"),
                    "fence should connect east to its neighbour; tail:\n" + h.tail(1200));
            assertTrue(h.blockMatches("45 " + y + " 36 minecraft:oak_fence[west=true]"),
                    "fence should connect west to its neighbour; tail:\n" + h.tail(1200));

            // Wire removal crosses the propagator boundary on a live chunk: the cache is
            // dropped, the remaining wires keep their repeater-fed power.
            h.cmd("setblock 40 " + y + " " + 40 + " minecraft:air");
            h.step(4);
            assertTrue(h.blockMatches("40 " + y + " " + 40 + " minecraft:air"),
                    "removed wire should be gone; tail:\n" + h.tail(1200));
            assertTrue(h.blockMatches("39 " + y + " " + 40 + " minecraft:redstone_wire[power=15]"),
                    "west neighbour should stay at 15; tail:\n" + h.tail(1200));
            assertTrue(h.blockMatches("41 " + y + " " + 40 + " minecraft:redstone_wire[power=15]"),
                    "east neighbour should stay at 15; tail:\n" + h.tail(1200));

            // Restoring the wire invalidates again and the network recovers full power.
            h.cmd("setblock 40 " + y + " " + 40 + " minecraft:redstone_wire");
            h.step(4);
            assertTrue(h.blockMatches("40 " + y + " " + 40 + " minecraft:redstone_wire[power=15]"),
                    "restored wire should carry 15 again; tail:\n" + h.tail(1200));

            // Fence connection flips: removal resets the neighbour's shape immediately,
            // re-adding restores it, all without disturbing later dispatch.
            h.cmd("setblock 45 " + y + " 36 minecraft:air");
            assertTrue(h.blockMatches("44 " + y + " 36 minecraft:oak_fence[east=false]"),
                    "fence connection should reset; tail:\n" + h.tail(1200));
            h.cmd("setblock 45 " + y + " 36 minecraft:oak_fence");
            assertTrue(h.blockMatches("44 " + y + " 36 minecraft:oak_fence[east=true]"),
                    "fence connection should return; tail:\n" + h.tail(1200));

            // A fresh bank after all those membership changes still dispatches in
            // parallel and powers its wires.
            buildBank(h, y, 32, 47, 66);
            buildBank(h, y, 84, 95, 66);
            int mark2 = h.mark();
            h.step(6);
            String stats2 = h.textSinceQuiet(mark2, 800, 15_000);
            assertTrue(statSum(stats2, "parallel") >= 1,
                    "post-invalidation phase should still dispatch in parallel; window:\n" + stats2);
            for (int x = 32; x <= 47; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 64 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",64) should carry 15; tail:\n" + h.tail(1200));
            }
            for (int x = 84; x <= 95; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 64 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",64) should carry 15; tail:\n" + h.tail(1200));
            }

            String all = h.tail(500_000);
            assertFalse(all.contains("from multiple threads"),
                    "threading violation detected; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
            assertFalse(all.contains("Thread dumps"),
                    "threading violation detected; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    // -------------------------------------------------- unaudited workload

    @Test
    public void liveDispenserTicksFallBackToSerialAndRecover() throws Exception {
        RedstoneServerHarness h = null;
        try {
            h = RedstoneServerHarness.start("audit", false, "-Dveltis.parallelRedstoneStats=1");
            boolean chunkCoords = h.detectForceloadChunkCoords();
            h.configureForceloadChunkCoords(chunkCoords);
            h.forceLoadChunks(0, 0, 7, 7);
            h.awaitLoaded(2, 2, 2);
            int surface = h.highestNonAir(2, 2, 5, -64);
            assertTrue(surface > Integer.MIN_VALUE, "no surface found; tail:\n" + h.tail(1500));
            int y = surface + 1;
            h.freeze();
            h.step(4);

            // 32 dispensers in two disjoint regions, each triggered by the redstone block
            // that is placed after it. The trigger schedules a 4gt dispenser tick; at that
            // collect the pending set is large and spread over two regions, i.e. it would
            // be a parallel phase if the tick class were audited.
            int mark = h.mark();
            buildDispensers(h, y, 32, 47, 80);
            buildDispensers(h, y, 80, 87, 80);
            h.step(6);
            String stats = h.textSinceQuiet(mark, 800, 15_000);
            assertTrue(stats.contains("block-tick phases"), "stats line missing; window:\n" + stats);
            assertTrue(statSum(stats, "serialUnauditedTick") >= 1,
                    "dispenser ticks must force the serial fallback; window:\n" + stats);

            // The fallback is per phase, not sticky: a normal repeater bank still
            // dispatches in parallel afterwards.
            buildBank(h, y, 32, 47, 120);
            buildBank(h, y, 80, 87, 120);
            int mark2 = h.mark();
            h.step(6);
            String stats2 = h.textSinceQuiet(mark2, 800, 15_000);
            assertTrue(statSum(stats2, "parallel") >= 1,
                    "audited bank must still run in parallel after an unaudited phase; window:\n" + stats2);
            for (int x = 32; x <= 47; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 118 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",118) should carry 15; tail:\n" + h.tail(1200));
            }
            for (int x = 80; x <= 87; x++) {
                assertTrue(h.blockMatches(x + " " + y + " " + 118 + " minecraft:redstone_wire[power=15]"),
                        "wire (" + x + ",118) should carry 15; tail:\n" + h.tail(1200));
            }

            String all = h.tail(500_000);
            assertFalse(all.contains("from multiple threads"),
                    "threading violation detected; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
            assertFalse(all.contains("Thread dumps"),
                    "threading violation detected; tail:\n" + all.substring(Math.max(0, all.length() - 4000)));
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private static void buildDispensers(RedstoneServerHarness h, int y, int x0, int x1, int z)
            throws IOException {
        for (int x = x0; x <= x1; x++) {
            h.cmd("setblock " + x + " " + y + " " + z + " minecraft:dispenser[facing=up]");
        }
        for (int x = x0; x <= x1; x++) {
            h.cmd("setblock " + x + " " + y + " " + (z + 1) + " minecraft:redstone_block");
        }
    }

    /**
     * Repeater bank: repeaters at {@code sourceZ - 1} facing south, sources at
     * {@code sourceZ} last, and a wire row at {@code sourceZ - 2} directly over the
     * repeater outputs (every wire is fed 15 by its own repeater).
     */
    private static void buildBank(RedstoneServerHarness h, int y, int x0, int x1, int sourceZ)
            throws IOException {
        for (int x = x0; x <= x1; x++) {
            h.cmd("setblock " + x + " " + y + " " + (sourceZ - 2) + " minecraft:redstone_wire");
        }
        for (int x = x0; x <= x1; x++) {
            h.cmd("setblock " + x + " " + y + " " + (sourceZ - 1) + " minecraft:repeater[facing=south]");
        }
        for (int x = x0; x <= x1; x++) {
            h.cmd("setblock " + x + " " + y + " " + sourceZ + " minecraft:redstone_block");
        }
    }

    private static long statSum(String text, String field) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "=(\\d+)").matcher(text);
        long sum = 0;
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static BlockPos pos(int x, int z) {
        return new BlockPos(x, CIRCUIT_Y, z);
    }

    /**
     * Applies one synthetic-chunk transition through the real write path. The light
     * precondition guarantees LevelChunk.setBlockState never reaches the light-engine
     * dereference (the stand-in level has no chunk source), and flags 512 skip every
     * placement side effect so only the write and the cache hook run.
     */
    private static void change(LevelChunk chunk, BlockPos cell, BlockState next) {
        BlockState previous = chunk.getBlockState(cell);
        assertFalse(LightEngine.hasDifferentLightProperties(previous, next),
                "test pair must keep identical light properties: " + previous + " -> " + next);
        assertNotNull(chunk.setBlockState(cell, next, 512),
                "transition must write a new state: " + previous + " -> " + next);
    }

    private static LongOpenHashSet expectedPositions(BlockPos... cells) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (BlockPos cell : cells) {
            set.add(cell.asLong());
        }
        return set;
    }

    /**
     * ServerLevel allocated without a constructor so the chunk write path can run
     * standalone: only the fields that path reads (dimension key, dimension type and
     * the paletted-container factory) are filled in.
     */
    private static ServerLevel syntheticLevel() {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            Field dimension = Level.class.getDeclaredField("dimension");
            dimension.setAccessible(true);
            dimension.set(level, Level.OVERWORLD);
            level.dimensionTypeRegistration = Holder.direct(overworldDimensionType());
            level.palettedContainerFactory = new PalettedContainerFactory(
                    Strategy.<BlockState>createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                    Blocks.AIR.defaultBlockState(), null, null, null, null);
            return level;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate a ServerLevel for the cache test", e);
        }
    }

    /** Overworld-like bounds (minY -64, height 384) so the chunk gets 24 sections. */
    private static DimensionType overworldDimensionType() {
        return new DimensionType(
                false, true, false, false, 1.0, -64, 384, 384,
                HolderSet.empty(), 0.0F,
                new DimensionType.MonsterSettings(UniformInt.of(0, 7), 0),
                DimensionType.Skybox.OVERWORLD, CardinalLighting.Type.DEFAULT,
                EnvironmentAttributeMap.EMPTY, HolderSet.empty(), Optional.empty());
    }

    /**
     * Real LevelChunk over the synthetic level. All 24 sections are pre-built (a null
     * entry would make the chunk constructor build biomes through the null half of the
     * factory), and stone anchors keep section 4 permanently non-empty so the write
     * path never asks the absent chunk source about section emptiness. The anchors sit
     * at x/z 14-15, outside every cell the tests touch.
     */
    private static LevelChunk syntheticChunk(ServerLevel level, ChunkPos chunkPos) {
        LevelChunkSection[] sections = new LevelChunkSection[24];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(
                    new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                            Strategy.<BlockState>createForBlockStates(Block.BLOCK_STATE_REGISTRY)),
                    null);
        }
        sections[(CIRCUIT_Y - level.getMinY()) >> 4].setBlockState(14, CIRCUIT_Y & 15, 15,
                Blocks.STONE.defaultBlockState());
        sections[(CIRCUIT_Y - level.getMinY()) >> 4].setBlockState(15, CIRCUIT_Y & 15, 14,
                Blocks.STONE.defaultBlockState());
        return new LevelChunk(level, chunkPos, UpgradeData.EMPTY, new LevelChunkTicks<Block>(),
                new LevelChunkTicks<Fluid>(), 0L, sections, null, null);
    }
}
