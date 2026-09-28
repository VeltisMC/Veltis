package org.veltismc.world.chunk;

import org.veltismc.world.api.BlockDelta;
import org.veltismc.world.api.ChunkDelta;
import org.veltismc.world.api.ChunkPos;
import org.veltismc.world.api.EntityDelta;
import org.veltismc.world.api.LightDelta;

/**
 * Applies immutable {@link ChunkDelta}s to chunks. All callers must be the
 * region owner thread (or a job holding the chunk's exclusive state); the
 * engine never applies deltas from arbitrary threads.
 */
public final class DeltaApplier {

    private DeltaApplier() {
    }

    /** Applies the delta; returns {@code false} if it targeted a different chunk or was rejected. */
    public static boolean apply(Chunk chunk, ChunkDelta delta) {
        ChunkPos pos = chunk.pos();
        if (delta instanceof BlockDelta bd) {
            if (!pos.equals(bd.chunk())) {
                return false;
            }
            return chunk.setBlock(bd.pos(), bd.state());
        }
        if (delta instanceof LightDelta ld) {
            if (!pos.equals(ld.chunk())) {
                return false;
            }
            if (ld.sky()) {
                chunk.setSkyLightUnsafe(ld.pos(), ld.level());
            } else {
                chunk.setBlockLightUnsafe(ld.pos(), ld.level());
            }
            return true;
        }
        if (delta instanceof EntityDelta) {
            // Entity lifecycles are applied by the simulation layer via the entity
            // registry; the delta is informational for bookkeeping.
            return true;
        }
        return false;
    }
}
