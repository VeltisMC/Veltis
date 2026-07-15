package org.veltismc.veltis.server.model.registry;

import org.veltismc.veltis.server.model.entity.InternalEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe registry for managing {@link InternalEntity} instances.
 *
 * <p>Indexed by both entity ID (per-session) and UUID (persistent).
 * Provides immutable external views.
 */
public final class EntityRegistry {

    private final ConcurrentHashMap<Integer, InternalEntity> byEntityId;
    private final ConcurrentHashMap<UUID, InternalEntity> byUuid;

    /**
     * Creates an empty entity registry.
     */
    public EntityRegistry() {
        this.byEntityId = new ConcurrentHashMap<>();
        this.byUuid = new ConcurrentHashMap<>();
    }

    /**
     * Registers an entity under both indices.
     *
     * @param entity the entity to register
     * @return the previously registered entity under the same UUID, if any
     */
    public Optional<InternalEntity> register(InternalEntity entity) {
        byEntityId.put(entity.entityId(), entity);
        return Optional.ofNullable(byUuid.put(entity.uniqueId(), entity));
    }

    /**
     * Removes an entity by entity ID.
     *
     * @param entityId the server-assigned entity ID
     * @return the removed entity, or empty if not found
     */
    public Optional<InternalEntity> unregisterById(int entityId) {
        var entity = byEntityId.remove(entityId);
        if (entity != null) {
            byUuid.remove(entity.uniqueId());
        }
        return Optional.ofNullable(entity);
    }

    /**
     * Removes an entity by UUID.
     *
     * @param uniqueId the persistent UUID
     * @return the removed entity, or empty if not found
     */
    public Optional<InternalEntity> unregisterByUuid(UUID uniqueId) {
        var entity = byUuid.remove(uniqueId);
        if (entity != null) {
            byEntityId.remove(entity.entityId());
        }
        return Optional.ofNullable(entity);
    }

    /**
     * Looks up an entity by server-assigned entity ID.
     */
    public Optional<InternalEntity> byEntityId(int entityId) {
        return Optional.ofNullable(byEntityId.get(entityId));
    }

    /**
     * Looks up an entity by persistent UUID.
     */
    public Optional<InternalEntity> byUuid(UUID uniqueId) {
        return Optional.ofNullable(byUuid.get(uniqueId));
    }

    /**
     * Returns true if an entity with the given entity ID is registered.
     */
    public boolean containsId(int entityId) {
        return byEntityId.containsKey(entityId);
    }

    /**
     * Returns true if an entity with the given UUID is registered.
     */
    public boolean containsUuid(UUID uniqueId) {
        return byUuid.containsKey(uniqueId);
    }

    /**
     * Returns an immutable snapshot of all registered entities.
     */
    public Collection<InternalEntity> all() {
        return byUuid.values().stream().toList();
    }

    /**
     * Returns a stream over all registered entities by UUID.
     */
    public Stream<InternalEntity> stream() {
        return byUuid.values().stream();
    }

    /**
     * Returns the number of registered entities.
     */
    public int count() {
        return byUuid.size();
    }

    /**
     * Returns true if the registry is empty.
     */
    public boolean isEmpty() {
        return byUuid.isEmpty();
    }

    /**
     * Removes all entities.
     */
    public void clear() {
        byEntityId.clear();
        byUuid.clear();
    }
}


