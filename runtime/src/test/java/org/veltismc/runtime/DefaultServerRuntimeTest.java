package org.veltismc.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Start/shutdown smoke test for the wired runtime: services, world engine,
 * event bus, scheduler and lifecycle all come up and go down cleanly.
 */
class DefaultServerRuntimeTest {

    @Test
    void startsAndShutsDownCleanly() {
        var runtime = new DefaultServerRuntime();
        assertEquals(ServerRuntime.RuntimeState.CREATED, runtime.state());
        assertFalse(runtime.isRunning());

        runtime.start().join();
        assertTrue(runtime.isRunning());
        assertEquals(ServerRuntime.RuntimeState.RUNNING, runtime.state());
        assertNotNull(runtime.worldEngine());
        assertNotNull(runtime.eventBus());
        assertNotNull(runtime.scheduler());
        assertTrue(runtime.services()
            .get(org.veltismc.runtime.service.WorldEngineService.class).isPresent());

        runtime.shutdown().join();
        assertFalse(runtime.isRunning());
        assertEquals(ServerRuntime.RuntimeState.STOPPED, runtime.state());
    }

    @Test
    void cannotStartTwice() {
        var runtime = new DefaultServerRuntime();
        runtime.start().join();

        var failure = assertThrows(CompletionException.class, () -> runtime.start().join());
        assertTrue(failure.getCause() instanceof IllegalStateException);

        runtime.shutdown().join();
        assertEquals(ServerRuntime.RuntimeState.STOPPED, runtime.state());
    }

    @Test
    void shutdownIsIdempotent() {
        var runtime = new DefaultServerRuntime();
        runtime.start().join();

        runtime.shutdown().join();
        runtime.shutdown().join();

        assertEquals(ServerRuntime.RuntimeState.STOPPED, runtime.state());
    }
}
