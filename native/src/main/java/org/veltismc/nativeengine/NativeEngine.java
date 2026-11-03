package org.veltismc.nativeengine;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lifecycle and operation facade for VeltisMC's optional native engine.
 *
 * <p>The engine is usable without a native library: every operation has a Java
 * implementation with identical output. Native availability is a configuration
 * decision and a runtime discovery result, never a precondition for starting the
 * server. The shared instance is what the server bootstrap uses; tests and
 * diagnostics can create independent instances.
 */
public final class NativeEngine {

    private static final NativeEngine SHARED = new NativeEngine();
    private static final System.Logger LOG = System.getLogger(NativeEngine.class.getName());

    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final LongAdder nativeInvocations = new LongAdder();
    private final LongAdder fallbackInvocations = new LongAdder();

    private volatile NativeConfig config = NativeConfig.defaults();
    private volatile NativeLoader.LoadedLibrary library;
    private volatile NativeStatus status = NativeStatus.disabled("not initialized");
    private boolean initialized;

    public NativeEngine() {
    }

    /** The process-wide instance the server bootstrap initializes and shuts down. */
    public static NativeEngine shared() {
        return SHARED;
    }

    /**
     * Initializes the engine once. Repeated calls while initialized return the
     * current status and never load a second library.
     */
    public NativeStatus initialize(NativeConfig requested) {
        Objects.requireNonNull(requested, "requested");
        var write = lifecycle.writeLock();
        write.lock();
        try {
            if (initialized) {
                return status;
            }
            config = requested;
            initialized = true;
            if (!requested.enabled()) {
                status = NativeStatus.disabled("disabled by " + NativeConfig.ENABLED_PROPERTY);
                return status;
            }
            if (!requested.autoload()) {
                status = NativeStatus.disabled("autoload disabled; call NativeEngine.load()");
                return status;
            }
            return loadLocked();
        } finally {
            write.unlock();
        }
    }

    /**
     * Attempts a load without requiring autoload. This is the manual path for an
     * administrator who enables native support after startup, and it is
     * idempotent while a library is active.
     */
    public NativeStatus load() {
        var write = lifecycle.writeLock();
        write.lock();
        try {
            initialized = true;
            if (library != null) {
                return status;
            }
            if (!config.enabled()) {
                status = NativeStatus.disabled("disabled by " + NativeConfig.ENABLED_PROPERTY);
                return status;
            }
            return loadLocked();
        } finally {
            write.unlock();
        }
    }

    private NativeStatus loadLocked() {
        var result = NativeLoader.tryLoad(config);
        if (result.library() == null) {
            status = result.status();
            return status;
        }

        var loaded = result.library();
        int rc;
        try {
            rc = loaded.initialize();
        } catch (Throwable t) {
            loaded.close();
            status = NativeStatus.unavailable(true, loaded.status().abiVersion(),
                    loaded.status().engineVersion(),
                    "native initialization failed: " + describe(t));
            return status;
        }

        if (rc != 0) {
            var reason = "native initialization failed: " + loaded.lastError()
                    + " (status " + rc + ")";
            loaded.close();
            status = NativeStatus.unavailable(true, loaded.status().abiVersion(),
                    loaded.status().engineVersion(), reason);
            return status;
        }

        library = loaded;
        status = NativeStatus.available(
                loaded.status().abiVersion(),
                loaded.status().engineVersion(),
                NativeCapabilities.fromMask(loaded.capabilitiesMask()));
        return status;
    }

