package org.veltismc.veltis.registry;

import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.kyori.adventure.key.Key;
import net.minecraft.resources.Identifier;
import org.bukkit.GameRule;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class VeltisBukkitRegistry<T extends Keyed> implements Registry<T> {

    private final Map<NamespacedKey, T> entries = new ConcurrentHashMap<>();
    private final RegistryKey<T> registryKey;
    private final net.minecraft.core.Registry<?> mcRegistry;
    private final Set<Identifier> mcKeys;
    private volatile boolean populated;

    @SuppressWarnings("unchecked")
    VeltisBukkitRegistry(net.minecraft.core.Registry<?> mcRegistry, RegistryKey<T> registryKey) {
        this.registryKey = registryKey;
        this.mcRegistry = mcRegistry;
        this.mcKeys = mcRegistry.keySet();
    }

    private T getOrCreate(NamespacedKey key, Identifier mcKey) {
        return entries.computeIfAbsent(key, k -> (T) createEntry(k, registryKey, mcRegistry));
    }

    private void ensurePopulated() {
        if (!populated) {
            synchronized (this) {
                if (!populated) {
                    for (Identifier mcKey : mcKeys) {
                        var nsKey = new NamespacedKey(mcKey.getNamespace(), mcKey.getPath());
                        T value = (T) createEntry(nsKey, registryKey, mcRegistry);
                        if (value != null) {
                            entries.put(nsKey, value);
                        }
                    }
                    populated = true;
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static Object createEntry(NamespacedKey key, RegistryKey<?> registryKey, net.minecraft.core.Registry<?> mcRegistry) {
        if (RegistryKey.BIOME.equals(registryKey)) {
            return createBiomeProxy(key);
        }
        if (RegistryKey.GAME_RULE.equals(registryKey)) {
            return createGameRuleEntry(key, mcRegistry);
        }
        return createGenericProxy(key, registryKey, mcRegistry);
    }

    private static Object createBiomeProxy(NamespacedKey key) {
        return Proxy.newProxyInstance(
            org.bukkit.block.Biome.class.getClassLoader(),
            new Class<?>[] { org.bukkit.block.Biome.class },
            new BiomeInvocationHandler(key)
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object createGenericProxy(NamespacedKey key, RegistryKey<?> registryKey, net.minecraft.core.Registry<?> mcRegistry) {
        Class<?> iface = KEY_TO_CLASS.get(registryKey.key());
        if (iface == null) iface = org.bukkit.Keyed.class;
        // Cannot proxy enum types; their constants are already defined at compile time
        if (iface.isEnum()) return null;
        return Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { iface, org.bukkit.Keyed.class }, new GenericInvocationHandler(key, registryKey, mcRegistry));
    }

    private static final Map<Key, Class<?>> KEY_TO_CLASS = new HashMap<>();
    static {
        try {
            KEY_TO_CLASS.put(RegistryKey.ITEM.key(), org.bukkit.inventory.ItemType.class);
            KEY_TO_CLASS.put(RegistryKey.BLOCK.key(), org.bukkit.block.BlockType.class);
            KEY_TO_CLASS.put(RegistryKey.ENTITY_TYPE.key(), org.bukkit.entity.EntityType.class);
            KEY_TO_CLASS.put(RegistryKey.ATTRIBUTE.key(), org.bukkit.attribute.Attribute.class);
            KEY_TO_CLASS.put(RegistryKey.ENCHANTMENT.key(), org.bukkit.enchantments.Enchantment.class);
            KEY_TO_CLASS.put(RegistryKey.MOB_EFFECT.key(), org.bukkit.potion.PotionEffectType.class);
            KEY_TO_CLASS.put(RegistryKey.POTION.key(), org.bukkit.potion.PotionType.class);
            KEY_TO_CLASS.put(RegistryKey.PARTICLE_TYPE.key(), org.bukkit.Particle.class);
            KEY_TO_CLASS.put(RegistryKey.SOUND_EVENT.key(), org.bukkit.Sound.class);
            KEY_TO_CLASS.put(RegistryKey.FLUID.key(), org.bukkit.Fluid.class);
            KEY_TO_CLASS.put(RegistryKey.STRUCTURE_TYPE.key(), org.bukkit.generator.structure.StructureType.class);
            KEY_TO_CLASS.put(RegistryKey.STRUCTURE.key(), org.bukkit.generator.structure.Structure.class);
            KEY_TO_CLASS.put(RegistryKey.CAT_VARIANT.key(), org.bukkit.entity.Cat.Type.class);
            KEY_TO_CLASS.put(RegistryKey.FROG_VARIANT.key(), org.bukkit.entity.Frog.Variant.class);
            KEY_TO_CLASS.put(RegistryKey.WOLF_VARIANT.key(), org.bukkit.entity.Wolf.Variant.class);
            KEY_TO_CLASS.put(RegistryKey.CHICKEN_VARIANT.key(), org.bukkit.entity.Chicken.Variant.class);
            KEY_TO_CLASS.put(RegistryKey.PAINTING_VARIANT.key(), org.bukkit.Art.class);
            KEY_TO_CLASS.put(RegistryKey.BANNER_PATTERN.key(), org.bukkit.block.banner.PatternType.class);
            KEY_TO_CLASS.put(RegistryKey.INSTRUMENT.key(), org.bukkit.MusicInstrument.class);
            KEY_TO_CLASS.put(RegistryKey.TRIM_MATERIAL.key(), org.bukkit.inventory.meta.trim.TrimMaterial.class);
            KEY_TO_CLASS.put(RegistryKey.TRIM_PATTERN.key(), org.bukkit.inventory.meta.trim.TrimPattern.class);
            KEY_TO_CLASS.put(RegistryKey.DAMAGE_TYPE.key(), org.bukkit.damage.DamageType.class);
            KEY_TO_CLASS.put(RegistryKey.JUKEBOX_SONG.key(), org.bukkit.JukeboxSong.class);
            KEY_TO_CLASS.put(RegistryKey.MENU.key(), org.bukkit.inventory.MenuType.class);
            KEY_TO_CLASS.put(RegistryKey.MAP_DECORATION_TYPE.key(), org.bukkit.map.MapCursor.Type.class);
            KEY_TO_CLASS.put(RegistryKey.GAME_EVENT.key(), org.bukkit.GameEvent.class);
            KEY_TO_CLASS.put(RegistryKey.POINT_OF_INTEREST_TYPE.key(), io.papermc.paper.entity.poi.PoiType.class);
            KEY_TO_CLASS.put(RegistryKey.VILLAGER_PROFESSION.key(), org.bukkit.entity.Villager.Profession.class);
            KEY_TO_CLASS.put(RegistryKey.VILLAGER_TYPE.key(), org.bukkit.entity.Villager.Type.class);
            KEY_TO_CLASS.put(RegistryKey.DATA_COMPONENT_TYPE.key(), io.papermc.paper.datacomponent.DataComponentType.class);
        } catch (Exception e) {
            // ignore - some types may not be loadable
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static org.bukkit.GameRule<?> createGameRuleEntry(NamespacedKey key, net.minecraft.core.Registry<?> mcRegistry) {
        var mcKey = Identifier.fromNamespaceAndPath(key.getNamespace(), key.getKey());
        var opt = mcRegistry.getOptional(mcKey);
        if (opt.isEmpty()) return null;
        var mcRule = (net.minecraft.world.level.gamerules.GameRule<?>) opt.get();
        if (mcRule == null) return null;

        var id = mcRule.getIdentifierWithFallback();

        return new GameRule() {
            @Override
            public String getName() {
                return id.getPath();
            }

            @Override
            public Class<?> getType() {
                return mcRule.valueClass();
            }

            @Override
            public Object getDefaultValue() {
                return mcRule.defaultValue();
            }

            @Override
            public NamespacedKey getKey() {
                return new NamespacedKey(id.getNamespace(), id.getPath());
            }

            @Override
            public String translationKey() {
                return mcRule.getDescriptionId();
            }
        };
    }

    @Override
    public @Nullable T get(NamespacedKey key) {
        var existing = entries.get(key);
        if (existing != null) return existing;
        if (populated) return null;
        var mcKey = Identifier.fromNamespaceAndPath(key.getNamespace(), key.getKey());
        if (!mcKeys.contains(mcKey)) return null;
        return getOrCreate(key, mcKey);
    }

    @Override
    public @Nullable NamespacedKey getKey(T value) {
        return value.getKey();
    }

    @Override
    public Stream<T> stream() {
        ensurePopulated();
        return entries.values().stream();
    }

    @Override
    public Stream<NamespacedKey> keyStream() {
        ensurePopulated();
        return entries.keySet().stream();
    }

    @Override
    public Iterator<T> iterator() {
        ensurePopulated();
        return entries.values().iterator();
    }

    @Override
    public int size() {
        ensurePopulated();
        return entries.size();
    }

    @Override
    public boolean hasTag(TagKey<T> key) {
        return false;
    }

    @Override
    public Tag<T> getTag(TagKey<T> key) {
        throw new NoSuchElementException("No tag found for " + key);
    }

    @Override
    public Collection<Tag<T>> getTags() {
        return List.of();
    }

    @Override
    public String toString() {
        return "VeltisBukkitRegistry{size=" + size() + "}";
    }

    private static final class BiomeInvocationHandler implements InvocationHandler {
        private final NamespacedKey key;

        BiomeInvocationHandler(NamespacedKey key) {
            this.key = key;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getKey" -> key;
                case "name" -> key.getKey();
                case "ordinal" -> 0;
                case "compareTo" -> key.compareTo(((org.bukkit.block.Biome) args[0]).getKey());
                case "translationKey" -> "biome.minecraft." + key.getKey();
                case "toString" -> key.toString();
                case "hashCode" -> key.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static final class GenericInvocationHandler implements InvocationHandler {
        private final NamespacedKey key;
        private final RegistryKey<?> registryKey;
        private final net.minecraft.core.Registry<?> mcRegistry;

        GenericInvocationHandler(NamespacedKey key, RegistryKey<?> registryKey, net.minecraft.core.Registry<?> mcRegistry) {
            this.key = key;
            this.registryKey = registryKey;
            this.mcRegistry = mcRegistry;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getKey":
                    return key;
                case "name":
                    return key.getKey().toUpperCase(java.util.Locale.ROOT);
                case "ordinal":
                    return 0;
                case "toString":
                    return key.toString();
                case "hashCode":
                    return key.hashCode();
                case "equals":
                    return proxy == args[0];
                case "translationKey":
                case "getTranslationKey":
                    return "registry.minecraft." + key.getKey();
                case "compareTo":
                    if (args != null && args.length > 0 && args[0] instanceof org.bukkit.Keyed k) {
                        return key.compareTo(k.getKey());
                    }
                    return 0;
                case "createItemStack":
                    var amount = args != null && args.length > 0 && args[0] instanceof Number n ? n.intValue() : 1;
                    return createItemStackViaNms(key, amount);
            }
            // Return safe defaults for unknown methods
            var ret = method.getReturnType();
            if (!ret.isPrimitive()) return null;
            if (ret == boolean.class) return false;
            if (ret == int.class || ret == long.class || ret == short.class || ret == byte.class) return 0;
            if (ret == float.class || ret == double.class) return 0.0;
            if (ret == char.class) return '\0';
            return null;
        }

        private ItemStack createItemStackViaNms(NamespacedKey itemKey, int amount) {
            try {
                var mcKey = net.minecraft.resources.Identifier.fromNamespaceAndPath(itemKey.getNamespace(), itemKey.getKey());
                var reg = mcRegistry;
                if (reg == null) return new ItemStack(org.bukkit.Material.AIR);
                var nmsItemObj = reg.get(mcKey);
                if (nmsItemObj == null) return new ItemStack(org.bukkit.Material.AIR);
                var nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                var ctor = nmsItemStackClass.getConstructor(nmsItemObj.getClass(), int.class);
                var nms = ctor.newInstance(nmsItemObj, amount);
                return org.veltismc.veltis.inventory.VeltisItemStackBridge.toBukkit(nms);
            } catch (Exception e) {
                return new ItemStack(org.bukkit.Material.AIR);
            }
        }
    }
}
