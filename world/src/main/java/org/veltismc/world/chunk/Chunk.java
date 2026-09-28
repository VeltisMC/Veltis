package org.veltismc.world.chunk;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkSnapshot;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.region.RegionImpl;
import org.veltismc.world.util.ObjectPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A chunk column. Content is never mutated directly by outside code: delta
 * writes go through the owner thread in READY/SIMULATING states, generation and
 * lighting writes happen under an exclusive pre-READY state (CAS-guarded), and
 * persistence reads happen under the SAVING state.
 */
public final class Chunk {

    private ChunkPos pos;
    private RegionImpl region;
    private int minSectionY;
    private int sectionCount;
    private ChunkSection[] sections;
    private ObjectPool<ChunkSection> sectionPool;
    private ChunkStateMachine state = new ChunkStateMachine();

    private volatile boolean dirty;
    private volatile boolean unloadRequested;
    private volatile boolean lit;
    private volatile long lastModifiedMillis;

    /**
     * Load-cycle stamp, incremented every time the pooled instance is re-initialized.
     * Writer: the borrowing thread inside {@link #init} (under this chunk's monitor).
     * Readers: any job/handle thread, as a staleness guard. Jobs and handles capture
     * the epoch they were created for and refuse to act on a recycled instance.
     */
    private volatile long recycleEpoch;

    /** The current load-cycle stamp of this pooled instance. */
    public long epoch() {
        return recycleEpoch;
    }

    /** True if this instance is still in the load cycle the caller captured. */
    public boolean matches(long expectedEpoch) {
        return recycleEpoch == expectedEpoch;
    }

    private final Map<ChunkState, List<CompletableFuture<Void>>> stateWaiters = new HashMap<>();

    /** Pooled construction; use {@link #init} to prepare a borrowed instance. */
    public Chunk() {
    }

