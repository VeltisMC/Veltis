package org.veltismc.world.chunk;

import org.veltismc.world.api.ChunkState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validated CAS state machine for a chunk's lifecycle.
 *
 * <p>Every transition is validated against the allowed graph and applied with a
 * single CAS, so a chunk in state X is exclusively owned by the job that moved
 * it there. Pre-READY states allow a self-CAS ({@code s -> s}) as an acquisition
 * marker for stages that share a state (e.g. SURFACE and NOISE both run in
 * GENERATING).
 */
public final class ChunkStateMachine {

    private static final EnumMap<ChunkState, Set<ChunkState>> ALLOWED = new EnumMap<>(ChunkState.class);

    static {
        allowed(ChunkState.UNLOADED, ChunkState.LOADING);
        allowed(ChunkState.LOADING, ChunkState.GENERATING, ChunkState.READY, ChunkState.UNLOADING);
        // GENERATING and BIOMES also allow the self-CAS acquisition marker.
        allowed(ChunkState.GENERATING, ChunkState.GENERATING, ChunkState.STRUCTURES, ChunkState.UNLOADING);
        allowed(ChunkState.STRUCTURES, ChunkState.BIOMES, ChunkState.UNLOADING);
        allowed(ChunkState.BIOMES, ChunkState.BIOMES, ChunkState.LIGHTING, ChunkState.UNLOADING);
        allowed(ChunkState.LIGHTING, ChunkState.READY, ChunkState.UNLOADING);
        allowed(ChunkState.READY, ChunkState.SIMULATING, ChunkState.DIRTY, ChunkState.SAVING, ChunkState.UNLOADING);
        allowed(ChunkState.SIMULATING, ChunkState.DIRTY, ChunkState.SAVING, ChunkState.UNLOADING);
        allowed(ChunkState.DIRTY, ChunkState.SAVING, ChunkState.UNLOADING);
        allowed(ChunkState.SAVING, ChunkState.READY, ChunkState.DIRTY, ChunkState.UNLOADING);
        allowed(ChunkState.UNLOADING, ChunkState.UNLOADED);
    }

    private static void allowed(ChunkState from, ChunkState... to) {
        EnumSet<ChunkState> targets = EnumSet.copyOf(java.util.Arrays.asList(to));
        ALLOWED.put(from, targets);
    }

    private final AtomicReference<ChunkState> state = new AtomicReference<>(ChunkState.UNLOADED);

    public ChunkState get() {
        return state.get();
    }

    /** Resets the machine for pooled reuse. */
    public void reset() {
        state.set(ChunkState.UNLOADED);
    }

    public boolean isLoaded() {
        ChunkState s = state.get();
        return s != ChunkState.UNLOADED && s != ChunkState.UNLOADING;
    }

    /**
     * Transitions from {@code from} to {@code to} if allowed and unclaimed.
     * Returns {@code false} if the current state differs or the move is invalid.
     */
    public boolean trySet(ChunkState from, ChunkState to) {
        Set<ChunkState> allowed = ALLOWED.get(from);
        if (allowed == null || !allowed.contains(to)) {
            return false;
        }
        return state.compareAndSet(from, to);
    }

    /** True if the chunk has reached {@code target} (or anything later in the lifecycle). */
    public boolean reached(ChunkState target) {
        return state.get().ordinal() >= target.ordinal();
    }
}
