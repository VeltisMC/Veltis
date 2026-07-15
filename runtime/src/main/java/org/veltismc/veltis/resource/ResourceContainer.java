package org.veltismc.veltis.resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Holds the raw bytes of a loaded resource along with its locator.
 *
 * @param locator  the resource locator
 * @param data     the raw resource bytes
 * @param loadedAt timestamp when the resource was loaded (millis)
 */
public record ResourceContainer(ResourceLocator locator, byte[] data, long loadedAt) {

    /**
     * Creates a resource container.
     */
    public ResourceContainer {
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(data, "data");
    }

    /**
     * Opens an input stream over the resource data.
     */
    public InputStream openStream() {
        return new java.io.ByteArrayInputStream(data);
    }

    /**
     * Returns the data as a UTF-8 string.
     */
    public String asString() {
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Returns the data size in bytes.
     */
    public int size() {
        return data.length;
    }
}


