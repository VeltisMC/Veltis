package org.veltismc.world.core;

import org.junit.jupiter.api.Test;
import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.EntityHandle;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.SimulatedEntity;
import org.veltismc.world.api.SimulationContext;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;
import org.veltismc.world.api.WorldMetrics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EngineSmokeTest {

    private static WorldConfig testConfig() {
        return WorldConfig.builder()
            .regionSizeChunks(1)
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

    @Test
    void generateSetFlushReload() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            World world = engine.createWorld("flat");
            ChunkPos cp = new ChunkPos(0, 0);
            ChunkHandle chunk = world.chunk(cp);
            chunk.load().get(15, TimeUnit.SECONDS);
            assertEquals(ChunkState.READY, chunk.state());
            assertTrue(chunk.isLoaded());

            assertEquals(3, chunk.getBlock(BlockPos.of(0, 0, 0)).get(5, TimeUnit.SECONDS).id(), "grass at y=0");
            assertEquals(2, chunk.getBlock(BlockPos.of(0, 1, 0)).get(5, TimeUnit.SECONDS).id(), "dirt at y=1");
            assertEquals(1, chunk.getBlock(BlockPos.of(0, -1, 0)).get(5, TimeUnit.SECONDS).id(), "stone at y=-1");
            assertEquals(0, chunk.getBlock(BlockPos.of(0, 2, 0)).get(5, TimeUnit.SECONDS).id(), "air at y=2");

            BlockState marker = new BlockState(42, 0, false);
            chunk.setBlock(BlockPos.of(0, 1, 0), marker).get(5, TimeUnit.SECONDS);
            assertEquals(marker, chunk.getBlock(BlockPos.of(0, 1, 0)).get(5, TimeUnit.SECONDS));

            world.saves().flush().get(15, TimeUnit.SECONDS);
            assertEquals(0, world.saves().pendingSaves());

            chunk.unload().get(15, TimeUnit.SECONDS);
            assertEquals(ChunkState.UNLOADED, chunk.state());

            ChunkHandle reloaded = world.chunk(cp);
            reloaded.load().get(15, TimeUnit.SECONDS);
            assertEquals(ChunkState.READY, reloaded.state());
            assertEquals(marker, reloaded.getBlock(BlockPos.of(0, 1, 0)).get(5, TimeUnit.SECONDS), "persisted marker");

            WorldMetrics metrics = engine.metrics();
            assertTrue(metrics.workerCount() >= 2);
            assertTrue(metrics.totalJobsExecuted() > 0);
            assertTrue(metrics.jobStats().containsKey("chunk-load"));
            assertTrue(metrics.jobStats().containsKey("chunk-generation-noise"));
        } finally {
            engine.stop();
        }
        assertFalse(engine.isRunning());
    }

    @Test
    void entityMigratesAcrossRegionBoundary() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            World world = engine.createWorld("mig");
            world.simulation().start();

            ChunkHandle chunk = world.chunk(new ChunkPos(0, 0));
            chunk.load().get(15, TimeUnit.SECONDS);

            AtomicReference<BlockPos> position = new AtomicReference<>(BlockPos.of(1, 1, 1));
            SimulatedEntity mover = new SimulatedEntity() {
                @Override
                public BlockPos position() {
                    return position.get();
                }

                @Override
                public void tick(SimulationContext ctx, long dtMillis) {
                    position.set(position.get().offset(1, 0, 0));
                }
            };
            EntityHandle handle = world.simulation().spawnEntity(mover);
            assertEquals(1, world.simulation().entityCount());

            Region first = awaitRegion(handle, new RegionPos(0, 0), 10_000);
            assertNotNull(first, "entity should be adopted by region (0,0)");
            assertTrue(handle.isOwned());

            Region second = awaitRegion(handle, new RegionPos(1, 0), 15_000);
            assertNotNull(second, "entity should migrate to region (1,0) after crossing the boundary");
            assertTrue(engine.metrics().regionMigrations() >= 1);

            world.simulation().despawnEntity(handle);
            await(() -> world.simulation().entityCount() == 0, 5_000, "entity should despawn");
            assertTrue(engine.metrics().regionMigrations() >= 1);
        } finally {
            engine.stop();
        }
    }

    @Test
    void explosionClearsBlocks() throws Exception {
        WorldEngine engine = WorldEngines.create(testConfig());
        engine.start();
        try {
            World world = engine.createWorld("boom");
            ChunkHandle chunk = world.chunk(new ChunkPos(0, 0));
            chunk.load().get(15, TimeUnit.SECONDS);

            chunk.setBlock(BlockPos.of(0, 2, 0), new BlockState(1, 0, true)).get(5, TimeUnit.SECONDS);
            assertEquals(1, chunk.getBlock(BlockPos.of(0, 2, 0)).get(5, TimeUnit.SECONDS).id());

            world.simulation().explode(BlockPos.of(0, 2, 0), 1);

            await(() -> {
                CompletableFuture<BlockState> f = chunk.getBlock(BlockPos.of(0, 2, 0));
                return f.isDone() && f.getNow(BlockState.AIR).id() == 0;
            }, 10_000, "explosion should break the block at the blast center");
            await(() -> {
                CompletableFuture<BlockState> f = chunk.getBlock(BlockPos.of(0, 2, 1));
                return f.isDone() && f.getNow(BlockState.AIR).id() == 0;
            }, 10_000, "explosion should break blocks in the radius");
            assertTrue(engine.metrics().jobStats().containsKey("explosion-fragment"));
        } finally {
            engine.stop();
        }
    }

    private static Region awaitRegion(EntityHandle handle, RegionPos target, long timeoutMillis) throws Exception {
        final Region[] out = {null};
        await(() -> {
            Region r = handle.region();
            if (r != null && r.pos().equals(target)) {
                out[0] = r;
                return true;
            }
            return false;
        }, timeoutMillis, "entity never reached region " + target);
        return out[0];
    }
}
