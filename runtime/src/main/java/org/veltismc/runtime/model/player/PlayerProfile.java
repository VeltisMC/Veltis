package org.veltismc.runtime.model.player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable profile data for a player.
 *
 * <p>Captures the stable identity and cosmetic properties of a
 * player, including UUID, name, display name, and skin textures.
 * Once created, a profile never changes — sessions and state
 * are tracked separately via {@link PlayerSession}.
 *
 * @param uniqueId     the player's Mojang UUID
 * @param name         the player's username
 * @param displayName  the display name (may differ from username)
 * @param skinValue    optional skin texture value (base64)
 * @param skinSignature optional skin texture signature
 * @param properties   additional profile properties
 */
public record PlayerProfile(
    UUID uniqueId,
    String name,
    String displayName,
    Optional<String> skinValue,
    Optional<String> skinSignature,
    Map<String, String> properties
) {

    /**
     * Creates a minimal profile with just UUID and name.
     */
    public static PlayerProfile minimal(UUID uniqueId, String name) {
        return new PlayerProfile(
            uniqueId, name, name,
            Optional.empty(), Optional.empty(),
            Map.of()
        );
    }

    /**
     * Creates a profile with skin data.
     */
    public static PlayerProfile withSkin(
        UUID uniqueId, String name, String displayName,
        String skinValue, String skinSignature
    ) {
        return new PlayerProfile(
            uniqueId, name, displayName,
            Optional.of(skinValue), Optional.of(skinSignature),
            Map.of()
        );
    }

    /**
     * Returns a new profile with the given display name.
     */
    public PlayerProfile withDisplayName(String displayName) {
        return new PlayerProfile(
            uniqueId, name, displayName,
            skinValue, skinSignature, properties
        );
    }

    /**
     * Returns a new profile with the given skin data.
     */
    public PlayerProfile withSkin(String skinValue, String skinSignature) {
        return new PlayerProfile(
            uniqueId, name, displayName,
            Optional.of(skinValue), Optional.of(skinSignature),
            properties
        );
    }
}


