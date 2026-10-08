package org.veltismc.world.simulation;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.BlockSimulator;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.entity.EntityHandleImpl;
import org.veltismc.world.entity.EntityMigrationMessage;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.util.SeedHash;

import java.util.ArrayList;

/**
 * The recurring simulation tick of one region, executed by the region's owner
 * worker. Random ticks, block updates, and entity ticks all happen here, which
 * keeps the region's simulation state single-threaded and deterministic.
 * Reschedules itself as a delayed job while simulation is running.
 * <p>
 * <b>Ownership model:</b> This job only processes {@link Chunk}s that appear in
 * {@link RegionImpl#activeChunksSnapshot()}. Chunks in activeChunks are
 * Veltis-owned; Minecraft must skip ticking those chunks to prevent
 * double-ticking. The{@link #tick(long)} method consumes the activity state
 * supplied by the NMS integration and never determines activity itself.
 */
public final class RegionTickJob implements RegionJob {

    private final WorldImpl world;
    private final RegionImpl region;
    private long tickSeq;

    public RegionTickJob(WorldImpl world, RegionImpl region) {
        this.world = world;
        this.region = region;
    }

    @Override
    public String name() {
        return "region-tick";
    }

    @Override
    public JobPriority priority() {
        return JobPriority.NORMAL;
    }

    @Override
    public boolean regionBound() {
        return true;
    }

    @Override
    public void execute(JobContext ctx) {
        region.clearTickScheduled();
        WorldSimulationImpl sim = world.simulationImpl();
        if (!sim.isRunning() || !region.isActive() || ctx.cancelled()) {
            return;
        }
        long dt = world.config().simTickIntervalMillis();
        tick(dt);
        if (sim.isRunning() && region.isActive() && !ctx.cancelled()) {
            sim.scheduleRegionTick(region);
        }
    }

    private void tick(long dtMillis) {
        tickSeq++;
        WorldConfig cfg = world.config();
        SimulationContextImpl simCtx = new SimulationContextImpl(region, region.seed());
        BlockSimulator blockSim = world.simulationImpl().simulator();

        // Minecraft decides which chunks are simulation-active (mirrored into the
        // region by the NMS activity sync); the region tick consumes that state
        // and never determines it itself.
        for (Chunk chunk : region.activeChunksSnapshot()) {
            tickChunk(cfg, simCtx, blockSim, chunk);
        }

        int drained = 0;
        while (drained < cfg.maxQueuedBlockUpdatesPerTick()) {
            BlockUpdateJob update = region.updateQueue().poll();
            if (update == null) {
                break;
            }
            if (blockSim != null) {
                Chunk c = world.chunkIfPresent(ChunkPos.containing(update.pos()));
                BlockState state = c != null && c.isLoaded() ? c.getBlock(update.pos()) : BlockState.AIR;
                blockSim.onNeighborChanged(simCtx, update.pos(), state, update.from());
            }
            drained++;
        }

        tickEntities(simCtx, dtMillis);
    }

    private void tickChunk(WorldConfig cfg, SimulationContextImpl simCtx, BlockSimulator blockSim, Chunk chunk) {
        if (chunk.state() != ChunkState.READY || !chunk.trySet(ChunkState.READY, ChunkState.SIMULATING)) {
            return;
        }
        if (blockSim != null) {
            for (int i = 0; i < cfg.randomTicksPerChunkPerTick(); i++) {
                long r = SeedHash.hash(region.seed(), chunk.pos().key(), tickSeq * 16 + i);
                int bx = (int) Math.floorMod(r, 16);
                int bz = (int) Math.floorMod(r >>> 16, 16);
                int by = cfg.minSectionY() * 16 + (int) Math.floorMod(r >>> 32, cfg.sectionCount() * 16L);
                BlockPos bp = new BlockPos(chunk.pos().x() * 16 + bx, by, chunk.pos().z() * 16 + bz);
                BlockState bs = chunk.getBlock(bp);
                if (bs.id() != 0) {
                    blockSim.onRandomTick(simCtx, bp, bs);
                }
            }
        }
        if (chunk.isUnloadRequested()) {
            // An unload was requested while this tick held the chunk: finish it
            // here on the owner. If random ticks re-dirtied the chunk this tick,
            // the CAS fails and the save pipeline performs the final unload.
            if (chunk.trySet(ChunkState.SIMULATING, ChunkState.UNLOADING)) {
                if (chunk.trySet(ChunkState.UNLOADING, ChunkState.UNLOADED)) {
                    chunk.region().removeChunk(chunk);
                }
            }
            return;
        }
        if (!chunk.isDirty()) {
            chunk.trySet(ChunkState.SIMULATING, ChunkState.READY);
        }
    }

    private void tickEntities(SimulationContextImpl simCtx, long dtMillis) {
        for (EntityHandleImpl handle : new ArrayList<>(region.entities().values())) {
            // Despawn-vs-migration protocol (H2): a handle marked while unowned is
            // purged by whichever region's owner next sees it. The ticking region
            // is always an adopter/owner, so this covers every message path.
            if (handle.isDespawnRequested()) {
                region.removeEntity(handle);
                handle.setRegion(null);
                continue;
            }
            handle.entity().tick(simCtx, dtMillis);
            BlockPos p = handle.entity().position();
            if (p == null) {
                continue;
            }
            ChunkPos cp = ChunkPos.containing(p);
            if (!region.owns(cp)) {
                region.removeEntity(handle);
                handle.setRegion(null);
                RegionImpl target = region.world().regionImpl(cp);
                target.deliver(new EntityMigrationMessage(handle));
            }
        }
    }
}