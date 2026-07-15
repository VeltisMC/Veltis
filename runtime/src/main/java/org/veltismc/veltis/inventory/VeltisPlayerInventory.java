package org.veltismc.veltis.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.*;

public class VeltisPlayerInventory implements PlayerInventory {

    private static final Logger LOG = System.getLogger(VeltisPlayerInventory.class.getName());

    private static final String NMS_INVENTORY = "net.minecraft.world.entity.player.Inventory";
    private static final String NMS_ITEMSTACK = "net.minecraft.world.item.ItemStack";

    private static Class<?> nmsInventoryClass;
    private static Class<?> nmsItemStackClass;
    private static Method getItem;
    private static Method setItem;
    private static Method getContainerSize;
    private static Method isEmptyMethod;
    private static Method removeItem;
    private static Method removeItemNoUpdate;
    private static Method setChanged;
    private static Method getSelectedSlot;
    private static Method setSelectedSlot;
    private static Method getSelectedItem;
    private static boolean reflectionInit;

    private final Object mcPlayer;
    private final Object nmsInventory;
    private final HumanEntity holder;
    private volatile int cachedSize;

    private static final int OFFHAND = 40;

    public VeltisPlayerInventory(Object mcPlayer, HumanEntity holder) {
        this.mcPlayer = mcPlayer;
        this.holder = holder;
        this.nmsInventory = resolveNmsInventory();
        this.cachedSize = 41;
        ensureReflection();
    }

    private Object resolveNmsInventory() {
        if (mcPlayer == null) return null;
        try {
            return mcPlayer.getClass().getMethod("getInventory").invoke(mcPlayer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to resolve NMS Inventory from player", e);
            return null;
        }
    }

    private static void ensureReflection() {
        if (reflectionInit) return;
        try {
            var cl = VeltisPlayerInventory.class.getClassLoader();
            nmsInventoryClass = Class.forName(NMS_INVENTORY);
            nmsItemStackClass = Class.forName(NMS_ITEMSTACK);

            getContainerSize = nmsInventoryClass.getMethod("getContainerSize");
            isEmptyMethod = nmsInventoryClass.getMethod("isEmpty");
            getItem = nmsInventoryClass.getMethod("getItem", int.class);
            setItem = nmsInventoryClass.getMethod("setItem", int.class, nmsItemStackClass);
            removeItem = nmsInventoryClass.getMethod("removeItem", int.class, int.class);
            removeItemNoUpdate = nmsInventoryClass.getMethod("removeItemNoUpdate", int.class);
            setChanged = nmsInventoryClass.getMethod("setChanged");
            getSelectedSlot = nmsInventoryClass.getMethod("getSelectedSlot");
            setSelectedSlot = nmsInventoryClass.getMethod("setSelectedSlot", int.class);
            getSelectedItem = nmsInventoryClass.getMethod("getSelectedItem");

            reflectionInit = true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to initialize NMS PlayerInventory reflection", e);
        }
    }

    @Override
    public int getSize() {
        if (nmsInventory == null) return cachedSize;
        try { return (int) getContainerSize.invoke(nmsInventory); }
        catch (Exception e) { return cachedSize; }
    }

    @Override
    public int getMaxStackSize() { return 64; }

    @Override
    public void setMaxStackSize(int size) {}

    @Override
    public boolean isEmpty() {
        if (nmsInventory == null) return true;
        try { return (boolean) isEmptyMethod.invoke(nmsInventory); }
        catch (Exception e) { return true; }
    }

    @Override
    public @NotNull ItemStack getItem(int index) {
        if (nmsInventory == null) return ItemStack.empty();
        try {
            var nms = getItem.invoke(nmsInventory, index);
            return VeltisItemStackBridge.toBukkit(nms);
        } catch (Exception e) { return ItemStack.empty(); }
    }

    @Override
    public void setItem(int index, @Nullable ItemStack item) {
        if (nmsInventory == null) return;
        try {
            var nms = VeltisItemStackBridge.toNms(item);
            setItem.invoke(nmsInventory, index, nms);
            setChanged.invoke(nmsInventory);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "setItem({0}) failed", index);
        }
    }

    @Override
    public void setItem(@NotNull EquipmentSlot slot, @Nullable ItemStack item) {
        setItem(slot.ordinal(), item);
    }

    @Override
    public @NotNull ItemStack getItem(@NotNull EquipmentSlot slot) {
        return getItem(slot.ordinal());
    }

