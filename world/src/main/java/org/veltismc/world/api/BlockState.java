package org.veltismc.world.api;

/**
 * An immutable block state, represented by a numeric id, an emitted light level
 * (0-15) and an opacity flag. The engine is deliberately agnostic of any particular
 * block registry; ids are assigned by the host.
 */
public record BlockState(int id, int lightLevel, boolean opaque) {

    /** The canonical "air" state: id 0, no light, not opaque. */
    public static final BlockState AIR = new BlockState(0, 0, false);

    public static BlockState of(int id) {
        return new BlockState(id, 0, true);
    }

    public static BlockState of(int id, int lightLevel, boolean opaque) {
        return new BlockState(id, lightLevel, opaque);
    }

    public BlockState {
        if (id < 0) throw new IllegalArgumentException("block id must be >= 0, got " + id);
        if (lightLevel < 0 || lightLevel > 15) throw new IllegalArgumentException("light must be 0-15, got " + lightLevel);
    }

    @Override
    public String toString() {
        return "BlockState[" + id + ", light=" + lightLevel + ", opaque=" + opaque + "]";
    }
}
