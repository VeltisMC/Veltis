package org.veltismc.nativeengine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real shared library, not a stand-in.
 *
 * <p>The test is skipped when {@code veltis.native.test.library} names no file,
 * so a Java-only checkout (or a CI job with no compiler) still runs the suite.
 * When the file is present the library is loaded through the same
 * {@link NativeLoader} the server uses, and every assertion is about bytes that
 * came out of the C++ implementation.
 *
 * <p>Run it against a freshly built library with:
 *
 * <pre>
 * ./gradlew :veltis-native:buildNative :veltis-native:test -PveltisNativeTest=true
 * </pre>
 */
class NativeEndToEndTest {

    private static Path library() {
        var value = System.getProperty("veltis.native.test.library");
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                "veltis.native.test.library is not configured");
        var path = Path.of(value);
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "native library has not been built: " + path + " (run :veltis-native:buildNative)");
        return path;
    }

    private static NativeConfig config(Path library) {
        return new NativeConfig(true, true, true, false, library.toString());
    }

    @Test
    void realLibraryLoadsAndReportsItsAbiAndCapabilities() {
        var result = NativeLoader.tryLoad(config(library()));

        assertNotNull(result.library(), () -> "expected a real native load, got: " + result.status().summary());
        try (var loaded = result.library()) {
            var status = loaded.status();
            assertTrue(status.available());
            assertTrue(status.loaded());
            assertFalse(status.fallbackActive());
            assertEquals(NativeLoader.EXPECTED_ABI_VERSION, status.abiVersion());
            assertEquals("1.0.0", status.engineVersion());
            assertTrue(status.supports(NativeCapabilities.TRANSFORM_I32), status.summary());
            assertTrue(status.supports(NativeCapabilities.SELF_TEST), status.summary());
        }
    }

    @Test
    void nativeSelfTestAndLifecycleWorkThroughTheRealLibrary() {
        var result = NativeLoader.tryLoad(config(library()));
        Assumptions.assumeTrue(result.library() != null, () -> result.status().summary());

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());
            assertTrue(loaded.isInitialized());
            assertEquals(0, loaded.selfTest());
            // Initialization is idempotent, and a second self-test still passes.
            assertEquals(0, loaded.initialize());
            assertEquals(0, loaded.selfTest());
        }
    }

    @Test
    void nativeTransformMatchesTheJavaFallbackBitForBit() {
        var result = NativeLoader.tryLoad(config(library()));
        Assumptions.assumeTrue(result.library() != null, () -> result.status().summary());

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());

            int[][] inputs = {
                    {},
                    {0},
                    {0, 1, 2, -1, Integer.MAX_VALUE},
                    {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE},
                    {-7, 13, -123456789, 987654321},
            };
            int[] salts = {0, 7, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 42};

            for (var input : inputs) {
                for (var salt : salts) {
                    try (var arena = Arena.ofConfined()) {
                        var output = arena.allocate(ValueLayout.JAVA_INT, input.length);
                        var rc = loaded.transform(
                                arena.allocateFrom(ValueLayout.JAVA_INT, input), input.length, salt, output, input.length);

                        assertEquals(0, rc, () -> "native transform failed: " + loaded.lastError());
                        assertArrayEquals(
                                NativeEngine.javaTransform(input, salt),
                                readInts(output, input.length),
                                () -> "native/Java mismatch for length " + input.length + " salt " + salt);
                    }
                }
            }
        }
    }

    @Test
    void nativeTransformWritesExactlyTheDeclaredOutputRange() {
        var result = NativeLoader.tryLoad(config(library()));
        Assumptions.assumeTrue(result.library() != null, () -> result.status().summary());

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());

            var input = new int[] {-7, 0, 7, 1234};
            var sentinel = 0x5A5A5A5A;
            var intSize = (int) ValueLayout.JAVA_INT.byteSize();

            try (var arena = Arena.ofConfined()) {
                // Two guard elements around the real output extent.
                var allocation = arena.allocate(ValueLayout.JAVA_INT, input.length + 2L);
                for (var i = 0; i < input.length + 2; i++) {
                    allocation.setAtIndex(ValueLayout.JAVA_INT, i, sentinel);
                }
                var output = allocation.asSlice(intSize, (long) intSize * input.length);

                assertEquals(0, loaded.transform(
                        arena.allocateFrom(ValueLayout.JAVA_INT, input), input.length, 3, output, input.length));

                assertEquals(sentinel, allocation.getAtIndex(ValueLayout.JAVA_INT, 0));
                assertEquals(sentinel, allocation.getAtIndex(ValueLayout.JAVA_INT, input.length + 1));
                assertArrayEquals(NativeEngine.javaTransform(input, 3), readInts(output, input.length));
            }
        }
    }

    @Test
    void engineCanInitializeTransformShutDownAndReloadTheRealLibrary() {
        var config = config(library());
        var engine = new NativeEngine();

        try {
            var first = engine.initialize(config);
            assertTrue(first.available(), first.summary());
            assertTrue(engine.nativeAvailable());
            assertArrayEquals(
                    NativeEngine.javaTransform(new int[] {3, -3, 9}, 5),
                    engine.transform(new int[] {3, -3, 9}, 5));

            engine.shutdown();
            assertFalse(engine.nativeAvailable());

            var second = engine.initialize(config);
            assertTrue(second.available(), second.summary());
            assertArrayEquals(
                    NativeEngine.javaTransform(new int[] {11, 12, 13}, -8),
                    engine.transform(new int[] {11, 12, 13}, -8));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void repeatedInitializeAndShutdownCyclesReleaseAndReloadTheLibrary() {
        var config = config(library());
        var engine = new NativeEngine();

        try {
            for (var cycle = 0; cycle < 5; cycle++) {
                var status = engine.initialize(config);
                assertTrue(status.available(), "cycle " + cycle + ": " + status.summary());
                assertTrue(engine.nativeAvailable());
                assertTrue(engine.selfTest());

                var input = new int[] {cycle, -cycle, cycle * 31, Integer.MIN_VALUE};
                assertArrayEquals(NativeEngine.javaTransform(input, cycle),
                        engine.transform(input, cycle));

                engine.shutdown();
                assertFalse(engine.nativeAvailable(), "shutdown must release the library");
                assertTrue(engine.status().fallbackActive());
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void transformAfterShutdownUsesTheJavaFallback() {
        var engine = new NativeEngine();

        try {
            var status = engine.initialize(config(library()));
            assertTrue(status.available(), status.summary());
            assertTrue(engine.nativeAvailable());

            engine.shutdown();
            assertFalse(engine.nativeAvailable());

            var input = new int[] {5, -5, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
            assertArrayEquals(NativeEngine.javaTransform(input, 17), engine.transform(input, 17));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void largeBatchesMatchTheJavaFallbackThroughTheRealLibrary() {
        var result = NativeLoader.tryLoad(config(library()));
        Assumptions.assumeTrue(result.library() != null, () -> result.status().summary());

        var count = 1 << 20;
        var input = new int[count];
        for (var i = 0; i < count; i++) {
            input[i] = (i * 0x9E3779B1) ^ (i >>> 7);
        }
        var expected = NativeEngine.javaTransform(input, -987654321);

        try (var loaded = result.library()) {
            assertEquals(0, loaded.initialize());
            try (var arena = Arena.ofConfined()) {
                var in = arena.allocateFrom(ValueLayout.JAVA_INT, input);
                var out = arena.allocate(ValueLayout.JAVA_INT, count);
                assertEquals(0, loaded.transform(in, count, -987654321, out, count),
                        () -> "large native transform failed: " + loaded.lastError());
                assertArrayEquals(expected, readInts(out, count));
            }
        }

        var engine = new NativeEngine();
        try {
            assertTrue(engine.initialize(config(library())).available());
            assertArrayEquals(expected, engine.transform(input, -987654321));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void concurrentTransformsThroughTheEngineAreCorrect() throws Exception {
        var engine = new NativeEngine();
        var status = engine.initialize(config(library()));
        assertTrue(status.available(), status.summary());

        var threads = 8;
        var rounds = 50;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var failures = new AtomicInteger();

        try {
            for (var t = 0; t < threads; t++) {
                var seed = t;
                var worker = new Thread(() -> {
                    try {
                        var input = new int[4096];
                        for (var i = 0; i < input.length; i++) {
                            input[i] = i * 31 + seed * 7919;
                        }
                        var expected = NativeEngine.javaTransform(input, seed);
                        start.await();
                        for (var round = 0; round < rounds; round++) {
                            if (!Arrays.equals(expected, engine.transform(input, seed))) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Throwable failure) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "native-e2e-" + t);
                worker.setDaemon(true);
                worker.start();
            }

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent native transforms did not finish");
            assertEquals(0, failures.get(), "concurrent native transforms must match javaTransform");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void shutdownWhileCallsAreActiveNeverProducesWrongResults() throws Exception {
        var engine = new NativeEngine();
        var status = engine.initialize(config(library()));
        assertTrue(status.available(), status.summary());

        var threads = 6;
        var roundsAfterShutdown = 200;
        var ready = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        var stop = new AtomicBoolean();
        var failures = new AtomicInteger();

        try {
            for (var t = 0; t < threads; t++) {
                var seed = t;
                var worker = new Thread(() -> {
                    try {
                        var input = new int[2048];
                        for (var i = 0; i < input.length; i++) {
                            input[i] = i * 17 + seed;
                        }
                        var expected = NativeEngine.javaTransform(input, seed);

                        // Signal readiness only after one successful call, so the
                        // main thread knows at least one native call is in flight
                        // (or has completed) before it starts shutdown.
                        if (!Arrays.equals(expected, engine.transform(input, seed))) {
                            failures.incrementAndGet();
                        }
                        ready.countDown();
                        while (!stop.get()) {
                            if (!Arrays.equals(expected, engine.transform(input, seed))) {
                                failures.incrementAndGet();
                            }
                        }
                        for (var round = 0; round < roundsAfterShutdown; round++) {
                            if (!Arrays.equals(expected, engine.transform(input, seed))) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Throwable failure) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "native-shutdown-" + t);
                worker.setDaemon(true);
                worker.start();
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not start");
            engine.shutdown();
            assertFalse(engine.nativeAvailable());
            // Let the workers continue past shutdown: those calls must use the
            // Java fallback and still return the same values.
            stop.set(true);

            assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish after shutdown");
            assertEquals(0, failures.get(), "shutdown must never corrupt an in-flight or later result");
        } finally {
            stop.set(true);
            engine.shutdown();
        }
    }

    private static int[] readInts(MemorySegment segment, int length) {
        var values = new int[length];
        for (var i = 0; i < length; i++) {
            values[i] = segment.getAtIndex(ValueLayout.JAVA_INT, i);
        }
        return values;
    }
}
