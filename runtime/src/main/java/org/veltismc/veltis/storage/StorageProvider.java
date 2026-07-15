package org.veltismc.veltis.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides access to storage locations within the server runtime.
 *
 * <p>Implementations resolve {@link StorageLocation} values to
 * absolute filesystem paths and ensure the corresponding directories
 * exist.
 */
public interface StorageProvider {

    /**
     * Resolves a storage location to an absolute path.
     *
     * @param location the storage location
     * @return the resolved path
     */
    Path resolve(StorageLocation location);

    /**
     * Ensures the directory for a storage location exists.
     *
     * @param location the storage location
     * @return the resolved path
     * @throws IOException if the directory cannot be created
     */
    Path ensureDirectory(StorageLocation location) throws IOException;
}


