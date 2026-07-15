package org.veltismc.veltis.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

public final class VeltisItemStackBridge {

    private static final Logger LOG = System.getLogger(VeltisItemStackBridge.class.getName());

    private static final String NMS_ITEMSTACK = "net.minecraft.world.item.ItemStack";
    private static final String NMS_ITEM = "net.minecraft.world.item.Item";
    private static final String NMS_BUILTIN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries";
    private static final String NMS_REGISTRY = "net.minecraft.core.Registry";
    private static final String NMS_RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
    private static final String NMS_IDENTIFIER = "net.minecraft.resources.Identifier";

    private static Class<?> nmsItemStackClass;
    private static Class<?> nmsItemClass;
    private static Class<?> builtInRegistriesClass;
    private static Class<?> registryClass;
    private static Class<?> resourceKeyClass;
    private static Class<?> identifierClass;
    private static Method registryGetValue;
    private static Constructor<?> itemStackConstructor;
    private static Method itemStackGetItem;
    private static Method itemStackGetCount;
    private static Method itemStackCopy;
    private static Method itemStackIsEmpty;
    private static Method itemStackSetCount;
    private static Method itemStackSplit;
    private static Method itemGetKey;
    private static Method resourceKeyLocation;
    private static Method locationGetNamespace;
    private static Method locationGetPath;
    private static boolean initialized;

    public static void initialize() {
        if (initialized) return;
        try {
            nmsItemStackClass = Class.forName(NMS_ITEMSTACK);
            nmsItemClass = Class.forName(NMS_ITEM);
            builtInRegistriesClass = Class.forName(NMS_BUILTIN_REGISTRIES);
            registryClass = Class.forName(NMS_REGISTRY);
            resourceKeyClass = Class.forName(NMS_RESOURCE_KEY);

            // In MC 26.2, ResourceLocation was renamed to Identifier
            identifierClass = Class.forName(NMS_IDENTIFIER);

            // Use Registry.getValue(Identifier) instead of BuiltInRegistries.get(ResourceKey)
            registryGetValue = registryClass.getMethod("getValue", identifierClass);
            itemStackConstructor = nmsItemStackClass.getConstructor(nmsItemClass, int.class);
            itemStackGetItem = nmsItemStackClass.getMethod("getItem");
            itemStackGetCount = nmsItemStackClass.getMethod("getCount");
            itemStackCopy = nmsItemStackClass.getMethod("copy");
            itemStackIsEmpty = nmsItemStackClass.getMethod("isEmpty");
            itemStackSetCount = nmsItemStackClass.getMethod("setCount", int.class);
            itemStackSplit = nmsItemStackClass.getMethod("split", int.class);

            itemGetKey = nmsItemClass.getMethod("getKey");
            resourceKeyLocation = resourceKeyClass.getMethod("location");
            locationGetNamespace = identifierClass.getMethod("getNamespace");
            locationGetPath = identifierClass.getMethod("getPath");

            initialized = true;
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Failed to initialize ItemStack bridge", e);
        }
    }

    public static Object toNms(ItemStack bukkit) {
        if (bukkit == null || bukkit.isEmpty()) return getEmptyNms();
        if (!initialized) initialize();
        try {
            var nmsItem = resolveNmsItem(bukkit.getType());
            if (nmsItem == null) return getEmptyNms();
            return itemStackConstructor.newInstance(nmsItem, bukkit.getAmount());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to convert Bukkit ItemStack to NMS", e);
            return getEmptyNms();
        }
    }

    public static ItemStack toBukkit(Object nms) {
        if (nms == null) return ItemStack.empty();
        if (!initialized) initialize();
        try {
            if ((boolean) itemStackIsEmpty.invoke(nms)) return ItemStack.empty();
            var item = itemStackGetItem.invoke(nms);
            var material = resolveMaterial(item);
            if (material == null || !material.isItem()) return ItemStack.empty();
            var count = (int) itemStackGetCount.invoke(nms);
            // VeltisMC: use VeltisItemStack to avoid recursive constructor call
            return new org.veltismc.veltis.inventory.VeltisItemStack(material, count);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to convert NMS ItemStack to Bukkit", e);
            return ItemStack.empty();
        }
    }

    public static Object copyNms(Object nms) {
        if (nms == null) return null;
        if (!initialized) initialize();
        try {
            if ((boolean) itemStackIsEmpty.invoke(nms)) return getEmptyNms();
            return itemStackCopy.invoke(nms);
        } catch (Exception e) {
            return getEmptyNms();
        }
    }

    public static Object getEmptyNms() {
        if (!initialized) initialize();
        try {
            var field = nmsItemStackClass.getField("EMPTY");
            return field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isEmptyNms(Object nms) {
        if (nms == null) return true;
        if (!initialized) initialize();
        try {
            return (boolean) itemStackIsEmpty.invoke(nms);
        } catch (Exception e) {
            return true;
        }
    }

    private static Object resolveNmsItem(Material material) {
        try {
            var identifier = identifierClass.getConstructor(String.class, String.class)
                .newInstance(material.getKey().namespace(), material.getKey().value());
            var itemRegistry = builtInRegistriesClass.getField("ITEM").get(null);
            return registryGetValue.invoke(itemRegistry, identifier);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not resolve NMS Item for material {0}", material);
            return null;
        }
    }

    private static Material resolveMaterial(Object nmsItem) {
        try {
            var key = itemGetKey.invoke(nmsItem);
            var location = resourceKeyLocation.invoke(key);
            var namespace = (String) locationGetNamespace.invoke(location);
            var path = (String) locationGetPath.invoke(location);
            return Material.getMaterial(path.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private VeltisItemStackBridge() {}
}
