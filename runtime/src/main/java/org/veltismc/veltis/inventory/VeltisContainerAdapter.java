package org.veltismc.veltis.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.*;

public class VeltisContainerAdapter implements Inventory {

    private static final Logger LOG = System.getLogger(VeltisContainerAdapter.class.getName());
    private static final String NMS_CONTAINER = "net.minecraft.world.Container";

    private static Class<?> containerInterface;
    private static Method getContainerSize;
    private static Method isEmptyMethod;
    private static Method getItem;
    private static Method removeItem;
    private static Method removeItemNoUpdate;
    private static Method setItem;
    private static Method setChanged;
    private static Method stillValid;
    private static Method startOpen;
    private static Method stopOpen;
    private static Method getViewers;
    private static boolean reflectionInit;

    private final Object nmsContainer;
    private final InventoryHolder holder;
    private final InventoryType type;
    private final int size;

    public VeltisContainerAdapter(Object nmsContainer, InventoryHolder holder,
                                   InventoryType type, String title, int size) {
        this.nmsContainer = nmsContainer;
        this.holder = holder;
        this.type = type;
        this.size = size;
        ensureReflection();
    }

    private static void ensureReflection() {
        if (reflectionInit) return;
        try {
            containerInterface = Class.forName(NMS_CONTAINER);
            getContainerSize = containerInterface.getMethod("getContainerSize");
            isEmptyMethod = containerInterface.getMethod("isEmpty");
            getItem = containerInterface.getMethod("getItem", int.class);
            removeItem = containerInterface.getMethod("removeItem", int.class, int.class);
            removeItemNoUpdate = containerInterface.getMethod("removeItemNoUpdate", int.class);
            setItem = containerInterface.getMethod("setItem", int.class, Class.forName("net.minecraft.world.item.ItemStack"));
            setChanged = containerInterface.getMethod("setChanged");
            stillValid = containerInterface.getMethod("stillValid", Class.forName("net.minecraft.world.entity.player.Player"));
            try { startOpen = containerInterface.getMethod("startOpen", Class.forName("net.minecraft.world.entity.ContainerUser")); } catch (Exception ignored) {}
            try { stopOpen = containerInterface.getMethod("stopOpen", Class.forName("net.minecraft.world.entity.ContainerUser")); } catch (Exception ignored) {}
            try { getViewers = containerInterface.getMethod("getEntitiesWithContainerOpen"); } catch (Exception ignored) {}
            reflectionInit = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to initialize NMS Container reflection", e);
        }
    }

