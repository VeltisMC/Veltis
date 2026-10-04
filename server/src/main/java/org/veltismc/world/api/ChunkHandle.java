package org.veltismc.world.api;

import java.util.concurrent.CompletableFuture;

/**
 * An asynchronous view of a single chunk.
 *
 * <p>Every operation is async: reads/writes are routed to the owning region and
 * applied by its worker. The returned futures complete when the owning worker
 * has executed the operation.
 */
public interface ChunkHandle {

    ChunkPos pos();

    /** Current lifecycle state (thread-safe read). */
    ChunkState state();

    boolean isLoaded();

    /** Reads a block asynchronously. Resolves to {@link BlockState#AIR} if the chunk is not loaded. */
    CompletableFuture<BlockState> getBlock(BlockPos pos);

    /** Writes a block asynchronously through the delta pipeline. */
    CompletableFuture<Void> setBlock(BlockPos pos, BlockState state);

    /** Starts the load pipeline and completes once the chunk is usable. */
    CompletableFuture<Void> load();

    /** Starts the unload pipeline (saving first if dirty) and completes once unloaded. */
    CompletableFuture<Void> unload();

    /** Completes when the chunk reaches the given state. */
    CompletableFuture<Void> awaitState(ChunkState state);
}
