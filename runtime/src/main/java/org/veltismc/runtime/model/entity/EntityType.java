package org.veltismc.runtime.model.entity;

/**
 * Classification type for an entity.
 *
 * <p>Captures the entity's registered type ID, name, dimensions,
 * and whether it is a living entity.
 *
 * @param name           the registry name (e.g. "minecraft:zombie")
 * @param translationKey the translation key for display
 * @param typeId         the numeric entity type ID
 * @param width          entity hitbox width
 * @param height         entity hitbox height
 * @param living         whether this is a living entity
 */
public record EntityType(
    String name,
    String translationKey,
    int typeId,
    float width,
    float height,
    boolean living
) {

    public static final EntityType PLAYER = new EntityType(
        "minecraft:player", "entity.minecraft.player",
        -1, 0.6f, 1.8f, true
    );

    /**
     * Returns a simple type from just a name.
     */
    public static EntityType named(String name) {
        return new EntityType(name, "entity." + name.replace(':', '.'), 0, 0.6f, 1.8f, true);
    }

    /**
     * Returns true if this is the player type.
     */
    public boolean isPlayer() {
        return "minecraft:player".equals(name);
    }
}


