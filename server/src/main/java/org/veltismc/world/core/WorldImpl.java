package org.veltismc.world.core;

import org.veltismc.world.api.ChunkDataCodec;
import org.veltismc.world.api.ChunkDelta;
import org.veltismc.world.api.ChunkGenerator;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkStore;
import org.veltismc.world.api.GenerationPipeline;
import org.veltismc.world.api.LightingEngine;
import org.veltismc.world.api.PoolStats;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.SaveService;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldSimulation;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.chunk.ChunkHandleImpl;
import org.veltismc.world.chunk.ChunkSection;
import org.veltismc.world.chunk.DeltaApplyJob;
import org.veltismc.world.generation.FlatWorldGenerator;
import org.veltismc.world.generation.GenerationPipelineImpl;
import org.veltismc.world.io.ChunkDataCodecImpl;
import org.veltismc.world.io.MemoryChunkStore;
import org.veltismc.world.io.SaveServiceImpl;
import org.veltismc.world.lighting.LightingEngineImpl;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.scheduler.RegionSchedulerImpl;
import org.veltismc.world.simulation.WorldSimulationImpl;
import org.veltismc.world.util.ObjectPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's world implementation. Owns regions (created lazily, registered
 * with the scheduler), the chunk/section memory pools, the save pipeline, the
 * generation pipeline, lighting, and simulation. All cross-package internals are
 * exposed here; the public {@link World} API remains the only external surface.
 *
 * <p>Internal class: not part of the public API. Public only because several
 * packages cooperate on a world's internals.
 */
public final class WorldImpl implements World {

    private final String name;
    private final WorldConfig config;
    private final RegionSchedulerImpl scheduler;
    private final DefaultWorldEngine engine;
    private final ConcurrentHashMap<Long, RegionImpl> regions = new ConcurrentHashMap<>();

    private final ObjectPool<Chunk> chunkPool;
    private final ObjectPool<ChunkSection> sectionPool;
    private final SaveServiceImpl saves;
    private final ChunkStore store;
    private final ChunkDataCodec codec;
    private final GenerationPipelineImpl generation;
    private final LightingEngineImpl lighting;
    private final WorldSimulationImpl simulation;

    private volatile ChunkGenerator generator;

    WorldImpl(String name, WorldConfig config, RegionSchedulerImpl scheduler, DefaultWorldEngine engine) {
        this.name = name;
        this.config = config;
        this.scheduler = scheduler;
        this.engine = engine;
        this.chunkPool = new ObjectPool<>(Chunk::new, config.maxPooledChunks());
        this.sectionPool = new ObjectPool<>(ChunkSection::new, config.maxPooledSections());
        this.saves = new SaveServiceImpl(this);
        this.store = new MemoryChunkStore();
        this.codec = new ChunkDataCodecImpl();
        this.generation = new GenerationPipelineImpl(this);
        this.lighting = new LightingEngineImpl(this);
        this.simulation = new WorldSimulationImpl(this);
        this.generator = new FlatWorldGenerator(
            config.minSectionY() * 16,
            (config.minSectionY() + config.sectionCount()) * 16);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public WorldConfig config() {
        return config;
    }

    @Override
    public Region region(ChunkPos pos) {
        return regionImpl(pos);
    }

    /** Returns the region owning a chunk, creating and registering it if needed. */
    public RegionImpl regionImpl(ChunkPos pos) {
        RegionPos rp = RegionPos.containing(config.regionSizeChunks(), pos);
        return regions.computeIfAbsent(rp.key(), k -> {
            RegionImpl region = new RegionImpl(this, rp);
            scheduler.registerRegion(region);
            if (simulation.isRunning()) {
                region.setActive(true);
                simulationImpl().scheduleRegionTick(region);
            }
            return region;
        });
    }

    /** Returns an existing region without creating one. */
    public RegionImpl regionFor(RegionPos pos) {
        return regions.get(pos.key());
    }

    public List<RegionImpl> regionsSnapshot() {
        return new ArrayList<>(regions.values());
    }

    @Override
    public ChunkHandle chunk(ChunkPos pos) {
        return new ChunkHandleImpl(regionImpl(pos).chunk(pos));
    }

    /** Returns the loaded chunk at a position, or {@code null}. Never creates. */
    public Chunk chunkIfPresent(ChunkPos pos) {
        RegionImpl region = regionFor(RegionPos.containing(config.regionSizeChunks(), pos));
        return region == null ? null : region.chunkIfPresent(pos);
    }

    /** Routes an immutable delta to its owning region's inbox. */
    public void routeDelta(ChunkDelta delta) {
        org.veltismc.world.api.ChunkPos cp = null;
        if (delta instanceof org.veltismc.world.api.BlockDelta bd) {
            cp = bd.chunk();
        } else if (delta instanceof org.veltismc.world.api.LightDelta ld) {
            cp = ld.chunk();
        }
        if (cp == null) {
            return;
        }
        RegionImpl target = regionFor(RegionPos.containing(config.regionSizeChunks(), cp));
        if (target == null) {
            return;
        }
        target.submit(new DeltaApplyJob(this, delta));
    }

    @Override
    public WorldSimulation simulation() {
        return simulation;
    }

    @Override
    public LightingEngine lighting() {
        return lighting;
    }

    @Override
    public SaveService saves() {
        return saves;
    }

    @Override
    public GenerationPipeline generation() {
        return generation;
    }

    @Override
    public void setGenerator(ChunkGenerator generator) {
        this.generator = generator;
    }

    @Override
    public ChunkGenerator generator() {
        return generator;
    }

    @Override
    public void close() {
        simulation.stop();
        saves.close();
        try {
            saves.flush().join();
        } catch (Exception e) {
            System.err.println("[veltis-world-engine] save flush interrupted for world '" + name + "': " + e);
        }
        for (RegionImpl region : regionsSnapshot()) {
            scheduler.unregisterRegion(region);
        }
        regions.clear();
    }

    /** Internal accessors used by jobs across packages. */
    public RegionSchedulerImpl scheduler() {
        return scheduler;
    }

    public ChunkStore store() {
        return store;
    }

    public ChunkDataCodec codec() {
        return codec;
    }

    public ObjectPool<Chunk> chunkPool() {
        return chunkPool;
    }

    public ObjectPool<ChunkSection> sectionPool() {
        return sectionPool;
    }

    public SaveServiceImpl savesImpl() {
        return saves;
    }

    public GenerationPipelineImpl generationImpl() {
        return generation;
    }

    public LightingEngineImpl lightingImpl() {
        return lighting;
    }

    public WorldSimulationImpl simulationImpl() {
        return simulation;
    }

    public void recordMigration() {
        engine.recordMigration();
    }

    public Map<String, PoolStats> poolStats() {
        Map<String, PoolStats> out = new HashMap<>();
        out.put("chunks", new PoolStats(chunkPool.created(), chunkPool.inUse()));
        out.put("sections", new PoolStats(sectionPool.created(), sectionPool.inUse()));
        return out;
    }

    public int pendingChunkLoads() {
        int count = 0;
        for (RegionImpl region : regionsSnapshot()) {
            for (Chunk chunk : region.chunksSnapshot()) {
                if (chunk.state() == org.veltismc.world.api.ChunkState.LOADING) {
                    count++;
                }
            }
        }
        return count;
    }

    public int pendingChunkUnloads() {
        int count = 0;
        for (RegionImpl region : regionsSnapshot()) {
            for (Chunk chunk : region.chunksSnapshot()) {
                if (chunk.isUnloadRequested()) {
                    count++;
                }
            }
        }
        return count;
    }
}
