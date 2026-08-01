package org.veltismc.veltis.registry;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Keyed;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class VeltisRegistryAccess implements RegistryAccess {

    private static volatile MinecraftServer serverRef;
    private static volatile boolean bootstrapped;
    private static final Set<RegistryKey<?>> inProgress = ConcurrentHashMap.newKeySet();

    private static final Map<Class<?>, RegistryKey<?>> CLASS_TO_KEY = new ConcurrentHashMap<>();

    static {
        CLASS_TO_KEY.put(org.bukkit.Art.class, RegistryKey.PAINTING_VARIANT);
        CLASS_TO_KEY.put(org.bukkit.block.banner.PatternType.class, RegistryKey.BANNER_PATTERN);
        CLASS_TO_KEY.put(org.bukkit.block.Biome.class, RegistryKey.BIOME);
        CLASS_TO_KEY.put(org.bukkit.entity.Cat.Type.class, RegistryKey.CAT_VARIANT);
        CLASS_TO_KEY.put(org.bukkit.enchantments.Enchantment.class, RegistryKey.ENCHANTMENT);
        CLASS_TO_KEY.put(org.bukkit.MusicInstrument.class, RegistryKey.INSTRUMENT);
        CLASS_TO_KEY.put(org.bukkit.generator.structure.Structure.class, RegistryKey.STRUCTURE);
        CLASS_TO_KEY.put(org.bukkit.inventory.meta.trim.TrimMaterial.class, RegistryKey.TRIM_MATERIAL);
        CLASS_TO_KEY.put(org.bukkit.inventory.meta.trim.TrimPattern.class, RegistryKey.TRIM_PATTERN);
        CLASS_TO_KEY.put(org.bukkit.damage.DamageType.class, RegistryKey.DAMAGE_TYPE);
        CLASS_TO_KEY.put(org.bukkit.JukeboxSong.class, RegistryKey.JUKEBOX_SONG);
        CLASS_TO_KEY.put(org.bukkit.entity.Frog.Variant.class, RegistryKey.FROG_VARIANT);
        CLASS_TO_KEY.put(org.bukkit.entity.Wolf.Variant.class, RegistryKey.WOLF_VARIANT);
        CLASS_TO_KEY.put(org.bukkit.entity.Chicken.Variant.class, RegistryKey.CHICKEN_VARIANT);
    }

    private static final RegistryKey<?>[] BOOTSTRAP_ORDER = {
        RegistryKey.BIOME,
        RegistryKey.ENTITY_TYPE,
        RegistryKey.DAMAGE_TYPE,
        RegistryKey.ENCHANTMENT,
        RegistryKey.STRUCTURE,
        RegistryKey.STRUCTURE_TYPE,
        RegistryKey.TRIM_MATERIAL,
        RegistryKey.TRIM_PATTERN,
        RegistryKey.ATTRIBUTE,
        RegistryKey.BLOCK,
        RegistryKey.CAT_VARIANT,
        RegistryKey.CHICKEN_VARIANT,
        RegistryKey.FLUID,
        RegistryKey.FROG_VARIANT,
        RegistryKey.GAME_EVENT,
        RegistryKey.GAME_RULE,
        RegistryKey.INSTRUMENT,
        RegistryKey.ITEM,
        RegistryKey.JUKEBOX_SONG,
        RegistryKey.MAP_DECORATION_TYPE,
        RegistryKey.MENU,
        RegistryKey.MOB_EFFECT,
        RegistryKey.PAINTING_VARIANT,
        RegistryKey.PARTICLE_TYPE,
        RegistryKey.POTION,
        RegistryKey.SOUND_EVENT,
        RegistryKey.VILLAGER_PROFESSION,
        RegistryKey.VILLAGER_TYPE,
        RegistryKey.WOLF_VARIANT,
        RegistryKey.BANNER_PATTERN,
        RegistryKey.POINT_OF_INTEREST_TYPE,
        RegistryKey.DATA_COMPONENT_TYPE,
    };

    private final Map<RegistryKey<?>, org.bukkit.Registry<?>> cache = new ConcurrentHashMap<>();

    public static void init(Object server) {
        if (server instanceof MinecraftServer ms) {
            serverRef = ms;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void bootstrap() {
        if (bootstrapped) return;
        var instance = (VeltisRegistryAccess) RegistryAccess.registryAccess();

        // Phase 1: Trigger Registry.<clinit> before building any registry.
        // Accessing any Registry field initializes the class, which calls
        // legacyRegistryFor() for each CLASS_TO_KEY entry, calling back into
        // getRegistry(). Since no key is in progress yet, all succeed.
        @SuppressWarnings("unused")
        var unused = org.bukkit.Registry.MATERIAL;

        // Phase 2: Build remaining keys not covered by CLASS_TO_KEY
        for (var key : BOOTSTRAP_ORDER) {
            if (!instance.cache.containsKey(key)) {
                instance.getRegistry((RegistryKey) key);
            }
        }
        bootstrapped = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> org.bukkit.Registry<T> getRegistry(RegistryKey<T> key) {
        var existing = cache.get(key);
        if (existing != null) return (org.bukkit.Registry<T>) existing;

        if (!inProgress.add(key)) {
            throw new IllegalStateException(
                "Recursive registry build detected: " + key.key().value());
        }
        try {
            var registry = buildRegistry(key);
            var old = cache.putIfAbsent(key, registry);
            return (org.bukkit.Registry<T>) (old != null ? old : registry);
        } finally {
            inProgress.remove(key);
        }
    }

    @Override
    @Deprecated
    @SuppressWarnings("unchecked")
    public <T extends Keyed> org.bukkit.Registry<T> getRegistry(Class<T> type) {
        var key = (RegistryKey<T>) CLASS_TO_KEY.get(type);
        return key != null ? getRegistry(key) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T extends Keyed> org.bukkit.Registry<T> buildRegistry(RegistryKey<?> registryKey) {
        var server = serverRef;
        if (server == null) {
            throw new IllegalStateException("Minecraft server not initialized yet");
        }
        var adventureKey = registryKey.key();
        var mcRegistryKey = ResourceKey.createRegistryKey(
            Identifier.fromNamespaceAndPath(adventureKey.namespace(), adventureKey.value()));
        Registry mcRegistry = server.registryAccess().lookupOrThrow(mcRegistryKey);
        return new VeltisBukkitRegistry<>(mcRegistry, (RegistryKey<T>) registryKey);
    }
}
