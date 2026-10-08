package org.veltismc.world.nms;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;

/**
 * Test-only support for the NMS integration tests: a one-time Minecraft
 * registry bootstrap plus constructor-free {@link ServerLevel} instances.
 *
 * <p>The binding tests only exercise identity — level to adapter to world —
 * and never tick a level or read its fields, so an allocated instance is
 * enough; building a real {@code ServerLevel} (storage, chunk source, entity
 * manager) would drag an entire world into a unit test.
 */
final class MinecraftTestSupport {

    private static boolean bootstrapped;

    private MinecraftTestSupport() {}

    /**
     * Initializes Minecraft's shared constants and registries once per JVM.
     * Every test touching Minecraft classes must call this first: {@code Level}
     * static initialization registers particle codecs through the built-in
     * registries and fails with "Not bootstrapped" otherwise — and a failed
     * class initialization is permanent for the whole test JVM.
     */
    static synchronized void bootstrapMinecraft() {
        if (bootstrapped) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }

    /**
     * Allocates a distinct {@link ServerLevel} without running a constructor,
     * so tests get separate identities for binding without a world behind them.
     * The dimension field (normally set by the constructor) is filled in so the
     * integration can derive a world name from it.
     */
    static ServerLevel newServerLevel() {
        return newServerLevel(Level.OVERWORLD);
    }

    /** Same as {@link #newServerLevel()} with an explicit dimension key. */
    static ServerLevel newServerLevel(ResourceKey<Level> dimension) {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
            Field dimensionField = Level.class.getDeclaredField("dimension");
            dimensionField.setAccessible(true);
            dimensionField.set(level, dimension);
            return level;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate a ServerLevel for the NMS tests", e);
        }
    }
}
