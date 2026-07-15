package org.veltismc.veltis.server.model.registry;

import org.veltismc.veltis.server.model.player.InternalPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Thread-safe registry for managing {@link InternalPlayer} instances.
 *
 * <p>Indexed by UUID for fast lookup. Provides immutable external
 * views via {@link #all()} and {@link #stream()}.
 */
public final class PlayerRegistry {

    private final ConcurrentHashMap<UUID, InternalPlayer> byUuid;

    /**
     * Creates an empty player registry.
     */
    public PlayerRegistry() {
        this.byUuid = new ConcurrentHashMap<>();
    }

    /**
     * Creates a pre-populated player registry.
     *
     * @param players initial players
     */
    public PlayerRegistry(Collection<? extends InternalPlayer> players) {
        this.byUuid = new ConcurrentHashMap<>();
        players.forEach(p -> byUuid.put(p.uniqueId(), p));
    }

    /**
     * Registers a player.
     *
     * @param player the player to register
     * @return the previously registered player under the same UUID, if any
     */
    public Optional<InternalPlayer> register(InternalPlayer player) {
        return Optional.ofNullable(byUuid.put(player.uniqueId(), player));
    }

    /**
     * Removes a player by UUID.
     *
     * @param uniqueId the player UUID
     * @return the removed player, or empty if not found
     */
    public Optional<InternalPlayer> unregister(UUID uniqueId) {
        return Optional.ofNullable(byUuid.remove(uniqueId));
    }

    /**
     * Removes a player by instance identity.
     *
     * @param player the player to remove
     * @return true if the player was found and removed
     */
    public boolean unregister(InternalPlayer player) {
        return byUuid.remove(player.uniqueId(), player);
    }

    /**
     * Looks up a player by UUID.
     *
     * @param uniqueId the player UUID
     * @return the player, or empty if not registered
     */
    public Optional<InternalPlayer> byUuid(UUID uniqueId) {
        return Optional.ofNullable(byUuid.get(uniqueId));
    }

    /**
     * Looks up a player by username.
     *
     * @param username the player username
     * @return the player, or empty if not found
     */
    public Optional<InternalPlayer> byName(String username) {
        return byUuid.values().stream()
            .filter(p -> p.profile().name().equalsIgnoreCase(username))
            .findFirst();
    }

    /**
     * Returns true if a player with the given UUID is registered.
     */
    public boolean contains(UUID uniqueId) {
        return byUuid.containsKey(uniqueId);
    }

    /**
     * Returns true if a player with the given username is registered.
     */
    public boolean containsName(String username) {
        return byUuid.values().stream()
            .anyMatch(p -> p.profile().name().equalsIgnoreCase(username));
    }

    /**
     * Returns an immutable snapshot of all registered players.
     */
    public Collection<InternalPlayer> all() {
        return byUuid.values().stream().toList();
    }

    /**
     * Returns a sequential stream over all registered players.
     */
    public Stream<InternalPlayer> stream() {
        return byUuid.values().stream();
    }

    /**
     * Returns the number of registered players.
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
     * Removes all players from the registry.
     */
    public void clear() {
        byUuid.clear();
    }
}