    @Override
    public int getSize() {
        if (nmsContainer == null) return size;
        try { return (int) getContainerSize.invoke(nmsContainer); } catch (Exception e) { return size; }
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public void setMaxStackSize(int size) {}

    @Override
    public boolean isEmpty() {
        if (nmsContainer == null) return true;
        try { return (boolean) isEmptyMethod.invoke(nmsContainer); } catch (Exception e) { return true; }
    }

    @Override
    public @NotNull ItemStack getItem(int index) {
        if (nmsContainer == null) return ItemStack.empty();
        try {
            var nms = getItem.invoke(nmsContainer, index);
            return VeltisItemStackBridge.toBukkit(nms);
        } catch (Exception e) { return ItemStack.empty(); }
    }

    @Override
    public void setItem(int index, @Nullable ItemStack item) {
        if (nmsContainer == null) return;
        try {
            var nms = VeltisItemStackBridge.toNms(item);
            setItem.invoke(nmsContainer, index, nms);
            setChanged.invoke(nmsContainer);
        } catch (Exception e) { LOG.log(Level.DEBUG, "setItem failed", e); }
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> addItem(@NotNull ItemStack... items) throws IllegalArgumentException {
        var leftover = new HashMap<Integer, ItemStack>();
        for (int i = 0; i < items.length; i++) {
            var item = items[i].clone();
            for (int slot = 0; slot < getSize(); slot++) {
                var existing = getItem(slot);
                if (existing.isEmpty()) {
                    setItem(slot, item);
                    item = null;
                    break;
                } else if (existing.isSimilar(item) && existing.getAmount() < existing.getMaxStackSize()) {
                    var space = existing.getMaxStackSize() - existing.getAmount();
                    var toAdd = Math.min(space, item.getAmount());
                    existing.setAmount(existing.getAmount() + toAdd);
                    setItem(slot, existing);
                    item.setAmount(item.getAmount() - toAdd);
                    if (item.getAmount() <= 0) { item = null; break; }
                }
            }
            if (item != null && item.getAmount() > 0) leftover.put(i, item);
        }
        return leftover;
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> removeItem(@NotNull ItemStack... items) throws IllegalArgumentException {
        var removed = new HashMap<Integer, ItemStack>();
        for (int i = 0; i < items.length; i++) {
            var toRemove = items[i].clone();
            for (int slot = 0; slot < getSize() && toRemove.getAmount() > 0; slot++) {
                var existing = getItem(slot);
                if (existing.isEmpty() || !existing.isSimilar(toRemove)) continue;
                var removeCount = Math.min(existing.getAmount(), toRemove.getAmount());
                existing.setAmount(existing.getAmount() - removeCount);
                setItem(slot, existing.getAmount() <= 0 ? ItemStack.empty() : existing);
                toRemove.setAmount(toRemove.getAmount() - removeCount);
            }
            removed.put(i, items[i].clone());
        }
        return removed;
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> removeItemAnySlot(@NotNull ItemStack... items) throws IllegalArgumentException {
        return removeItem(items);
    }

    @Override
    public @NotNull ItemStack[] getContents() {
        var contents = new ItemStack[getSize()];
        for (int i = 0; i < contents.length; i++) contents[i] = getItem(i);
        return contents;
    }

    @Override
    public void setContents(@NotNull ItemStack[] items) throws IllegalArgumentException {
        clear();
        for (int i = 0; i < Math.min(items.length, getSize()); i++) {
            if (items[i] != null) setItem(i, items[i].clone());
        }
    }

    @Override
    public @NotNull ItemStack[] getStorageContents() { return getContents(); }

    @Override
    public void setStorageContents(@NotNull ItemStack[] items) throws IllegalArgumentException { setContents(items); }

    @Override
    public boolean contains(@NotNull Material material) throws IllegalArgumentException { return first(material) >= 0; }

    @Override
    public boolean contains(@Nullable ItemStack item) { return first(item) >= 0; }

    @Override
    public boolean contains(@NotNull Material material, int amount) throws IllegalArgumentException {
        return containsAtLeast(new ItemStack(material, 1), amount);
    }

    @Override
    public boolean contains(@Nullable ItemStack item, int amount) { return containsAtLeast(item, amount); }

    @Override
    public boolean containsAtLeast(@Nullable ItemStack item, int amount) {
        if (item == null) return false;
        int count = 0;
        for (int i = 0; i < getSize(); i++) {
            var existing = getItem(i);
            if (!existing.isEmpty() && existing.isSimilar(item)) {
                count += existing.getAmount();
                if (count >= amount) return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull HashMap<Integer, ? extends ItemStack> all(@NotNull Material material) throws IllegalArgumentException {
        var result = new HashMap<Integer, ItemStack>();
        for (int i = 0; i < getSize(); i++) {
            var item = getItem(i);
            if (!item.isEmpty() && item.getType() == material) result.put(i, item);
        }
        return result;
    }

    @Override
    public @NotNull HashMap<Integer, ? extends ItemStack> all(@Nullable ItemStack item) {
        var result = new HashMap<Integer, ItemStack>();
        if (item == null) return result;
        for (int i = 0; i < getSize(); i++) {
            var existing = getItem(i);
            if (!existing.isEmpty() && existing.isSimilar(item)) result.put(i, existing);
        }
        return result;
    }

    @Override
    public int first(@NotNull Material material) throws IllegalArgumentException {
        for (int i = 0; i < getSize(); i++) {
            var item = getItem(i);
            if (!item.isEmpty() && item.getType() == material) return i;
        }
        return -1;
    }

    @Override
    public int first(@NotNull ItemStack item) {
        for (int i = 0; i < getSize(); i++) {
            var existing = getItem(i);
            if (!existing.isEmpty() && existing.isSimilar(item)) return i;
        }
        return -1;
    }

    @Override
    public int firstEmpty() {
        for (int i = 0; i < getSize(); i++) {
            if (getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    @Override
    public void clear() {
        for (int i = 0; i < getSize(); i++) setItem(i, ItemStack.empty());
    }

    @Override
    public void clear(int index) { setItem(index, ItemStack.empty()); }

    @Override
    public void remove(@NotNull Material material) throws IllegalArgumentException {
        for (int i = 0; i < getSize(); i++) {
            var item = getItem(i);
            if (!item.isEmpty() && item.getType() == material) setItem(i, ItemStack.empty());
        }
    }

    @Override
    public void remove(@NotNull ItemStack item) {
        for (int i = 0; i < getSize(); i++) {
            var existing = getItem(i);
            if (!existing.isEmpty() && existing.isSimilar(item)) setItem(i, ItemStack.empty());
        }
    }

    @Override
    public int close() {
        clear();
        return getSize();
    }

    @Override
    public @NotNull List<HumanEntity> getViewers() {
        if (getViewers != null && nmsContainer != null) {
            try {
                var nmsViewers = getViewers.invoke(nmsContainer);
                if (nmsViewers instanceof List<?> list) {
                    return new ArrayList<>((List<HumanEntity>) list);
                }
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    @Override
    public @NotNull InventoryType getType() { return type; }

    @Override
    public @Nullable InventoryHolder getHolder() { return holder; }

    @Override
    public @Nullable InventoryHolder getHolder(boolean useSnapshot) { return holder; }

    @Override
    public @NotNull ListIterator<ItemStack> iterator() {
        return iterator(0);
    }

    @Override
    public @NotNull ListIterator<ItemStack> iterator(int index) {
        var items = new ArrayList<ItemStack>();
        for (int i = 0; i < getSize(); i++) items.add(getItem(i));
        return items.listIterator(index);
    }

    @Override
    public @Nullable Location getLocation() { return null; }
}
