package org.veltismc.veltis.resource;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provides access to resources by {@link ResourceLocator}.
 *
 * <p>Implementations may load resources from the filesystem, the
 * classpath, embedded JAR resources, or future plugin JARs.
 */
public interface ResourceProvider {

    /**
     * Opens an input stream for the given resource.
     *
     * @param locator the resource locator
     * @return an input stream for the resource content
     * @throws java.io.IOException if the resource cannot be opened
     */
    InputStream open(ResourceLocator locator) throws java.io.IOException;

    /**
     * Reads the resource content as a byte array.
     */
    byte[] read(ResourceLocator locator) throws java.io.IOException;

    /**
     * Reads the resource content as a UTF-8 string.
     */
    String readString(ResourceLocator locator) throws java.io.IOException;

    /**
     * Returns the filesystem path for a resource, if available.
     */
    Optional<Path> path(ResourceLocator locator);

    /**
     * Returns true if a resource exists at the given locator.
     */
    boolean exists(ResourceLocator locator);
}


