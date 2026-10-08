package org.veltismc.world.nms;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.region.RegionImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Minecraft-facing half of the Veltis world engine integration.
 *
 * <p><b>Architectural boundary.</b> Minecraft owns loading, simulation
 * distance, and ticking eligibility: which chunk columns exist and which of
 * them Minecraft block-ticks this tick is decided entirely by vanilla's
 * {@code DistanceManager} and chunk state. Veltis owns region ownership,
 * scheduling, and simulation execution: what runs inside a chunk and when a
 * region's simulation tick happens. This class only mirrors the former into
 * the latter — it never creates Veltis chunks or regions, never asks the
 * engine about chunk activity, and never mutates region state directly from
 * the server thread.
 *
 * <p><b>Wiring.</b> {@link #install} is called once the world engine has
 * started (see {@code VeltisBootstrap}); it creates one Veltis world per
 * {@link ServerLevel}, named after its dimension resource key (e.g.
 * {@code minecraft:overworld}), and installs a per-level callback that
 * {@code ServerLevel.tick()} invokes every tick right after
 * {@code getChunkSource().tick(haveTime, true)} — the point at which vanilla
 * has finished this tick's DistanceManager/chunk-state updates. Shutdown
 * clears all callbacks and bindings.
 */
public final class VeltisWorldIntegration {

    private static final Logger LOG = LogManager.getLogger(VeltisWorldIntegration.class);

    private final NmsWorldEngineBridge bridge;
    private final AtomicBoolean syncFailureLogged = new AtomicBoolean();

    VeltisWorldIntegration(NmsWorldEngineBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    /**
     * Binds every current ServerLevel to its own Veltis world and installs the
     * per-level activity hook. Call after the world engine is running; no
     * worlds are created before that.
     *
     * @param engine the running world engine
     * @param minecraftServer the {@code MinecraftServer} instance (typed as
     *     {@code Object} only so callers outside the NMS layer stay
     *     MC-free); cast happens here, where real types are used directly
     */
    public static VeltisWorldIntegration install(WorldEngine engine, Object minecraftServer) {
        Objects.requireNonNull(engine, "engine");
        MinecraftServer server = (MinecraftServer) Objects.requireNonNull(minecraftServer, "minecraftServer");
        VeltisWorldIntegration integration =
                new VeltisWorldIntegration(new NmsWorldEngineBridge(engine));
        for (ServerLevel level : server.getAllLevels()) {
            integration.bind(level);
        }
        return integration;
    }

    /**
     * Binds one level: creates (or reuses) its single Veltis world — named
     * deterministically from the dimension resource key — and installs the
     * per-tick activity hook. Repeated calls for the same level do not create
     * duplicate bindings or worlds.
     */
    public void bind(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        bridge.bind(worldName(level), level);
        level.veltisActivitySync = this::syncActiveChunks;
    }

    /** Removes one level's hook and binding; the Veltis world stays engine-owned. */
    public void unbind(ServerLevel level) {
        if (level == null) {
            return;
        }
        level.veltisActivitySync = null;
        bridge.unbind(level);
    }

    /** Clears every level hook and binding (Veltis shutdown). Worlds are not touched. */
    public void shutdown() {
        for (NmsWorldAdapter adapter : bridge.adapters()) {
            adapter.serverLevel().veltisActivitySync = null;
        }
        bridge.clear();
    }

    /**
     * Mirrors this tick's Minecraft ticking-chunk set into Veltis activity for
     * one level. Invoked from {@code ServerLevel.tick()} after the chunk
     * source has run, so vanilla's activity state for this tick is final.
     *
     * <p>Runs every server tick but only walks the chunks vanilla itself
     * block-ticks ({@code ChunkMap.forEachBlockTickingChunk} — the same
     * iteration vanilla's own {@code tickChunk} pass uses), never all loaded
     * chunks and never based on player positions. Any failure is contained
     * here so the vanilla tick cannot be taken down by it.
     */
    void syncActiveChunks(ServerLevel level) {
        try {
            NmsWorldAdapter adapter = bridge.adapter(level);
            if (adapter == null) {
                return;
            }
            WorldImpl world = (WorldImpl) adapter.engineWorld();
            LongOpenHashSet active = new LongOpenHashSet();
            // Minecraft determines which chunks are block-ticking this tick via
            // ChunkMap.forEachBlockTickingChunk - the same iteration vanilla's
            // own tickChunk pass uses. We mirror this state into Veltis.
            level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
                var pos = chunk.getPos();
                active.add(new ChunkPos(pos.x(), pos.z()).key());
            });
            syncActivity(world, active);
            // After syncing activity, ensure chunks marked active are properly
            // loaded and associated with their regions. Chunks that are now
            // active but not yet loaded should be handled by the normal chunk
            // loading lifecycle; Veltis does not override Minecraft's chunk
            // loading mechanism.
        } catch (Throwable t) {
            if (syncFailureLogged.compareAndSet(false, true)) {
                LOG.error("[VeltisMC] Chunk activity sync failed; further failures are suppressed", t);
            }
        }
    }

    /**
     * Applies an observed ticking set (Veltis {@link ChunkPos#key()} values)
     * to a Veltis world. Pure engine-side logic with no Minecraft types:
     * only already-loaded Veltis chunks inside existing regions are ever
     * marked — a key without a loaded chunk or region is skipped, so the
     * activity bridge can never lazily create anything. The actual mutation is
     * deferred to a region-bound {@link ChunkActivitySyncJob} so region state
     * changes on its owner thread.
     */
    static void syncActivity(WorldImpl world, LongSet activeKeys) {
        Map<RegionImpl, LongSet> desired = new HashMap<>();
        for (long key : activeKeys) {
            ChunkPos pos = ChunkPos.ofKey(key);
            RegionImpl region = world.regionFor(RegionPos.containing(world.config().regionSizeChunks(), pos));
            if (region == null || region.chunkIfPresent(pos) == null) {
                continue; // no region / no loaded Veltis chunk: never create one from activity
            }
            desired.computeIfAbsent(region, r -> new LongOpenHashSet()).add(key);
        }

        for (RegionImpl region : world.regionsSnapshot()) {
            LongSet want = desired.get(region);
            List<Chunk> current = region.activeChunksSnapshot();
            if (want == null && current.isEmpty()) {
                continue;
            }
            LongSet have = new LongOpenHashSet(current.size());
            for (Chunk chunk : current) {
                have.add(chunk.pos().key());
            }
            if (have.equals(want)) {
                continue; // already in sync this tick
            }
            region.submit(new ChunkActivitySyncJob(region, want == null ? LongSets.EMPTY_SET : want));
        }
    }

    /** The Veltis world name of a level: its dimension identifier (e.g. {@code minecraft:overworld}). */
    static String worldName(ServerLevel level) {
        return worldName(level.dimension());
    }

    /** Deterministic world name for a dimension key; distinct dimensions yield distinct names. */
    static String worldName(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }
}