    public void init(RegionImpl region, ChunkPos pos, int minSectionY, int sectionCount, ObjectPool<ChunkSection> sectionPool) {
        // Bump + waiter-fail under the same monitor awaitState registers on, so no
        // waiter can slip in between and be orphaned on the new load cycle.
        synchronized (this) {
            recycleEpoch++;
            failStaleWaitersLocked();
        }
        this.pos = pos;
        this.region = region;
        this.minSectionY = minSectionY;
        this.sectionCount = sectionCount;
        this.sectionPool = sectionPool;
        this.sections = new ChunkSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            this.sections[i] = sectionPool.borrow();
        }
        this.state.reset();
        this.dirty = false;
        this.unloadRequested = false;
        this.lit = false;
        this.lastModifiedMillis = 0;
    }

    /** Completes every waiter of the previous load cycle; called from {@link #init}. */
    private void failStaleWaitersLocked() {
        if (stateWaiters.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> stale = new ArrayList<>();
        for (List<CompletableFuture<Void>> waiters : stateWaiters.values()) {
            stale.addAll(waiters);
        }
        stateWaiters.clear();
        for (CompletableFuture<Void> future : stale) {
            future.completeExceptionally(new IllegalStateException(
                "chunk instance was recycled before reaching the awaited state"));
        }
    }

    public ChunkPos pos() {
        return pos;
    }

    public RegionImpl region() {
        return region;
    }

    public ChunkState state() {
        return state.get();
    }

    public boolean isLoaded() {
        return state.isLoaded();
    }

    public boolean trySet(ChunkState from, ChunkState to) {
        boolean ok = state.trySet(from, to);
        if (ok) {
            onStateChanged(to);
        }
        return ok;
    }

    public boolean reached(ChunkState target) {
        return state.reached(target);
    }

    public int minSectionY() {
        return minSectionY;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public ChunkSection section(int sectionYIndex) {
        return sections[sectionYIndex];
    }

    private ChunkSection sectionForY(int y) {
        int sy = (y >> 4) - minSectionY;
        return sy >= 0 && sy < sectionCount ? sections[sy] : null;
    }

    public BlockState getBlock(BlockPos p) {
        ChunkSection s = sectionForY(p.y());
        return s == null ? BlockState.AIR : s.getBlock(p.x() & 15, p.y() & 15, p.z() & 15);
    }

    /**
     * Applies a delta write. Must be called on the region owner thread; the chunk
     * must be in READY/SIMULATING/DIRTY (writes during SAVING are allowed and
     * re-dirty the chunk for a follow-up save). Returns whether the write applied.
     */
    public boolean setBlock(BlockPos p, BlockState bs) {
        if (!region.isOwnerThread()) {
            throw new IllegalStateException("setBlock on " + pos + " from non-owner thread " + Thread.currentThread().getName());
        }
        ChunkState s = state.get();
        if (s == ChunkState.READY || s == ChunkState.SIMULATING || s == ChunkState.DIRTY) {
            applyWrite(p, bs);
            markDirty();
            return true;
        }
        if (s == ChunkState.SAVING) {
            applyWrite(p, bs);
            dirty = true;
            lastModifiedMillis = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /** Direct write used by generation jobs while holding an exclusive pre-READY state. */
    public void setBlockUnsafe(BlockPos p, BlockState bs) {
        ChunkSection s = sectionForY(p.y());
        if (s != null) {
            s.setBlock(p.x() & 15, p.y() & 15, p.z() & 15, bs);
        }
    }

    public void markDirty() {
        dirty = true;
        lastModifiedMillis = System.currentTimeMillis();
        ChunkState s = state.get();
        if (s == ChunkState.READY && !trySet(ChunkState.READY, ChunkState.DIRTY)) {
            return;
        }
        if (s == ChunkState.SIMULATING && !trySet(ChunkState.SIMULATING, ChunkState.DIRTY)) {
            return;
        }
        region.world().saves().scheduleSave(pos);
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    public void markUnloadRequested() {
        unloadRequested = true;
    }

    public boolean isUnloadRequested() {
        return unloadRequested;
    }

    public boolean isLit() {
        return lit;
    }

    public void markLit() {
        lit = true;
    }

    public void markUnlit() {
        lit = false;
    }

    public long lastModifiedMillis() {
        return lastModifiedMillis;
    }

    public int getBlockLight(BlockPos p) {
        ChunkSection s = sectionForY(p.y());
        return s == null ? 0 : s.getBlockLight(p.x() & 15, p.y() & 15, p.z() & 15);
    }

    public int getSkyLight(BlockPos p) {
        ChunkSection s = sectionForY(p.y());
        return s == null ? 0 : s.getSkyLight(p.x() & 15, p.y() & 15, p.z() & 15);
    }

    public void setSkyLightUnsafe(BlockPos p, int level) {
        ChunkSection s = sectionForY(p.y());
        if (s != null) {
            s.setSkyLight(p.x() & 15, p.y() & 15, p.z() & 15, level);
        }
    }

    public void setBlockLightUnsafe(BlockPos p, int level) {
        ChunkSection s = sectionForY(p.y());
        if (s != null) {
            s.setBlockLight(p.x() & 15, p.y() & 15, p.z() & 15, level);
        }
    }

    private void applyWrite(BlockPos p, BlockState bs) {
        ChunkSection s = sectionForY(p.y());
        if (s != null) {
            s.setBlock(p.x() & 15, p.y() & 15, p.z() & 15, bs);
        }
    }

    /** Immutable snapshot taken while the chunk holds the SAVING state. */
    public ChunkSnapshot snapshot() {
        int[][] blocks = new int[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] opaque = new byte[sectionCount][];
        for (int i = 0; i < sectionCount; i++) {
            blocks[i] = new int[ChunkSection.BLOCK_COUNT];
            blockLight[i] = new byte[ChunkSection.BLOCK_COUNT];
            skyLight[i] = new byte[ChunkSection.BLOCK_COUNT];
            opaque[i] = new byte[ChunkSection.BLOCK_COUNT / 8];
            sections[i].copyTo(blocks[i], blockLight[i], skyLight[i], opaque[i]);
        }
        return new ChunkSnapshot(pos, blocks, blockLight, skyLight, opaque, lastModifiedMillis);
    }

    /** Applies a loaded snapshot. Must be called while holding LOADING. */
    public void applySnapshot(ChunkSnapshot snapshot) {
        for (int i = 0; i < sectionCount; i++) {
            if (i < snapshot.sections().length) {
                sections[i].copyFrom(snapshot.sections()[i], snapshot.blockLight()[i], snapshot.skyLight()[i], snapshot.opaque()[i]);
            }
        }
        lastModifiedMillis = snapshot.timestampMillis();
    }

    /** Waits for the current load cycle to reach {@code target}. */
    public CompletableFuture<Void> awaitState(ChunkState target) {
        return awaitState(recycleEpoch, target);
    }

    /**
     * Waits for the load cycle identified by {@code expectedEpoch} to reach
     * {@code target}. If the pooled instance was recycled in the meantime, the
     * returned future completes exceptionally instead of waiting forever.
     */
    public CompletableFuture<Void> awaitState(long expectedEpoch, ChunkState target) {
        synchronized (this) {
            if (recycleEpoch != expectedEpoch) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException(
                    "chunk instance was recycled; the awaited load cycle no longer exists"));
                return failed;
            }
            // UNLOADED is the lowest ordinal: reaching it must be exact, otherwise
            // any later transition (e.g. READY) would satisfy the wait.
            if (target == ChunkState.UNLOADED ? state.get() == ChunkState.UNLOADED : reached(target)) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            stateWaiters.computeIfAbsent(target, k -> new ArrayList<>()).add(future);
            return future;
        }
    }

    private void onStateChanged(ChunkState newState) {
        List<CompletableFuture<Void>> toComplete = null;
        synchronized (this) {
            var it = stateWaiters.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                boolean satisfied = e.getKey() == ChunkState.UNLOADED
                    ? newState == ChunkState.UNLOADED
                    : e.getKey().ordinal() <= newState.ordinal();
                if (satisfied) {
                    if (toComplete == null) {
                        toComplete = new ArrayList<>();
                    }
                    toComplete.addAll(e.getValue());
                    it.remove();
                }
            }
        }
        if (toComplete != null) {
            for (CompletableFuture<Void> f : toComplete) {
                f.complete(null);
            }
        }
    }

    /** Resets and returns all sections to the pool. Called by the unload path. */
    public void releaseSections() {
        for (ChunkSection s : sections) {
            s.reset();
            sectionPool.release(s);
        }
    }
}
