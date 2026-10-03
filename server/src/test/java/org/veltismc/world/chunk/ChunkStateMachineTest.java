package org.veltismc.world.chunk;

import org.junit.jupiter.api.Test;
import org.veltismc.world.api.ChunkState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStateMachineTest {

    @Test
    void validTransitions() {
        ChunkStateMachine m = new ChunkStateMachine();
        assertTrue(m.trySet(ChunkState.UNLOADED, ChunkState.LOADING));
        assertTrue(m.trySet(ChunkState.LOADING, ChunkState.GENERATING));
        assertTrue(m.trySet(ChunkState.GENERATING, ChunkState.STRUCTURES));
        assertTrue(m.trySet(ChunkState.STRUCTURES, ChunkState.BIOMES));
        assertTrue(m.trySet(ChunkState.BIOMES, ChunkState.LIGHTING));
        assertTrue(m.trySet(ChunkState.LIGHTING, ChunkState.READY));
        assertTrue(m.trySet(ChunkState.READY, ChunkState.SIMULATING));
        assertTrue(m.trySet(ChunkState.SIMULATING, ChunkState.DIRTY));
        assertTrue(m.trySet(ChunkState.DIRTY, ChunkState.SAVING));
        assertTrue(m.trySet(ChunkState.SAVING, ChunkState.READY));
        assertTrue(m.trySet(ChunkState.READY, ChunkState.SAVING));
        assertTrue(m.trySet(ChunkState.SAVING, ChunkState.UNLOADING));
        assertTrue(m.trySet(ChunkState.UNLOADING, ChunkState.UNLOADED));
        assertEquals(ChunkState.UNLOADED, m.get());
    }

    @Test
    void invalidTransitionsRejected() {
        ChunkStateMachine m = new ChunkStateMachine();
        assertFalse(m.trySet(ChunkState.UNLOADED, ChunkState.READY));
        assertFalse(m.trySet(ChunkState.UNLOADED, ChunkState.UNLOADING));
        assertFalse(m.trySet(ChunkState.LOADING, ChunkState.LIGHTING));
        assertFalse(m.trySet(ChunkState.LOADING, ChunkState.BIOMES));
        assertFalse(m.trySet(ChunkState.GENERATING, ChunkState.READY));
        assertFalse(m.trySet(ChunkState.STRUCTURES, ChunkState.LIGHTING));
        assertFalse(m.trySet(ChunkState.STRUCTURES, ChunkState.STRUCTURES));
        assertFalse(m.trySet(ChunkState.LIGHTING, ChunkState.SIMULATING));
        assertFalse(m.trySet(ChunkState.READY, ChunkState.READY));
        assertFalse(m.trySet(ChunkState.READY, ChunkState.LOADING));
        assertFalse(m.trySet(ChunkState.UNLOADED, ChunkState.STRUCTURES));
        assertEquals(ChunkState.UNLOADED, m.get());
    }

    @Test
    void selfAcquisitionMarkers() {
        ChunkStateMachine m = new ChunkStateMachine();
        assertTrue(m.trySet(ChunkState.UNLOADED, ChunkState.LOADING));
        assertTrue(m.trySet(ChunkState.LOADING, ChunkState.GENERATING));
        assertTrue(m.trySet(ChunkState.GENERATING, ChunkState.GENERATING), "SURFACE reuses GENERATING");
        assertTrue(m.trySet(ChunkState.GENERATING, ChunkState.STRUCTURES));
        assertTrue(m.trySet(ChunkState.STRUCTURES, ChunkState.BIOMES));
        assertTrue(m.trySet(ChunkState.BIOMES, ChunkState.BIOMES), "DECORATION reuses BIOMES");
        assertTrue(m.trySet(ChunkState.BIOMES, ChunkState.LIGHTING));
    }

    @Test
    void secondWriterFailsCas() {
        ChunkStateMachine m = new ChunkStateMachine();
        m.trySet(ChunkState.UNLOADED, ChunkState.LOADING);
        assertTrue(m.trySet(ChunkState.LOADING, ChunkState.GENERATING));
        assertFalse(m.trySet(ChunkState.LOADING, ChunkState.GENERATING), "already claimed by another job");
        assertFalse(m.trySet(ChunkState.LOADING, ChunkState.READY));
    }

    @Test
    void reachedUsesOrdinal() {
        ChunkStateMachine m = new ChunkStateMachine();
        assertFalse(m.reached(ChunkState.READY));
        m.trySet(ChunkState.UNLOADED, ChunkState.LOADING);
        m.trySet(ChunkState.LOADING, ChunkState.GENERATING);
        assertFalse(m.reached(ChunkState.READY));
        m.trySet(ChunkState.GENERATING, ChunkState.STRUCTURES);
        m.trySet(ChunkState.STRUCTURES, ChunkState.BIOMES);
        m.trySet(ChunkState.BIOMES, ChunkState.LIGHTING);
        assertFalse(m.reached(ChunkState.READY));
        m.trySet(ChunkState.LIGHTING, ChunkState.READY);
        assertTrue(m.reached(ChunkState.READY));
        assertTrue(m.reached(ChunkState.UNLOADED));
        assertTrue(m.reached(ChunkState.LOADING));
        m.trySet(ChunkState.READY, ChunkState.SAVING);
        assertTrue(m.reached(ChunkState.READY));
    }

    @Test
    void resetRestoresUnloaded() {
        ChunkStateMachine m = new ChunkStateMachine();
        m.trySet(ChunkState.UNLOADED, ChunkState.LOADING);
        m.reset();
        assertEquals(ChunkState.UNLOADED, m.get());
        assertTrue(m.trySet(ChunkState.UNLOADED, ChunkState.LOADING));
    }

    @Test
    void isLoaded() {
        ChunkStateMachine m = new ChunkStateMachine();
        assertFalse(m.isLoaded());
        m.trySet(ChunkState.UNLOADED, ChunkState.LOADING);
        assertTrue(m.isLoaded());
        m.trySet(ChunkState.LOADING, ChunkState.GENERATING);
        assertTrue(m.isLoaded());
        m.trySet(ChunkState.GENERATING, ChunkState.UNLOADING);
        assertFalse(m.isLoaded());
        m.trySet(ChunkState.UNLOADING, ChunkState.UNLOADED);
        assertFalse(m.isLoaded());
    }
}
