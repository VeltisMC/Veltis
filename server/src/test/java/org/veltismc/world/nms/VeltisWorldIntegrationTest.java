package org.veltismc.world.nms;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockSimulator;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.SimulationContext;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.region.RegionImpl;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The activity-sync half of the integration: Minecraft's ticking set is
 * mirrored into Veltis regions (correct region, inactive chunks excluded,
 * nothing ever created), and {@code RegionTickJob} consumes that state by
 * ticking active chunks only.
 */
class VeltisWorldIntegrationTest {

    private static WorldConfig testConfig() {
        return WorldConfig.builder()
                .regionSizeChunks(2)
                .minWorkers(2)
                .maxWorkers(4)
                .adaptiveWorkers(false)
                .simTickIntervalMillis(10)
                .workerParkTimeoutNanos(50_000L)
                .watchdogIntervalMillis(200)
                .longJobThresholdMillis(1_000)
                .saveFlushIntervalMillis(50)
                .saveBatchSize(64)
                .build();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2);
        }
        fail(message);
    }

    private static LongSet keys(ChunkPos... positions) {
        LongOpenHashSet set = new LongOpenHashSet(positions.length);
        for (ChunkPos pos : positions) {
            set.add(pos.key());
        }
        return set;
    }

    private static boolean activeIn(RegionImpl region, int x, int z) {
        for (Chunk chunk : region.activeChunksSnapshot()) {
            if (chunk.pos().x() == x && chunk.pos().z() == z) {
                return true;
            }
        }
        return false;
    }

    private static ChunkHandle loadChunk(World world, int x, int z) throws Exception {
        ChunkHandle handle = world.chunk(new ChunkPos(x, z));
        handle.load().get(15, TimeUnit.SECONDS);
        return handle;
    }

    @BeforeAll
    static void bootstrap() {
        MinecraftTestSupport.bootstrapMinecraft();
    }

    @Test
    void worldNamesFollowDimensionKeys() {
        assertEquals("minecraft:overworld", VeltisWorldIntegration.worldName(Level.OVERWORLD));
        assertEquals("minecraft:the_nether", VeltisWorldIntegration.worldName(Level.NETHER));
        assertEquals("minecraft:the_end", VeltisWorldIntegration.worldName(Level.END));
        assertFalse(VeltisWorldIntegration.worldName(Level.OVERWORLD)
                .equals(VeltisWorldIntegration.worldName(Level.NETHER)),
                "distinct dimensions must yield distinct world names");
    }

    @Test
    void bindInstallsTheTickHookAndShutdownClearsIt() {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            NmsWorldEngineBridge bridge = new NmsWorldEngineBridge(engine);
            VeltisWorldIntegration integration = new VeltisWorldIntegration(bridge);
            ServerLevel level = MinecraftTestSupport.newServerLevel();

            integration.bind(level);
            assertNotNull(level.veltisActivitySync, "bind must install the per-tick hook");
            assertNotNull(bridge.adapter(level));
            assertEquals("minecraft:overworld", bridge.adapter(level).engineWorld().name());

            integration.bind(level); // again: same level must not duplicate anything
            assertEquals(1, engine.worlds().size(), "binding twice must not create a second world");

            integration.unbind(level);
            assertNull(level.veltisActivitySync, "unbind must remove the hook");
            assertNull(bridge.adapter(level), "unbind must remove the mapping");

            integration.bind(level);
            integration.shutdown();
            assertNull(level.veltisActivitySync, "shutdown must clear hooks");
            assertTrue(bridge.adapters().isEmpty(), "shutdown must clear bindings");
        } finally {
            engine.stop();
        }
    }

    @Test
    void multipleDimensionsBindToDifferentWorlds() {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            NmsWorldEngineBridge bridge = new NmsWorldEngineBridge(engine);
            VeltisWorldIntegration integration = new VeltisWorldIntegration(bridge);

            ServerLevel overworld = MinecraftTestSupport.newServerLevel(Level.OVERWORLD);
            ServerLevel nether = MinecraftTestSupport.newServerLevel(Level.NETHER);
            integration.bind(overworld);
            integration.bind(nether);

            assertEquals(2, engine.worlds().size(), "each dimension must get its own Veltis world");
            assertNotNull(engine.world("minecraft:overworld"));
            assertNotNull(engine.world("minecraft:the_nether"));
            assertFalse(bridge.adapter(overworld).engineWorld()
                    .equals(bridge.adapter(nether).engineWorld()));
        } finally {
            engine.stop();
        }
    }

    @Test
    void syncFailureIsContainedInsideTheVanillaTickHook() {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            VeltisWorldIntegration integration =
                    new VeltisWorldIntegration(new NmsWorldEngineBridge(engine));
            ServerLevel level = MinecraftTestSupport.newServerLevel();

            // Unbound: returns without touching the level at all.
            assertDoesNotThrow(() -> integration.syncActiveChunks(level));

            // Bound but with no chunk source (no real world behind it): the
            // failure must be caught here so the vanilla tick can never crash.
            integration.bind(level);
            assertDoesNotThrow(() -> integration.syncActiveChunks(level));
        } finally {
            engine.stop();
        }
    }

    @Test
    void activityEntersTheCorrectRegionAndSkipsInactiveChunks() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            // regionSizeChunks(2): region (0,0) owns chunks (0,0)-(1,0),
            // region (2,0) owns (4,0)-(5,0).
            WorldImpl world = (WorldImpl) engine.createWorld("sync", testConfig());
            loadChunk(world, 0, 0);
            loadChunk(world, 1, 0);
            loadChunk(world, 5, 0);

            RegionImpl region00 = world.regionFor(new RegionPos(0, 0));
            RegionImpl region20 = world.regionFor(new RegionPos(2, 0));
            assertNotNull(region00);
            assertNotNull(region20);

            VeltisWorldIntegration.syncActivity(world, keys(new ChunkPos(0, 0), new ChunkPos(5, 0)));

            await(() -> activeIn(region00, 0, 0), 5_000, "chunk (0,0) must be active in region (0,0)");
            await(() -> activeIn(region20, 5, 0), 5_000, "chunk (5,0) must be active in region (2,0)");
            assertEquals(1, region00.activeChunksSnapshot().size(), "only the ticking chunk is active");
            assertEquals(1, region20.activeChunksSnapshot().size(), "only the ticking chunk is active");
            assertFalse(activeIn(region00, 1, 0), "loaded-but-not-ticking chunk must stay inactive");
        } finally {
            engine.stop();
        }
    }

    @Test
    void activityNeverCreatesChunksOrRegions() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            WorldImpl world = (WorldImpl) engine.createWorld("ghost", testConfig());
            loadChunk(world, 0, 0);
            RegionImpl region00 = world.regionFor(new RegionPos(0, 0));
            assertNotNull(region00);

            // (1,0): inside an existing region but never loaded.
            // (100,100): no region, no chunk, anywhere.
            VeltisWorldIntegration.syncActivity(world,
                    keys(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(100, 100)));

            await(() -> activeIn(region00, 0, 0) && region00.activeChunksSnapshot().size() == 1,
                    5_000, "the loaded chunk must be marked active");
            assertNull(world.chunkIfPresent(new ChunkPos(1, 0)),
                    "activity must not create a Veltis chunk");
            assertNull(world.chunkIfPresent(new ChunkPos(100, 100)),
                    "activity must not create a Veltis chunk");
            assertNull(world.regionFor(RegionPos.containing(2, new ChunkPos(100, 100))),
                    "activity must not create regions");
            assertEquals(1, world.regionsSnapshot().size());
        } finally {
            engine.stop();
        }
    }

    @Test
    void deactivatedChunksLeaveActivity() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            WorldImpl world = (WorldImpl) engine.createWorld("off", testConfig());
            loadChunk(world, 0, 0);
            loadChunk(world, 1, 0);
            RegionImpl region00 = world.regionFor(new RegionPos(0, 0));
            assertNotNull(region00);

            VeltisWorldIntegration.syncActivity(world, keys(new ChunkPos(0, 0), new ChunkPos(1, 0)));
            await(() -> region00.activeChunksSnapshot().size() == 2, 5_000,
                    "both chunks must start active");

            VeltisWorldIntegration.syncActivity(world, keys(new ChunkPos(0, 0)));
            await(() -> region00.activeChunksSnapshot().size() == 1 && !activeIn(region00, 1, 0),
                    5_000, "the chunk Minecraft stopped ticking must leave activity");
            assertTrue(activeIn(region00, 0, 0), "the still-ticking chunk stays active");

            VeltisWorldIntegration.syncActivity(world, LongSets.EMPTY_SET);
            await(() -> region00.activeChunksSnapshot().isEmpty(), 5_000,
                    "an empty ticking set must clear activity");
        } finally {
            engine.stop();
        }
    }

    @Test
    void unloadingAChunkRemovesItFromActivity() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            WorldImpl world = (WorldImpl) engine.createWorld("bye", testConfig());
            ChunkHandle chunk = loadChunk(world, 0, 0);
            RegionImpl region00 = world.regionFor(new RegionPos(0, 0));
            assertNotNull(region00);

            VeltisWorldIntegration.syncActivity(world, keys(new ChunkPos(0, 0)));
            await(() -> activeIn(region00, 0, 0), 5_000, "chunk must be active after sync");

            chunk.unload().get(15, TimeUnit.SECONDS);
            await(() -> region00.activeChunksSnapshot().isEmpty(), 5_000,
                    "unloading a Veltis chunk must remove it from activeChunks");
        } finally {
            engine.stop();
        }
    }

    @Test
    void regionTickOnlyTicksActiveChunks() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            WorldImpl world = (WorldImpl) engine.createWorld("sim", testConfig());
            // Solid terrain for the whole vertical range, so every random-tick
            // attempt lands on a non-air block and ticks are observable on the
            // first eligible region tick instead of probabilistically.
            WorldConfig cfg = testConfig();
            world.setGenerator(new SolidGenerator(
                    cfg.minSectionY() * 16, (cfg.minSectionY() + cfg.sectionCount()) * 16));
            loadChunk(world, 0, 0);
            loadChunk(world, 1, 0);

            TickRecorder recorder = new TickRecorder();
            world.simulation().registerBlockSimulator(recorder);
            world.simulation().start();

            // The region tick runs, but neither chunk has been marked active:
            // nothing may be ticked.
            Thread.sleep(500);
            assertEquals(0, recorder.count.get(), "inactive chunks must not be ticked");

            // Activate only chunk (0,0); it must start receiving random ticks,
            // and everything observed must come from that chunk alone.
            VeltisWorldIntegration.syncActivity(world, keys(new ChunkPos(0, 0)));
            RegionImpl region00 = world.regionFor(new RegionPos(0, 0));
            assertNotNull(region00);
            await(() -> activeIn(region00, 0, 0), 5_000, "sync must mark chunk (0,0) active");
            assertEquals(1, world.simulation().activeRegions(), "exactly one region must be active");
            await(() -> recorder.count.get() > 0, 10_000,
                    "an active chunk must receive random ticks");
            for (BlockPos pos : recorder.positions) {
                assertTrue(pos.x() >= 0 && pos.x() < 16 && pos.z() >= 0 && pos.z() < 16,
                        "only chunk (0,0) is active, but " + pos + " was ticked");
            }
        } finally {
            engine.stop();
        }
    }

    /** Fills every block of every generated chunk, so random ticks always hit. */
    private static final class SolidGenerator implements org.veltismc.world.api.ChunkGenerator {
        private final int minY;
        private final int maxYExclusive;

        private SolidGenerator(int minY, int maxYExclusive) {
            this.minY = minY;
            this.maxYExclusive = maxYExclusive;
        }

        @Override
        public void apply(org.veltismc.world.api.GenerationStage stage,
                          org.veltismc.world.api.GenerationContext ctx) {
            if (stage != org.veltismc.world.api.GenerationStage.NOISE) {
                return;
            }
            int baseX = ctx.pos().x() * 16;
            int baseZ = ctx.pos().z() * 16;
            BlockState stone = new BlockState(1, 0, true);
            for (int y = minY; y < maxYExclusive; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        ctx.setBlock(new BlockPos(baseX + x, y, baseZ + z), stone);
                    }
                }
            }
        }
    }

    /** Records random-tick callbacks the engine delivers. */
    private static final class TickRecorder implements BlockSimulator {
        final AtomicInteger count = new AtomicInteger();
        final CopyOnWriteArrayList<BlockPos> positions = new CopyOnWriteArrayList<>();

        @Override
        public void onRandomTick(SimulationContext ctx, BlockPos pos, BlockState state) {
            count.incrementAndGet();
            positions.add(pos);
        }
    }
}
