package ca.spottedleaf.moonrise.compat.lithium;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class LithiumHooks {

    private LithiumHooks() {}

    public static void onChunkAccessible(final ServerLevel world, final LevelChunk chunk) {
    }

    public static void onChunkInaccessible(final ServerLevel world, final ChunkPos pos) {
    }
}
