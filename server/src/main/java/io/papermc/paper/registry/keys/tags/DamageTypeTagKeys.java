package io.papermc.paper.registry.keys.tags;

import static net.kyori.adventure.key.Key.key;

import io.papermc.paper.annotation.GeneratedClass;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import org.bukkit.damage.DamageType;
import org.jspecify.annotations.NullMarked;

@SuppressWarnings({
        "unused",
        "SpellCheckingInspection"
})
@NullMarked
@GeneratedClass
public final class DamageTypeTagKeys {
    public static final TagKey<DamageType> ALWAYS_HURTS_ENDER_DRAGONS = create(key("always_hurts_ender_dragons"));
    public static final TagKey<DamageType> ALWAYS_KILLS_ARMOR_STANDS = create(key("always_kills_armor_stands"));
    public static final TagKey<DamageType> ALWAYS_MOST_SIGNIFICANT_FALL = create(key("always_most_significant_fall"));
    public static final TagKey<DamageType> ALWAYS_TRIGGERS_SILVERFISH = create(key("always_triggers_silverfish"));
    public static final TagKey<DamageType> AVOIDS_GUARDIAN_THORNS = create(key("avoids_guardian_thorns"));
    public static final TagKey<DamageType> BURN_FROM_STEPPING = create(key("burn_from_stepping"));
    public static final TagKey<DamageType> BURNS_ARMOR_STANDS = create(key("burns_armor_stands"));
    public static final TagKey<DamageType> BYPASSES_ARMOR = create(key("bypasses_armor"));
    public static final TagKey<DamageType> BYPASSES_EFFECTS = create(key("bypasses_effects"));
    public static final TagKey<DamageType> BYPASSES_ENCHANTMENTS = create(key("bypasses_enchantments"));
    public static final TagKey<DamageType> BYPASSES_INVULNERABILITY = create(key("bypasses_invulnerability"));
    public static final TagKey<DamageType> BYPASSES_RESISTANCE = create(key("bypasses_resistance"));
    public static final TagKey<DamageType> BYPASSES_SHIELD = create(key("bypasses_shield"));
    public static final TagKey<DamageType> BYPASSES_WOLF_ARMOR = create(key("bypasses_wolf_armor"));
    public static final TagKey<DamageType> CAN_BREAK_ARMOR_STAND = create(key("can_break_armor_stand"));
    public static final TagKey<DamageType> DAMAGES_HELMET = create(key("damages_helmet"));
    public static final TagKey<DamageType> IGNITES_ARMOR_STANDS = create(key("ignites_armor_stands"));
    public static final TagKey<DamageType> IS_DROWNING = create(key("is_drowning"));
    public static final TagKey<DamageType> IS_EXPLOSION = create(key("is_explosion"));
    public static final TagKey<DamageType> IS_FALL = create(key("is_fall"));
    public static final TagKey<DamageType> IS_FIRE = create(key("is_fire"));
    public static final TagKey<DamageType> IS_FREEZING = create(key("is_freezing"));
    public static final TagKey<DamageType> IS_LIGHTNING = create(key("is_lightning"));
    public static final TagKey<DamageType> IS_PLAYER_ATTACK = create(key("is_player_attack"));
    public static final TagKey<DamageType> IS_PROJECTILE = create(key("is_projectile"));
    public static final TagKey<DamageType> MACE_SMASH = create(key("mace_smash"));
    public static final TagKey<DamageType> NO_ANGER = create(key("no_anger"));
    public static final TagKey<DamageType> NO_IMPACT = create(key("no_impact"));
    public static final TagKey<DamageType> NO_KNOCKBACK = create(key("no_knockback"));
    public static final TagKey<DamageType> PANIC_CAUSES = create(key("panic_causes"));
    public static final TagKey<DamageType> PANIC_ENVIRONMENTAL_CAUSES = create(key("panic_environmental_causes"));
    public static final TagKey<DamageType> WITCH_RESISTANT_TO = create(key("witch_resistant_to"));
    public static final TagKey<DamageType> WITHER_IMMUNE_TO = create(key("wither_immune_to"));

    private DamageTypeTagKeys() {
    }

    public static TagKey<DamageType> create(final Key key) {
        return TagKey.create(RegistryKey.DAMAGE_TYPE, key);
    }
}