    /** Shuts down and unloads the native library. Safe to call repeatedly. */
    public void shutdown() {
        var write = lifecycle.writeLock();
        write.lock();
        try {
            var loaded = library;
            library = null;
            if (loaded != null) {
                try {
                    loaded.close();
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING, "Native engine shutdown failed", t);
                }
            }
            initialized = false;
            status = NativeStatus.disabled("native engine stopped");
        } finally {
            write.unlock();
        }
    }

    public NativeStatus status() {
        return status;
    }

    public boolean nativeAvailable() {
        return library != null;
    }

    public java.util.Set<NativeCapabilities> capabilities() {
        return status.capabilities();
    }

    /** Runs the library's self-test when available, otherwise the Java check. */
    public boolean selfTest() {
        var read = lifecycle.readLock();
        read.lock();
        try {
            var loaded = library;
            if (loaded != null && status.supports(NativeCapabilities.SELF_TEST)) {
                try {
                    return loaded.selfTest() == 0;
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING, "Native self-test failed", t);
                    return false;
                }
            }
            return javaSelfTest();
        } finally {
            read.unlock();
        }
    }

    /**
     * Bulk deterministic transform.
     *
     * <p>Calls the native implementation when a compatible library is active and
     * the capability is advertised; otherwise returns {@link #javaTransform}.
     * A native call that reports an error is logged and, when fallback is
     * enabled, retried in Java. With fallback disabled an unavailable operation
     * throws instead of silently changing its result source.
     */
    public int[] transform(int[] input, int salt) {
        Objects.requireNonNull(input, "input");
        if (input.length == 0) {
            return new int[0];
        }

        var read = lifecycle.readLock();
        read.lock();
        try {
            var loaded = library;
            if (loaded != null && status.supports(NativeCapabilities.TRANSFORM_I32)) {
                try {
                    var result = nativeTransform(loaded, input, salt);
                    if (config.stats()) {
                        nativeInvocations.increment();
                    }
                    return result;
                } catch (Throwable t) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Native transform failed; " +
                                    (config.fallback() ? "using Java fallback" : "fallback disabled"),
                            t);
                    if (!config.fallback()) {
                        throw new IllegalStateException("Native transform failed and fallback is disabled", t);
                    }
                }
            } else if (!config.fallback()) {
                throw new IllegalStateException(
                        "Native transform is unavailable and fallback is disabled: " + status.summary());
            }

            if (config.stats()) {
                fallbackInvocations.increment();
            }
            return javaTransform(input, salt);
        } finally {
            read.unlock();
        }
    }

    /** Invocation counters; only incremented when {@code veltis.native.stats=true}. */
    public Stats stats() {
        return new Stats(nativeInvocations.sum(), fallbackInvocations.sum());
    }

    public record Stats(long nativeInvocations, long fallbackInvocations) {}

    private static int[] nativeTransform(NativeLoader.LoadedLibrary loaded, int[] input, int salt) {
        try (var arena = Arena.ofConfined()) {
            var inputSegment = arena.allocateFrom(ValueLayout.JAVA_INT, input);
            var outputSegment = arena.allocate(ValueLayout.JAVA_INT, input.length);
            var rc = loaded.transform(inputSegment, input.length, salt, outputSegment, input.length);
            if (rc != 0) {
                var detail = loaded.lastError();
                throw new IllegalStateException(
                        "native transform returned " + rc + (detail.isBlank() ? "" : ": " + detail));
            }
            var result = new int[input.length];
            // Bulk copy rather than a getAtIndex loop: the FFM bulk operation is a
            // single intrinsic, and this path is the one a future caller uses for
            // large batches.
            MemorySegment.copy(outputSegment, ValueLayout.JAVA_INT, 0, result, 0, input.length);
            return result;
        }
    }

    /**
     * Java implementation of the same contract as the C++ algorithm. The
     * multiplication intentionally uses Java's two's-complement overflow, which
     * matches the C++ side's unsigned 32-bit wraparound.
     */
    public static int[] javaTransform(int[] input, int salt) {
        Objects.requireNonNull(input, "input");
        var output = new int[input.length];
        for (var i = 0; i < input.length; i++) {
            output[i] = input[i] * 31 + salt + i;
        }
        return output;
    }

    private static boolean javaSelfTest() {
        var input = new int[] {0, 1, -1, 123456789};
        var expected = new int[] {17, 49, -12, -467806817};
        var actual = javaTransform(input, 17);
        return java.util.Arrays.equals(expected, actual);
    }

    private static String describe(Throwable t) {
        var message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
