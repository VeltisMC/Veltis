package org.veltismc.veltis;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.veltismc.veltis.inventory.VeltisContainerAdapter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VeltisInventoryProxy {

    private VeltisInventoryProxy() {}

    public static Inventory wrapNmsContainer(Object nmsContainer, InventoryHolder holder, InventoryType type, String title) {
        int size = 27;
        try {
            var getSize = nmsContainer.getClass().getMethod("getContainerSize");
            size = (int) getSize.invoke(nmsContainer);
        } catch (Exception ignored) {}
        return new VeltisContainerAdapter(nmsContainer, holder, type, title, size);
    }

    public static Inventory wrapNmsContainer(Object nmsContainer, InventoryHolder holder, InventoryType type, String title, int size) {
        return new VeltisContainerAdapter(nmsContainer, holder, type, title, size);
    }

    public static PlayerInventory createPlayerInventory(HumanEntity holder) {
        return (PlayerInventory) Proxy.newProxyInstance(
            PlayerInventory.class.getClassLoader(),
            new Class<?>[]{PlayerInventory.class},
            new PlayerInventoryHandler(holder)
        );
    }

    public static Inventory createInventory(InventoryHolder owner, int size, String title) {
        return (Inventory) Proxy.newProxyInstance(
            Inventory.class.getClassLoader(),
            new Class<?>[]{Inventory.class},
            new InventoryHandler(owner, size, title != null ? title : "container")
        );
    }

    private record PlayerInventoryHandler(HumanEntity holder) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                return switch (method.getName()) {
                    case "getHolder" -> holder;
                    case "getSize" -> 36 + 5 + 1; // 36 storage + 5 armor + offhand
                    case "getStorageContents" -> new ItemStack[36];
                    case "getContents" -> new ItemStack[41];
                    case "getArmorContents" -> new ItemStack[4];
                    case "getExtraContents" -> new ItemStack[1];
                    case "setItem" -> null;
                    case "getItem" -> {
                        if (args != null && args.length > 0 && args[0] instanceof Integer slot) {
                            if (slot >= 36 && slot < 40) yield ItemStack.empty();
                            yield ItemStack.empty();
                        }
                        yield ItemStack.empty();
                    }
                    case "first" -> -1;
                    case "firstEmpty" -> -1;
                    case "addItem" -> new HashMap<Integer, ItemStack>();
                    case "removeItem" -> new HashMap<Integer, ItemStack>();
                    case "all" -> new HashMap<Integer, ItemStack>();
                    case "contains" -> false;
                    case "containsAtLeast" -> false;
                    case "clear" -> null;
                    case "iterator" -> java.util.Collections.emptyIterator();
                    case "listIterator" -> java.util.Collections.emptyIterator();
                    case "getTitle" -> "container.inventory";
                    case "isEmpty" -> true;
                    case "getType" -> InventoryType.PLAYER;
                    case "getLocation" -> null;
                    case "getInventoryType" -> InventoryType.PLAYER;
                    case "getClass" -> PlayerInventory.class;
                    case "toString" -> "VeltisPlayerInventory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> defaultReturn(method.getReturnType());
                };
            } catch (Exception e) {
                return defaultReturn(method.getReturnType());
            }
        }
    }

    private record InventoryHandler(InventoryHolder owner, int size, String title) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                return switch (method.getName()) {
                    case "getHolder" -> owner;
                    case "getSize" -> size;
                    case "getStorageContents", "getContents" -> new ItemStack[size];
                    case "setItem" -> null;
                    case "getItem" -> ItemStack.empty();
                    case "first" -> -1;
                    case "firstEmpty" -> -1;
                    case "addItem" -> new HashMap<Integer, ItemStack>();
                    case "removeItem" -> new HashMap<Integer, ItemStack>();
                    case "all" -> new HashMap<Integer, ItemStack>();
                    case "contains" -> false;
                    case "containsAtLeast" -> false;
                    case "clear" -> null;
                    case "iterator" -> java.util.Collections.emptyIterator();
                    case "listIterator" -> java.util.Collections.emptyIterator();
                    case "getTitle" -> title;
                    case "isEmpty" -> true;
                    case "getType" -> InventoryType.CHEST;
                    case "getInventoryType" -> InventoryType.CHEST;
                    case "getLocation" -> null;
                    case "toString" -> "VeltisInventory{" + title + "}";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> defaultReturn(method.getReturnType());
                };
            } catch (Exception e) {
                return defaultReturn(method.getReturnType());
            }
        }
    }

    private static Object defaultReturn(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
