package org.veltismc.world.chunk;

import org.veltismc.world.api.BlockPos;
import org.veltismc.world.api.BlockState;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.ChunkState;
import org.veltismc.world.api.JobContext;
import org.veltismc.world.api.JobPriority;
import org.veltismc.world.api.RegionJob;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous handle to a chunk. Every operation is a region-bound job executed
 * by the owning worker; futures complete when the worker applied the operation.
 */
public final class ChunkHandleImpl implements ChunkHandle {

    private final Chunk chunk;

    public ChunkHandleImpl(Chunk chunk) {
        this.chunk = chunk;
    }

    public Chunk chunk() {
        return chunk;
    }

    @Override
    public ChunkPos pos() {
        return chunk.pos();
    }

    @Override
    public ChunkState state() {
        return chunk.state();
    }

    @Override
    public boolean isLoaded() {
        return chunk.isLoaded();
    }

    @Override
    public CompletableFuture<BlockState> getBlock(BlockPos pos) {
        CompletableFuture<BlockState> future = new CompletableFuture<>();
        chunk.region().submit(regionJob("chunk-get-block", JobPriority.NORMAL, ctx ->
            future.complete(chunk.isLoaded() ? chunk.getBlock(pos) : BlockState.AIR)));
        return future;
    }

    @Override
    public CompletableFuture<Void> setBlock(BlockPos pos, BlockState state) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        chunk.region().submit(regionJob("chunk-set-block", JobPriority.HIGH, ctx -> {
            if (chunk.isLoaded() && chunk.setBlock(pos, state)) {
                chunk.region().world().lighting().relight(chunk.pos());
            }
            future.complete(null);
        }));
        return future;
    }

    @Override
    public CompletableFuture<Void> load() {
        chunk.region().submit(new ChunkLoadJob(chunk.region().world(), chunk));
        return chunk.awaitState(ChunkState.READY);
    }

    @Override
    public CompletableFuture<Void> unload() {
        chunk.region().submit(new ChunkUnloadJob(chunk));
        return chunk.awaitState(ChunkState.UNLOADED);
    }

    @Override
    public CompletableFuture<Void> awaitState(ChunkState state) {
        return chunk.awaitState(state);
    }

    private static RegionJob regionJob(String name, JobPriority priority, JobBody body) {
        return new RegionJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JobPriority priority() {
                return priority;
            }

            @Override
            public boolean regionBound() {
                return true;
            }

            @Override
            public void execute(JobContext ctx) {
                body.run(ctx);
            }
        };
    }

    @FunctionalInterface
    private interface JobBody {
        void run(JobContext ctx);
    }
}
