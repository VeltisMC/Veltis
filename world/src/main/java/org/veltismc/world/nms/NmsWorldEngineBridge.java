package org.veltismc.world.nms;

import net.minecraft.server.level.ServerLevel;
import org.veltismc.world.api.World;
import org.veltismc.world.api.WorldEngine;

import java.util.Objects;

/** Creates explicit NMS-to-engine world bindings without changing vanilla state. */
public final class NmsWorldEngineBridge {

    private final WorldEngine engine;

    public NmsWorldEngineBridge(WorldEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Creates a binding for an existing vanilla world.
     *
     * <p>The name must be stable for the lifetime of the binding. Chunk
     * loading, entity ownership, and vanilla callbacks are intentionally not
     * installed by this bootstrap-only operation.
     */
    public NmsWorldAdapter bind(String worldName, ServerLevel serverLevel) {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        return new NmsWorldAdapter(serverLevel, engine.createWorld(worldName));
    }
}
