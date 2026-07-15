package org.veltismc.veltis.server.model.registry;

import org.veltismc.veltis.server.model.world.InternalWorld;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Thread-safe registry for managing {@link InternalWorld} instances.
 *
 * <p>Indexed by both UUID and name for flexible lookup.
 * Provides immutable external views.
 */
public final class WorldRegistry {

    private final ConcurrentHashMap<UUID, InternalWorld> byUuid;
    private final CopyOnWriteArrayList<InternalWorld> ordered;

    /**
     * Creates an empty world registry.
     */
    public WorldRegistry() {
        this.byUuid = new ConcurrentHashMap<>();
        this.ordered = new CopyOnWriteArrayList<>();
    }

    /**
     * Registers a world.
     *
     * @param world the world to register
     * @return the previously registered world under the same UUID, if any
     */
    public Optional<InternalWorld> register(InternalWorld world) {
        var existing = byUuid.put(world.uniqueId(), world);
        if (existing == null) {
            ordered.add(world);
        }
        return Optional.ofNullable(existing);
    }

    /**
     * Removes a world by UUID.
     *
     * @param uniqueId the world UUID
     * @return the removed world, or empty if not found
     */
    public Optional<InternalWorld> unregister(UUID uniqueId) {
        var removed = byUuid.remove(uniqueId);
        if (removed != null) {
            ordered.remove(removed);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Removes a world by name.
     *
     * @param name the world name
     * @return the removed world, or empty if not found
     */
    public Optional<InternalWorld> unregister(String name) {
        var found = byName(name);
        found.ifPresent(w -> {
            byUuid.remove(w.uniqueId());
            ordered.remove(w);
        });
        return found;
    }

    /**
     * Looks up a world by UUID.
     */
    public Optional<InternalWorld> byUuid(UUID uniqueId) {
        return Optional.ofNullable(byUuid.get(uniqueId));
    }

    /**
     * Looks up a world by name.
     */
    public Optional<InternalWorld> byName(String name) {
        return ordered.stream()
            .filter(w -> w.name().equalsIgnoreCase(name))
            .findFirst();
    }

    /**
     * Looks up a world by insertion index.
     */
    public Optional<InternalWorld> byIndex(int index) {
        if (index >= 0 && index < ordered.size()) {
            return Optional.of(ordered.get(index));
        }
        return Optional.empty();
    }

    /**
     * Returns true if a world with the given UUID is registered.
     */
    public boolean contains(UUID uniqueId) {
        return byUuid.containsKey(uniqueId);
    }

    /**
     * Returns true if a world with the given name is registered.
     */
    public boolean containsName(String name) {
        return ordered.stream().anyMatch(w -> w.name().equalsIgnoreCase(name));
    }

    /**
     * Returns an immutable snapshot of all registered worlds.
     */
    public Collection<InternalWorld> all() {
        return ordered.stream().toList();
    }

    /**
     * Returns a sequential stream over all registered worlds.
     */
    public Stream<InternalWorld> stream() {
        return ordered.stream();
    }

    /**
     * Returns the number of registered worlds.
     */
    public int count() {
        return byUuid.size();
    }

    /**
     * Returns the default world (first registered), if any.
     */
    public Optional<InternalWorld> defaultWorld() {
        return ordered.isEmpty() ? Optional.empty() : Optional.of(ordered.get(0));
    }

    /**
     * Returns true if the registry is empty.
     */
    public boolean isEmpty() {
        return byUuid.isEmpty();
    }

    /**
     * Removes all worlds.
     */
    public void clear() {
        byUuid.clear();
        ordered.clear();
    }
}


