package org.veltismc.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.veltismc.nativeengine.NativeEngine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup-path smoke test for the optional native engine.
 *
 * <p>{@link VeltisBootstrap#boot(String[])} is the only place the server wires
 * native support in, and it must never fail startup. The test is written to run
 * in both configurations CI needs:
 *
 * <ul>
 *   <li>with no {@code veltis.native.path}, which is what an ordinary
 *       {@code :server:test} and a Java-only installation do: the engine must
 *       report the Java fallback and keep producing correct results;</li>
 *   <li>with {@code -Dveltis.native.path} pointing at a built library, which is
 *       the native-enabled startup: the engine must become available, then
 *       {@link VeltisBootstrap#onServerStopping()} must release it.</li>
 * </ul>
 *
 * <p>{@link VeltisBootstrap} deliberately initializes once per JVM, so a second
 * {@code boot} in the same process must be a no-op rather than an error. The
 * test asserts that too.
 */
class NativeBootstrapTest {

    @TempDir
    Path home;

    @Test
    void bootAndStopWorkWithAndWithoutANativeLibrary() {
        var configured = System.getProperty("veltis.native.path", "").isBlank() ? null
                : System.getProperty("veltis.native.path");

        VeltisBootstrap.boot(new String[] {"--home", home.toString()});

        var engine = VeltisBootstrap.nativeEngine();
        var status = engine.status();
        if (configured == null) {
            assertFalse(status.available(), status.summary());
            assertFalse(engine.nativeAvailable(), "no library was configured");
        } else {
            assertTrue(status.available(), status.summary());
            assertTrue(engine.nativeAvailable(), status.summary());
        }

        var input = new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42));

        VeltisBootstrap.onServerStopping();
        assertFalse(engine.nativeAvailable(), "server stop must release the native library");
        assertTrue(engine.status().fallbackActive());

        // Boot is a one-time operation. A second call must not throw and must
        // not resurrect a library the server already released.
        VeltisBootstrap.boot(new String[] {"--home", home.toString()});
        assertFalse(engine.nativeAvailable());

        assertArrayEquals(NativeEngine.javaTransform(input, 42), engine.transform(input, 42));
    }
}
