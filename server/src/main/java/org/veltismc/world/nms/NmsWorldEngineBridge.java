package org.veltismc.world.nms;

import net.minecraft.server.level.ServerLevel;
import org.veltismc.world.api.WorldEngine;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maintains the identity-based mapping between Minecraft {@link ServerLevel}
 * instances and their engine-owned Veltis worlds.
 *
 * <p>One level binds to exactly one adapter (and therefore one Veltis world);
 * binding the same level again returns the existing adapter, and distinct
 * level instances are always distinct keys. The map itself is never exposed —
 * only immutable snapshots. Unbinding drops the mapping; the Veltis world
 * belongs to the engine and is closed by the engine's lifecycle, not here.
 */
public final class NmsWorldEngineBridge {

    private final WorldEngine engine;
    private final Map<ServerLevel, NmsWorldAdapter> bindings = new IdentityHashMap<>();

    public NmsWorldEngineBridge(WorldEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Creates a binding for an existing vanilla world.
     *
     * <p>Idempotent per level: if the level is already bound, its existing
     * adapter is returned regardless of {@code worldName}. The name must be
     * stable for the lifetime of the binding. Chunk loading, entity ownership,
     * and vanilla callbacks are intentionally not installed by this
     * bootstrap-only operation.
     */
    public synchronized NmsWorldAdapter bind(String worldName, ServerLevel serverLevel) {
        Objects.requireNonNull(serverLevel, "serverLevel");
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        NmsWorldAdapter existing = bindings.get(serverLevel);
        if (existing != null) {
            return existing;
        }
        NmsWorldAdapter adapter = new NmsWorldAdapter(serverLevel, engine.createWorld(worldName));
        bindings.put(serverLevel, adapter);
        return adapter;
    }

    /** Returns the adapter bound to this level, or {@code null}. */
    public synchronized NmsWorldAdapter adapter(ServerLevel serverLevel) {
        return serverLevel == null ? null : bindings.get(serverLevel);
    }

    /** Removes the binding for this level and returns it, or {@code null}. */
    public synchronized NmsWorldAdapter unbind(ServerLevel serverLevel) {
        return serverLevel == null ? null : bindings.remove(serverLevel);
    }

    /** Immutable snapshot of every bound adapter; the map is never exposed. */
    public synchronized List<NmsWorldAdapter> adapters() {
        return List.copyOf(bindings.values());
    }

    /** Removes every binding (Veltis shutdown). Worlds are not touched. */
    public synchronized void clear() {
        bindings.clear();
    }
}
