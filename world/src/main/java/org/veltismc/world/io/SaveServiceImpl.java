package org.veltismc.world.io;

import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.SaveService;
import org.veltismc.world.chunk.Chunk;
import org.veltismc.world.core.WorldImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link SaveService}: dirty chunks are deduplicated in a set, drained by
 * migratable batch jobs, serialized by the codec, and written through the store.
 * Simulation never waits for disk: writes happen asynchronously and the state
 * machine (SAVING) guarantees at most one writer per chunk.
 */
public final class SaveServiceImpl implements SaveService {

    private final WorldImpl world;
    private final ConcurrentHashMap<Long, ChunkPos> pending = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<CompletableFuture<Void>> flushWaiters = new ArrayList<>();

    public SaveServiceImpl(WorldImpl world) {
        this.world = world;
    }

    @Override
    public void scheduleSave(ChunkPos pos) {
        if (closed.get()) {
            return;
        }
        if (pending.putIfAbsent(pos.key(), pos) == null) {
            world.scheduler().schedule(new SaveBatchJob(world, this));
        }
    }

    @Override
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (flushWaiters) {
            flushWaiters.add(future);
        }
        if (isQuiescent()) {
            completeFlushWaiters();
        } else {
            world.scheduler().schedule(new SaveBatchJob(world, this));
        }
        return future;
    }

    @Override
    public int pendingSaves() {
        return pending.size() + inFlight.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            world.scheduler().schedule(new SaveBatchJob(world, this));
        }
    }

    /**
     * Kicks the drain loop when work is pending. {@link #scheduleSave} only
     * schedules a batch when it inserts a new entry, so a caller that observes an
     * entry it did not create (or that races with a draining batch) uses this to
     * guarantee progress. Redundant batch jobs are harmless: they return as soon
     * as the service is quiescent, and chunk claims are CAS-guarded.
     */
    public void drainIfIdle() {
        if (!pending.isEmpty()) {
            world.scheduler().schedule(new SaveBatchJob(world, this));
        }
    }

    boolean isQuiescent() {
        return pending.isEmpty() && inFlight.get() == 0;
    }

    void completeFlushWaiters() {
        List<CompletableFuture<Void>> waiters;
        synchronized (flushWaiters) {
            if (!isQuiescent()) {
                return;
            }
            waiters = new ArrayList<>(flushWaiters);
            flushWaiters.clear();
        }
        for (CompletableFuture<Void> f : waiters) {
            f.complete(null);
        }
    }

    void onSaveStarted() {
        inFlight.incrementAndGet();
    }

    void onSaveFinished() {
        inFlight.decrementAndGet();
        if (isQuiescent()) {
            completeFlushWaiters();
        }
    }

    WorldImpl world() {
        return world;
    }

    ConcurrentHashMap<Long, ChunkPos> pending() {
        return pending;
    }
}
