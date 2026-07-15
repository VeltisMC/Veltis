package org.veltismc.veltis.inventory;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Map;

public class VeltisItemStack extends ItemStack {

    private final Material type;
    private final int amount;
    private ItemMeta itemMeta;

    public VeltisItemStack(Material type, int amount) {
        super();
        this.type = type;
        this.amount = amount;
        this.craftDelegate = this;
    }

    @Override
    public Material getType() {
        return craftDelegate == this ? type : super.getType();
    }

    @Override
    public int getAmount() {
        return craftDelegate == this ? amount : super.getAmount();
    }

    @Override
    public void setAmount(int value) {
        if (craftDelegate == this) return;
        super.setAmount(value);
    }

    @Override
    public boolean isEmpty() {
        return type == Material.AIR || amount <= 0;
    }

    @Override
    public ItemMeta getItemMeta() {
        return itemMeta;
    }

    @Override
    public boolean hasItemMeta() {
        return itemMeta != null;
    }

    @Override
    public boolean setItemMeta(ItemMeta meta) {
        this.itemMeta = meta;
        return true;
    }

    @Override
    public ItemStack clone() {
        var c = new VeltisItemStack(type, amount);
        if (itemMeta != null) c.itemMeta = itemMeta.clone();
        return c;
    }

    @Override
    public Map<Enchantment, Integer> getEnchantments() {
        return itemMeta != null && itemMeta.hasEnchants() ? itemMeta.getEnchants() : Map.of();
    }

    @Override
    public boolean containsEnchantment(Enchantment ench) {
        return itemMeta != null && itemMeta.hasEnchant(ench);
    }

    @Override
    public int getEnchantmentLevel(Enchantment ench) {
        return itemMeta != null && itemMeta.hasEnchant(ench) ? itemMeta.getEnchantLevel(ench) : 0;
    }

    @Override
    public int getMaxStackSize() {
        return type.getMaxStackSize();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ItemStack other)) return false;
        return type == other.getType() && amount == other.getAmount();
    }

    @Override
    public int hashCode() {
        return type.hashCode() * 31 + amount;
    }

    @Override
    public String toString() {
        return "VeltisItemStack{" + type + " x " + amount + "}";
    }
}
