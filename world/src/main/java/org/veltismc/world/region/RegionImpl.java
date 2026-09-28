package org.veltismc.world.region;

import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.Region;
import org.veltismc.world.api.RegionJob;
import org.veltismc.world.api.RegionPos;
import org.veltismc.world.api.RegionScheduler;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.chunk.ChunkHandleImpl;
import org.veltismc.world.core.WorldImpl;
import org.veltismc.world.entity.EntityHandleImpl;
import org.veltismc.world.scheduler.JobEnvelope;
import org.veltismc.world.scheduler.JobHandleImpl;
import org.veltismc.world.scheduler.RegionInbox;
import org.veltismc.world.scheduler.RegionSchedulerImpl;
import org.veltismc.world.simulation.BlockUpdateJob;
import org.veltismc.world.util.LockFreeQueue;
import org.veltismc.world.util.ObjectPool;
import org.veltismc.world.util.SeedHash;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A world region: the ownership boundary of the engine.
 *
 * <p>Exactly one worker owns this region at any time. Only that worker may
 * mutate the region's chunks, entities, and simulation state. Cross-region work
 * arrives as {@link RegionMessage}s in the inbox and is executed by the owner.
 */
public final class RegionImpl implements Region, RegionInbox {

    private final RegionPos pos;
    private final WorldImpl world;
    private final RegionSchedulerImpl scheduler;
    private final long seed;
    private final LockFreeQueue<JobEnvelope> inbox = new LockFreeQueue<>();
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final LockFreeQueue<BlockUpdateJob> updateQueue = new LockFreeQueue<>();
    private final TreeMap<Long, EntityHandleImpl> entities = new TreeMap<>();
    private final AtomicBoolean tickScheduled = new AtomicBoolean();

    private volatile boolean active;

    public RegionImpl(WorldImpl world, RegionPos pos) {
        this.world = world;
        this.pos = pos;
        this.scheduler = world.scheduler();
        this.seed = SeedHash.hash(world.config().worldSeed(), pos.x(), pos.z());
        this.active = world.simulation().isRunning();
    }

    @Override
    public RegionPos pos() {
        return pos;
    }

    @Override
    public WorldImpl world() {
        return world;
    }

    @Override
    public RegionScheduler scheduler() {
        return scheduler;
    }

    @Override
    public boolean owns(ChunkPos p) {
        return RegionPos.containing(world.config().regionSizeChunks(), p).equals(pos);
    }

    @Override
    public List<ChunkHandle> loadedChunks() {
        List<ChunkHandle> out = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk.isLoaded()) {
                out.add(new ChunkHandleImpl(chunk));
            }
        }
        return out;
    }

    @Override
    public int pendingMessages() {
        return inbox.size();
    }

    @Override
    public String ownerName() {
        return scheduler.ownerName(pos);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public JobHandleImpl submit(RegionJob job) {
        return (JobHandleImpl) scheduler.schedule(this, job);
    }

    /** Delivers a cross-region message into this region's inbox. */
    public void deliver(RegionMessage message) {
        submit(message);
    }

    /** Returns the chunk for a position, creating it (and pooling its sections) on first use. */
    public Chunk chunk(ChunkPos chunkPos) {
        return chunks.computeIfAbsent(chunkPos.key(), k -> {
            WorldConfig cfg = world.config();
            ObjectPool<Chunk> chunkPool = world.chunkPool();
            Chunk chunk = chunkPool.borrow();
            chunk.init(this, chunkPos, cfg.minSectionY(), cfg.sectionCount(), world.sectionPool());
            return chunk;
        });
    }

    /** Returns the chunk if it exists and is loaded, without creating it. */
    public Chunk chunkIfPresent(ChunkPos chunkPos) {
        Chunk chunk = chunks.get(chunkPos.key());
        return chunk != null && chunk.isLoaded() ? chunk : null;
    }

    /** Removes a chunk from the region and returns it to the chunk pool. */
    public void removeChunk(Chunk chunk) {
        chunks.remove(chunk.pos().key());
        world.lightingImpl().onChunkUnloaded(chunk.pos());
        chunk.releaseSections();
        world.chunkPool().release(chunk);
    }

    public LockFreeQueue<JobEnvelope> inbox() {
        return inbox;
    }

    /** Queue of pending block updates drained by the region tick. */
    public LockFreeQueue<BlockUpdateJob> updateQueue() {
        return updateQueue;
    }

    /** Deterministic per-region seed for simulation randomness. */
    public long seed() {
        return seed;
    }

    /** Whether the calling thread is the worker currently owning this region. */
    public boolean isOwnerThread() {
        Thread owner = scheduler.ownerThread(pos);
        return owner != null && owner == Thread.currentThread();
    }

    /** Number of loaded chunks owned by this region (diagnostics). */
    public int loadedChunkCount() {
        return chunks.size();
    }

    /** Snapshot of loaded chunks (internal, for save/unload scans). */
    public List<Chunk> chunksSnapshot() {
        return new ArrayList<>(chunks.values());
    }

    /** Owner-thread-only entity registry (ascending id order for deterministic ticks). */
    public void addEntity(EntityHandleImpl handle) {
        entities.put(handle.id(), handle);
    }

    /** Owner-thread-only removal from the registry. */
    public void removeEntity(EntityHandleImpl handle) {
        entities.remove(handle.id());
    }

    /** Owner-thread-only view of the registry, sorted by ascending id. */
    public TreeMap<Long, EntityHandleImpl> entities() {
        return entities;
    }

    public int entityCount() {
        return entities.size();
    }

    /** Marks the region's tick job as scheduled (prevents double ticking). */
    public boolean tryMarkTickScheduled() {
        return tickScheduled.compareAndSet(false, true);
    }

    public void clearTickScheduled() {
        tickScheduled.set(false);
    }

    public boolean isTickScheduled() {
        return tickScheduled.get();
    }
}
