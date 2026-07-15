package org.veltismc.veltis.data;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A key-value data repository backed by persistent storage.
 *
 * <p>Provides asynchronous read/write operations for
 * {@link DataContainer} values keyed by string identifiers.
 */
public interface DataRepository {

    /**
     * Reads a value by key.
     *
     * @param key the data key
     * @return the value, or empty if not found
     */
    Optional<DataContainer> read(String key) throws IOException;

    /**
     * Writes a value by key.
     */
    void write(String key, DataContainer value) throws IOException;

    /**
     * Deletes a value by key.
     */
    void delete(String key) throws IOException;

    /**
     * Returns true if a key exists.
     */
    boolean exists(String key) throws IOException;

    /**
     * Async read.
     */
    CompletableFuture<Optional<DataContainer>> readAsync(String key);

    /**
     * Async write.
     */
    CompletableFuture<Void> writeAsync(String key, DataContainer value);

    /**
     * Async delete.
     */
    CompletableFuture<Void> deleteAsync(String key);
}


