package org.veltismc.veltis.registry;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.jspecify.annotations.NullMarked;

@NullMarked
class VeltisBiome implements Biome {

    private static int nextOrdinal;
    private final NamespacedKey key;
    private final int ordinal;
    private final String name;

    VeltisBiome(NamespacedKey key) {
        this.key = key;
        this.ordinal = nextOrdinal++;
        this.name = key.getKey();
    }

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
        return name;
    }

    @Override
    public int ordinal() {
        return ordinal;
    }
}
