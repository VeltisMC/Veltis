package org.veltismc.nativeengine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeEngineTest {

    @TempDir
    Path temp;

    @Test
    void disabledConfigurationUsesTheJavaFallback() {
        var engine = new NativeEngine();
        var status = engine.initialize(new NativeConfig(false, true, true, false, ""));

        assertFalse(status.available());
        assertTrue(status.fallbackActive());
        assertFalse(engine.nativeAvailable());
        assertArrayEquals(NativeEngine.javaTransform(new int[] {1, 2, 3}, 11),
                engine.transform(new int[] {1, 2, 3}, 11));
        engine.shutdown();
    }

    @Test
    void missingLibraryUsesTheJavaFallback() {
        var engine = new NativeEngine();
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        var status = engine.initialize(new NativeConfig(true, true, true, false, missing.toString()));

        assertFalse(status.available());
        assertFalse(status.loaded());
        assertTrue(status.fallbackActive());
        assertArrayEquals(NativeEngine.javaTransform(new int[] {7, -7, 0}, 3),
                engine.transform(new int[] {7, -7, 0}, 3));
        engine.shutdown();
    }

    @Test
    void fallbackDisabledMakesAnUnavailableOperationFailExplicitly() {
        var engine = new NativeEngine();
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        engine.initialize(new NativeConfig(true, true, false, false, missing.toString()));

        assertThrows(IllegalStateException.class,
                () -> engine.transform(new int[] {1, 2, 3}, 0));
        engine.shutdown();
    }

    @Test
    void repeatedInitializationAndShutdownAreSafe() {
        var engine = new NativeEngine();
        var config = new NativeConfig(false, true, true, false, "");

        var first = engine.initialize(config);
        var second = engine.initialize(config);
        assertSame(first, second);

        engine.shutdown();
        engine.shutdown();
        assertFalse(engine.nativeAvailable());

        var third = engine.initialize(config);
        assertFalse(third.available());
        engine.shutdown();
    }

    @Test
    void javaTransformMatchesTheNativeContract() {
        var input = new int[] {0, 1, 2, -1, 2147483647};
        var expected = new int[] {7, 39, 71, -21, 2147483628};

        assertArrayEquals(expected, NativeEngine.javaTransform(input, 7));
        assertArrayEquals(new int[0], NativeEngine.javaTransform(new int[0], 7));
    }

    @Test
    void statsOnlyCountWhenEnabled() {
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        var withoutStats = new NativeEngine();
        withoutStats.initialize(new NativeConfig(true, true, true, false, missing.toString()));
        withoutStats.transform(new int[] {1, 2}, 0);
        assertEquals(new NativeEngine.Stats(0, 0), withoutStats.stats());
        withoutStats.shutdown();

        var withStats = new NativeEngine();
        withStats.initialize(new NativeConfig(true, true, true, true, missing.toString()));
        withStats.transform(new int[] {1, 2}, 0);
        withStats.transform(new int[] {3, 4}, 0);
        assertEquals(new NativeEngine.Stats(0, 2), withStats.stats());
        withStats.shutdown();
    }

    @Test
    void selfTestRunsTheJavaCheckWithoutANativeLibrary() {
        var engine = new NativeEngine();
        engine.initialize(new NativeConfig(false, true, true, false, ""));
        assertTrue(engine.selfTest());
        engine.shutdown();
    }

    @Test
    void transformDoesNotShareTheCallersArray() {
        var input = new int[] {1, 2, 3};
        var output = NativeEngine.javaTransform(input, 5);
        assertFalse(Arrays.equals(input, output));
        assertArrayEquals(new int[] {1, 2, 3}, input);
    }

    @Test
    void manualLoadIsIdempotentAndFallsBackWhenTheLibraryIsAbsent() {
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        var engine = new NativeEngine();

        var disabled = engine.initialize(new NativeConfig(true, false, true, false, missing.toString()));
        assertFalse(disabled.available());
        assertFalse(engine.nativeAvailable());

        var first = engine.load();
        var second = engine.load();
        assertFalse(first.available());
        assertFalse(second.available(), "a failed manual load may be retried, and must stay a reported fallback");
        assertFalse(engine.nativeAvailable());
        assertArrayEquals(NativeEngine.javaTransform(new int[] {4, 5, 6}, 2),
                engine.transform(new int[] {4, 5, 6}, 2));
        engine.shutdown();
    }

    @Test
    void nativeInitializationFailureIsReportedAndFallsBack() {
        var fixture = fixturePath("veltis.native.test.fakeInit");
        var engine = new NativeEngine();

        var status = engine.initialize(new NativeConfig(true, true, true, false, fixture.toString()));
        assertFalse(status.available(), status.summary());
        assertTrue(status.loaded(), "the library was found and opened");
        assertFalse(engine.nativeAvailable());
        assertTrue(status.reason().contains("native initialization failed"), status.reason());
        assertArrayEquals(NativeEngine.javaTransform(new int[] {1, -2, 3}, 9),
                engine.transform(new int[] {1, -2, 3}, 9));
        engine.shutdown();
    }

    @Test
    void nativeInitializationFailureWithFallbackDisabledStillProducesAUsableEngine() {
        var fixture = fixturePath("veltis.native.test.fakeInit");
        var engine = new NativeEngine();

        var status = engine.initialize(new NativeConfig(true, true, false, false, fixture.toString()));
        assertFalse(status.available());
        assertFalse(engine.nativeAvailable());
        assertThrows(IllegalStateException.class,
                () -> engine.transform(new int[] {1, 2, 3}, 0));
        engine.shutdown();
    }

    @Test
    void nativeTransformFailureFallsBackToJava() {
        var fixture = fixturePath("veltis.native.test.fakeTransform");
        var engine = new NativeEngine();

        var status = engine.initialize(new NativeConfig(true, true, true, false, fixture.toString()));
        assertTrue(status.available(), status.summary());
        assertTrue(status.supports(NativeCapabilities.TRANSFORM_I32));
        assertTrue(engine.nativeAvailable());

        var input = new int[] {10, -10, 0, Integer.MIN_VALUE, Integer.MAX_VALUE};
        assertArrayEquals(NativeEngine.javaTransform(input, -3), engine.transform(input, -3));
        engine.shutdown();
    }

    @Test
    void nativeTransformFailureWithFallbackDisabledThrows() {
        var fixture = fixturePath("veltis.native.test.fakeTransform");
        var engine = new NativeEngine();

        var status = engine.initialize(new NativeConfig(true, true, false, false, fixture.toString()));
        assertTrue(status.available(), status.summary());
        assertThrows(IllegalStateException.class, () -> engine.transform(new int[] {1, 2, 3}, 0));
        engine.shutdown();
    }

    /**
     * A library that fails every transform must not write one warning per call.
     *
     * <p>The clock is frozen and the interval is unreachable, so the engine's
     * production schedule is not what is under test here: what is tested is that
     * the transform path routes its warnings through the throttle at all, and
     * that the caller still receives Java results while they are being held
     * back. {@code FailureLogThrottleTest} covers the schedule itself.
     */
    @Test
    void repeatedNativeTransformFailuresDoNotWriteAWarningPerCall() {
        var fixture = fixturePath("veltis.native.test.fakeTransform");
        var engine = new NativeEngine(2, Long.MAX_VALUE, () -> 0L);

        var status = engine.initialize(new NativeConfig(true, true, true, false, fixture.toString()));
        assertTrue(status.available(), status.summary());

        var input = new int[] {10, -10, 0, Integer.MIN_VALUE, Integer.MAX_VALUE};
        var expected = NativeEngine.javaTransform(input, -3);
        for (var i = 0; i < 25; i++) {
            assertArrayEquals(expected, engine.transform(input, -3));
        }

        assertEquals(23L, engine.suppressedTransformWarnings(),
                "the first two failures are reported and the other twenty-three wait for the next message");
        engine.shutdown();
        assertEquals(0L, engine.suppressedTransformWarnings(),
                "a new lifecycle generation does not inherit the previous engine's backlog");
    }

    @Test
    void concurrentInitializationLoadsAtMostOneLibrary() throws Exception {
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        var config = new NativeConfig(true, true, true, false, missing.toString());
        var engine = new NativeEngine();

        var threads = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var results = new NativeStatus[threads];
        var errors = new Throwable[threads];

        for (var i = 0; i < threads; i++) {
            var index = i;
            var worker = new Thread(() -> {
                try {
                    start.await();
                    results[index] = engine.initialize(config);
                } catch (Throwable t) {
                    errors[index] = t;
                } finally {
                    done.countDown();
                }
            }, "native-init-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent initialization did not finish");
        for (var error : errors) {
            assertEquals(null, error);
        }
        for (var i = 1; i < threads; i++) {
            assertSame(results[0], results[i], "every caller must observe the same load outcome");
        }
        assertFalse(engine.nativeAvailable());
        engine.shutdown();
    }

    @Test
    void concurrentTransformsOnTheJavaFallbackAreIndependent() throws Exception {
        var engine = new NativeEngine();
        engine.initialize(new NativeConfig(false, true, true, false, ""));

        var threads = 8;
        var rounds = 100;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var mismatches = new AtomicInteger();

        for (var t = 0; t < threads; t++) {
            var seed = t;
            var worker = new Thread(() -> {
                try {
                    var input = new int[257];
                    for (var i = 0; i < input.length; i++) {
                        input[i] = i * 31 + seed * 7919;
                    }
                    var expected = NativeEngine.javaTransform(input, seed);
                    start.await();
                    for (var round = 0; round < rounds; round++) {
                        if (!Arrays.equals(expected, engine.transform(input, seed))) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (Throwable failure) {
                    mismatches.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "native-fallback-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent fallback transforms did not finish");
        assertEquals(0, mismatches.get(), "every concurrent fallback result must match javaTransform");
        engine.shutdown();
    }

    /**
     * Concurrent lifecycle calls must publish one consistent state, not a
     * half-initialized one.
     *
     * <p>Every thread that calls {@code initialize} receives the *same*
     * {@link NativeStatus} instance: the write lock serializes the calls, so one
     * thread performs the load and the rest observe the published result. A race
     * here would mean two libraries mapped into one engine, each with its own
     * arena. The lock-free accessors are also hammered throughout, because
     * {@code status()} and {@code nativeAvailable()} take no lock and are the only
     * way an outside observer — a metrics endpoint, a health check — can read the
     * engine; a null status or a wrong transform result under contention is a
     * publication bug.
     */
    @Test
    void concurrentLifecycleCallsPublishOneConsistentState() throws Exception {
        var engine = new NativeEngine();
        var missing = temp.resolve("missing").resolve(NativeLoader.libraryFileName());
        var config = new NativeConfig(true, true, true, false, missing.toString());

        var threads = 8;
        var rounds = 60;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var errors = new Throwable[threads];
        var publishedStatuses = new ArrayList<Object>();

        for (var t = 0; t < threads; t++) {
            final int index = t;
            var worker = new Thread(() -> {
                try {
                    start.await();
                    for (var round = 0; round < rounds; round++) {
                        if (index % 2 == 0) {
                            var status = engine.initialize(config);
                            synchronized (publishedStatuses) {
                                publishedStatuses.add(status);
                            }
                        } else {
                            // Lock-free observers. With no library on disk the
                            // only states these can witness are "not yet
                            // initialized" and "initialized, unavailable" —
                            // paired reads against a *live* library are covered
                            // by NativeEndToEndTest's shutdown-during-calls cases,
                            // which is where the mixture would be observable.
                            var seen = engine.status();
                            if (seen == null) {
                                throw new IllegalStateException("status() returned null");
                            }
                            if (engine.nativeAvailable() && !seen.available()) {
                                throw new IllegalStateException(
                                    "nativeAvailable() and status() disagree: " + seen.summary());
                            }
                            var result = engine.transform(new int[] {1, 2, 3}, 5);
                            if (!Arrays.equals(NativeEngine.javaTransform(new int[] {1, 2, 3}, 5),
                                    result)) {
                                throw new IllegalStateException("transform returned a wrong value");
                            }
                        }
                    }
                } catch (Throwable failure) {
                    errors[index] = failure;
                } finally {
                    done.countDown();
                }
            }, "lifecycle-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent lifecycle calls did not finish");
        for (var error : errors) {
            assertEquals(null, error, "a concurrent lifecycle call failed");
        }

        synchronized (publishedStatuses) {
            assertFalse(publishedStatuses.isEmpty(), "the initializing threads ran");
            for (var status : publishedStatuses) {
                assertSame(publishedStatuses.get(0), status,
                        "every initialize() call must observe one published status");
            }
        }
        assertFalse(engine.nativeAvailable(),
                "a library that does not exist is never reported as available");
        engine.shutdown();
    }

    @Test
    void transformOfAnEmptyArrayNeedsNoNativeEngine() {
        var engine = new NativeEngine();
        engine.initialize(new NativeConfig(true, true, false, false,
                temp.resolve("missing").resolve(NativeLoader.libraryFileName()).toString()));

        assertArrayEquals(new int[0], engine.transform(new int[0], 123));
        engine.shutdown();
    }

    private static Path fixturePath(String property) {
        var value = System.getProperty(property);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                property + " is not configured");
        var path = Path.of(value);
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "native test fixture has not been built: " + path + " (run :veltis-native:buildNative)");
        return path;
    }
}
