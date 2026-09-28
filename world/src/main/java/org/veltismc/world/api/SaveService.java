package org.veltismc.world.api;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous save service.
 *
 * <p>Simulation never waits for disk: dirty chunks are queued, batched, and
 * written by a dedicated IO executor (virtual threads). The engine always keeps
 * a compressed representation ready for the codec; future compression support
 * is a codec implementation detail.
 */
public interface SaveService {

    /** Queues a dirty chunk for saving (deduplicated). */
    void scheduleSave(ChunkPos pos);

    /** Completes when all currently queued and in-flight writes have finished. */
    CompletableFuture<Void> flush();

    /** Number of chunks queued or in flight. */
    int pendingSaves();

    /** Shuts down the IO executor. */
    void close();
}
