package org.veltismc.veltis.storage;

/**
 * Standard storage locations within the server runtime directory.
 *
 * <p>Each location maps to a subdirectory under the server's root
 * storage path:
 * <ul>
 *   <li>{@link #CONFIG} - server configuration files (server.yml, runtime.yml, etc.)</li>
 *   <li>{@link #CACHE}  - cached data that can be regenerated</li>
 *   <li>{@link #DATA}   - persistent runtime data (player data, world data)</li>
 *   <li>{@link #LOGS}   - server log files</li>
 *   <li>{@link #RUNTIME} - runtime resources and temporary files</li>
 * </ul>
 */
public enum StorageLocation {
    CONFIG("config"),
    CACHE("cache"),
    DATA("data"),
    LOGS("logs"),
    RUNTIME("runtime");

    private final String directory;

    StorageLocation(String directory) {
        this.directory = directory;
    }

    /**
     * Returns the directory name for this location.
     */
    public String directory() {
        return directory;
    }
}


