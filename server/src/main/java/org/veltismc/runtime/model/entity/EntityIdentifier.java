package org.veltismc.runtime.model.entity;

import java.util.UUID;

/**
 * Immutable identity for an entity.
 *
 * <p>Combines the server-assigned entity ID (unique per session)
 * with the persistent UUID that follows the entity across sessions.
 *
 * @param entityId the server-assigned entity ID
 * @param uniqueId the persistent UUID
 */
public record EntityIdentifier(int entityId, UUID uniqueId) {

    /**
     * Creates an identifier from a UUID with a zero entity ID.
     * Used for entities that have not yet been assigned an ID.
     */
    public static EntityIdentifier fromUuid(UUID uniqueId) {
        return new EntityIdentifier(0, uniqueId);
    }

    /**
     * Creates an identifier with both values.
     */
    public static EntityIdentifier of(int entityId, UUID uniqueId) {
        return new EntityIdentifier(entityId, uniqueId);
    }

    /**
     * Returns a new identifier with an updated entity ID.
     */
    public EntityIdentifier withEntityId(int entityId) {
        return new EntityIdentifier(entityId, uniqueId);
    }

    @Override
    public int entityId() {
        return entityId;
    }

    @Override
    public UUID uniqueId() {
        return uniqueId;
    }
}


