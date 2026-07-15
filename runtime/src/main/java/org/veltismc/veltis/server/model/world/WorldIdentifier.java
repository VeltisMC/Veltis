package org.veltismc.veltis.server.model.world;

import java.util.UUID;

/**
 * Immutable identity for a world.
 *
 * <p>Combines the world's unique UUID with its logical name
 * for unambiguous identification and lookup.
 *
 * @param uniqueId the world's unique identifier
 * @param name     the world's logical name (e.g. "world", "world_nether")
 */
public record WorldIdentifier(UUID uniqueId, String name) {

    /**
     * Creates an identifier from a name with a random UUID.
     */
    public static WorldIdentifier named(String name) {
        return new WorldIdentifier(UUID.randomUUID(), name);
    }
}