    @Override
    public @NotNull HashMap<Integer, ItemStack> addItem(@NotNull ItemStack... items) throws IllegalArgumentException {
        var leftover = new HashMap<Integer, ItemStack>();
        for (int i = 0; i < items.length; i++) {
            var item = items[i].clone();
            for (int slot = 0; slot < getSize() && item.getAmount() > 0; slot++) {
                var existing = getItem(slot);
                if (existing.isEmpty() || !existing.isSimilar(item)) continue;
                var space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) continue;
                var toAdd = Math.min(space, item.getAmount());
                existing.setAmount(existing.getAmount() + toAdd);
                setItem(slot, existing);
                item.setAmount(item.getAmount() - toAdd);
            }
            if (item.getAmount() > 0) {
                for (int slot = 0; slot < getSize() && item.getAmount() > 0; slot++) {
                    if (getItem(slot).isEmpty()) {
                        var stackSize = Math.min(item.getAmount(), item.getMaxStackSize());
                        var toPlace = item.clone();
                        toPlace.setAmount(stackSize);
                        setItem(slot, toPlace);
                        item.setAmount(item.getAmount() - stackSize);
                    }
                }
            }
            if (item.getAmount() > 0) leftover.put(i, item);
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
        var size = getSize();
        var contents = new ItemStack[size];
        for (int i = 0; i < size; i++) contents[i] = getItem(i);
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
    public @NotNull ItemStack[] getStorageContents() {
        var storage = new ItemStack[36];
        for (int i = 0; i < 36; i++) storage[i] = getItem(i);
        return storage;
    }

    @Override
    public void setStorageContents(@NotNull ItemStack[] items) throws IllegalArgumentException {
        for (int i = 0; i < Math.min(items.length, 36); i++) {
            setItem(i, items[i] != null ? items[i].clone() : ItemStack.empty());
        }
    }

    @Override
    public @NotNull ItemStack[] getArmorContents() {
        var armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) armor[i] = getItem(36 + i);
        return armor;
    }

    @Override
    public void setArmorContents(@NotNull ItemStack[] items) {
        for (int i = 0; i < Math.min(items.length, 4); i++) {
            setItem(36 + i, items[i] != null ? items[i].clone() : ItemStack.empty());
        }
    }

    @Override
    public @NotNull ItemStack[] getExtraContents() {
        return new ItemStack[]{ getItem(OFFHAND) };
    }

    @Override
    public void setExtraContents(@NotNull ItemStack[] items) {
        if (items.length > 0) setItem(OFFHAND, items[0] != null ? items[0].clone() : ItemStack.empty());
    }

    @Override
    public @Nullable ItemStack getHelmet() { return getItem(39); }

    @Override
    public @Nullable ItemStack getChestplate() { return getItem(38); }

    @Override
    public @Nullable ItemStack getLeggings() { return getItem(37); }

    @Override
    public @Nullable ItemStack getBoots() { return getItem(36); }

    @Override
    public void setHelmet(@Nullable ItemStack helmet) { setItem(39, helmet); }

    @Override
    public void setChestplate(@Nullable ItemStack chestplate) { setItem(38, chestplate); }

    @Override
    public void setLeggings(@Nullable ItemStack leggings) { setItem(37, leggings); }

    @Override
    public void setBoots(@Nullable ItemStack boots) { setItem(36, boots); }

    @Override
    public @NotNull ItemStack getItemInMainHand() { return getItem(getHeldItemSlot()); }

    @Override
    public void setItemInMainHand(@Nullable ItemStack item) { setItem(getHeldItemSlot(), item); }

    @Override
    public @NotNull ItemStack getItemInOffHand() { return getItem(OFFHAND); }

    @Override
    public void setItemInOffHand(@Nullable ItemStack item) { setItem(OFFHAND, item); }

    @Override
    public int getHeldItemSlot() {
        if (nmsInventory == null) return 0;
        try { return (int) getSelectedSlot.invoke(nmsInventory); }
        catch (Exception e) { return 0; }
    }

    @Override
    public void setHeldItemSlot(int slot) {
        if (nmsInventory == null) return;
        try { setSelectedSlot.invoke(nmsInventory, slot); }
        catch (Exception e) { LOG.log(Level.DEBUG, "setHeldItemSlot failed", e); }
    }

    @Override @Deprecated
    public ItemStack getItemInHand() { return getItemInMainHand(); }

    @Override @Deprecated
    public void setItemInHand(@Nullable ItemStack stack) { setItemInMainHand(stack); }

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
        return getSize();
    }

    @Override
    public @NotNull List<HumanEntity> getViewers() {
        return List.of(holder);
    }

    @Override
    public @NotNull InventoryType getType() { return InventoryType.PLAYER; }

    @Override
    public @Nullable HumanEntity getHolder() { return holder; }

    @Override
    public @Nullable InventoryHolder getHolder(boolean useSnapshot) { return holder; }

    @Override
    public @NotNull ListIterator<ItemStack> iterator() { return iterator(0); }

    @Override
    public @NotNull ListIterator<ItemStack> iterator(int index) {
        var items = new ArrayList<ItemStack>();
        for (int i = 0; i < getSize(); i++) items.add(getItem(i));
        return items.listIterator(index);
    }

    @Override
    public @Nullable Location getLocation() { return null; }
}
