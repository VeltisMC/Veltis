package org.veltismc.veltis.resource;

/**
 * A namespaced resource identifier.
 *
 * <p>Resources are identified by a namespace and a path, following
 * the convention {@code namespace:path}. For example:
 * <ul>
 *   <li>{@code VeltisMC:config/server.yml}</li>
 *   <li>{@code VeltisMC:resources/banner.txt}</li>
 *   <li>{@code minecraft:textures/block/stone.png}</li>
 * </ul>
 *
 * @param namespace the resource namespace (e.g. "VeltisMC", "minecraft")
 * @param path      the path within the namespace
 */
public record ResourceLocator(String namespace, String path) {

    /**
     * Parses a locator string in the format "namespace:path".
     *
     * @param locator the locator string
     * @return the parsed locator
     * @throws IllegalArgumentException if the format is invalid
     */
    public static ResourceLocator parse(String locator) {
        var colon = locator.indexOf(':');
        if (colon <= 0 || colon >= locator.length() - 1) {
            throw new IllegalArgumentException("Invalid resource locator: " + locator);
        }
        return new ResourceLocator(locator.substring(0, colon), locator.substring(colon + 1));
    }

    /**
     * Creates a locator in the {@code VeltisMC} namespace.
     */
    public static ResourceLocator VeltisMC(String path) {
        return new ResourceLocator("VeltisMC", path);
    }

    /**
     * Returns the full locator string (namespace:path).
     */
    public String full() {
        return namespace + ":" + path;
    }

    /**
     * Returns the file extension from the path.
     */
    public String extension() {
        var dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }

    /**
     * Returns the file name from the path.
     */
    public String fileName() {
        var slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Returns the parent directory path.
     */
    public String parent() {
        var slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }
}



