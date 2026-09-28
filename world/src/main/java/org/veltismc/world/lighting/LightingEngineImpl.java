package org.veltismc.world.lighting;

import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.LightingEngine;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link LightingEngine}. Relight requests are deduplicated; loaded
 * chunks get a region-bound {@link LightJob} (so light never races the owner),
 * while pre-READY chunks are lit by the generation pipeline's LIGHTING stage.
 * The engine never blocks simulation on light work.
 */
public final class LightingEngineImpl implements LightingEngine {

    private final WorldImpl world;
    private final ConcurrentHashMap<Long, Boolean> pending = new ConcurrentHashMap<>();

    public LightingEngineImpl(WorldImpl world) {
        this.world = world;
    }

    @Override
    public void relight(ChunkPos pos) {
        Chunk chunk = world.chunkIfPresent(pos);
        if (chunk == null || !chunk.isLoaded()) {
            return;
        }
        ChunkState s = chunk.state();
        if (s == ChunkState.GENERATING || s == ChunkState.STRUCTURES
            || s == ChunkState.BIOMES || s == ChunkState.LIGHTING) {
            return; // the generation pipeline's LIGHTING stage handles this chunk
        }
        if (pending.putIfAbsent(pos.key(), Boolean.TRUE) == null) {
            world.scheduler().schedule(chunk.region(), new LightJob(chunk));
        }
    }

    @Override
    public boolean isPending(ChunkPos pos) {
        return pending.containsKey(pos.key());
    }

    @Override
    public boolean isLit(ChunkPos pos) {
        Chunk chunk = world.chunkIfPresent(pos);
        return chunk != null && chunk.isLit();
    }

    @Override
    public int pendingCount() {
        return pending.size();
    }

    /** Called by {@link LightJob} once the light pass completed. */
    void onLightDone(ChunkPos pos) {
        pending.remove(pos.key());
    }

    /** Called by {@link LightJob} when it deferred (e.g. chunk was SAVING). */
    void onLightDeferred(ChunkPos pos) {
        // Keep the request in the set; the next relight attempt reuses it.
    }

    public void onChunkUnloaded(ChunkPos pos) {
        pending.remove(pos.key());
    }
}
