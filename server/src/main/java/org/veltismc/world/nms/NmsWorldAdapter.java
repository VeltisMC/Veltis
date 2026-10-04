package org.veltismc.world.nms;

import net.minecraft.server.level.ServerLevel;
import org.veltismc.world.api.ChunkHandle;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.World;

import java.util.Objects;

/**
 * Version-pinned bridge between one vanilla world and one engine world.
 *
 * <p>This class deliberately does not invoke vanilla ticking or mutate NMS
 * state. Those operations require ownership, snapshot, and ticket contracts
 * that are not available in the generic engine yet.
 */
public final class NmsWorldAdapter {

    private final ServerLevel serverLevel;
    private final World engineWorld;

    public NmsWorldAdapter(ServerLevel serverLevel, World engineWorld) {
        this.serverLevel = Objects.requireNonNull(serverLevel, "serverLevel");
        this.engineWorld = Objects.requireNonNull(engineWorld, "engineWorld");
    }

    public ServerLevel serverLevel() {
        return serverLevel;
    }

    public World engineWorld() {
        return engineWorld;
    }

    /** Returns the engine-owned asynchronous handle for an NMS chunk coordinate. */
    public ChunkHandle chunk(int chunkX, int chunkZ) {
        return engineWorld.chunk(new ChunkPos(chunkX, chunkZ));
    }
}
