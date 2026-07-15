package org.veltismc.veltis;

import com.destroystokyo.paper.SkinParts;
import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.poi.PoiType;
import io.papermc.paper.world.damagesource.CombatEntry;
import io.papermc.paper.world.damagesource.FallLocationType;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.damage.DamageEffect;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pose;

public final class VeltisInternalAPIBridge implements InternalAPIBridge {

    @Override
    public DamageEffect getDamageEffect(String key) {
        return null;
    }

    @Override
    public PoiType.Occupancy createOccupancy(String enumNameEntry) {
        return PoiType.Occupancy.HAS_SPACE;
    }

    @Override
    public Biome constructLegacyCustomBiome() {
        var key = NamespacedKey.minecraft("custom");
        return new Biome() {
            @Override
            public NamespacedKey getKey() {
                return key;
            }
            @Override
            public int compareTo(Biome o) {
                return key.compareTo(o.getKey());
            }
            @Override
            public String name() {
                return "CUSTOM";
            }
            @Override
            public int ordinal() {
                return 0;
            }
        };
    }

    @Override
    public CombatEntry createCombatEntry(LivingEntity entity, DamageSource damageSource, float damage) {
        return createCombatEntry(damageSource, damage, null, 0);
    }

    @Override
    public CombatEntry createCombatEntry(DamageSource damageSource, float damage,
                                          FallLocationType fallLocationType, float fallDistance) {
        return new CombatEntry() {
            @Override
            public DamageSource getDamageSource() {
                return damageSource;
            }
            @Override
            public float getDamage() {
                return damage;
            }
            @Override
            public @org.jspecify.annotations.Nullable FallLocationType getFallLocationType() {
                return fallLocationType;
            }
            @Override
            public float getFallDistance() {
                return fallDistance;
            }
        };
    }

    @Override
    public Predicate<CommandSourceStack> restricted(Predicate<CommandSourceStack> predicate) {
        return predicate;
    }

    @Override
    public ResolvableProfile defaultMannequinProfile() {
        return null;
    }

    @Override
    public SkinParts.Mutable allSkinParts() {
        return new SkinParts.Mutable() {
            private boolean cape = true, jacket = true, leftSleeve = true, rightSleeve = true;
            private boolean leftPants = true, rightPants = true, hats = true;
            @Override public boolean hasCapeEnabled() { return cape; }
            @Override public void setCapeEnabled(boolean enabled) { this.cape = enabled; }
            @Override public boolean hasJacketEnabled() { return jacket; }
            @Override public void setJacketEnabled(boolean enabled) { this.jacket = enabled; }
            @Override public boolean hasLeftSleeveEnabled() { return leftSleeve; }
            @Override public void setLeftSleeveEnabled(boolean enabled) { this.leftSleeve = enabled; }
            @Override public boolean hasRightSleeveEnabled() { return rightSleeve; }
            @Override public void setRightSleeveEnabled(boolean enabled) { this.rightSleeve = enabled; }
            @Override public boolean hasLeftPantsEnabled() { return leftPants; }
            @Override public void setLeftPantsEnabled(boolean enabled) { this.leftPants = enabled; }
            @Override public boolean hasRightPantsEnabled() { return rightPants; }
            @Override public void setRightPantsEnabled(boolean enabled) { this.rightPants = enabled; }
            @Override public boolean hasHatsEnabled() { return hats; }
            @Override public void setHatsEnabled(boolean enabled) { this.hats = enabled; }
            @Override public int getRaw() {
                int raw = 0;
                if (cape) raw |= 1;
                if (jacket) raw |= 2;
                if (leftSleeve) raw |= 4;
                if (rightSleeve) raw |= 8;
                if (leftPants) raw |= 16;
                if (rightPants) raw |= 32;
                if (hats) raw |= 64;
                return raw;
            }
            @Override public SkinParts immutableCopy() { return this; }
            @Override public SkinParts.Mutable mutableCopy() { return this; }
        };
    }

    @Override
    public Component defaultMannequinDescription() {
        return Component.text("Manni");
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <MODERN, LEGACY> GameRule<LEGACY> legacyGameRuleBridge(
        GameRule<MODERN> rule,
        Function<LEGACY, MODERN> fromLegacyToModern,
        Function<MODERN, LEGACY> toLegacyFromModern,
        Class<LEGACY> legacyClass
    ) {
        return new GameRule<LEGACY>() {
            @Override
            public String getName() {
                return ((Keyed) rule).getKey().getKey();
            }
            @Override
            public Class<LEGACY> getType() {
                return legacyClass;
            }
            @Override
            public LEGACY getDefaultValue() {
                return toLegacyFromModern.apply(rule.getDefaultValue());
            }
            @Override
            public NamespacedKey getKey() {
                return ((Keyed) rule).getKey();
            }
            @Override
            public String translationKey() {
                return rule.translationKey();
            }
        };
    }

    @Override
    public Set<Pose> validMannequinPoses() {
        return Set.of();
    }
}
