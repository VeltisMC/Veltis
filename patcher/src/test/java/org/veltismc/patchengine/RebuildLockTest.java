package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rebuild lock — the "one process may rebuild this runtime artifact at a
 * time" rule behind {@link VeltisRuntime#prepare()}.
 *
 * <p>These tests drive the lock with real OS file locks and real threads, so
 * they cover both of the interesting shapes: a second process (this JVM's
 * {@code tryLock} returning {@code null}) and a second thread of the same
 * process ({@code OverlappingFileLockException}), which the acquire loop must
 * treat exactly the same way — wait, then proceed.
 */
class RebuildLockTest {

    @TempDir
    Path tmp;

    @Test
    void acquireAndReleaseCreatesThenRemovesTheLockFile() throws Exception {
        var file = tmp.resolve("home").resolve(".rebuild.lock");
        try (var lock = RebuildLock.acquire(file)) {
            assertTrue(Files.isRegularFile(file), "the lock file must exist while held");
        }
        assertFalse(Files.exists(file), "a released lock file is cleaned up best-effort");
    }

    @Test
    void aHeldLockBlocksAnotherAcquirerUntilRelease() throws Exception {
        var file = tmp.resolve("r").resolve(".rebuild.lock");
        var acquired = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Future<Boolean> other;
        try (var holder = RebuildLock.acquire(file)) {
            other = executor.submit(() -> {
                try (var lock = RebuildLock.acquire(file, Duration.ofSeconds(30))) {
                    acquired.countDown();
                    return true;
                }
            });
            Thread.sleep(300);
            assertEquals(1, acquired.getCount(),
                "the second acquirer must wait while the first holds the lock");
        }
        // The holder has released; the waiting acquirer now proceeds.
        assertTrue(other.get(10, TimeUnit.SECONDS));
        assertEquals(0, acquired.getCount(), "the second acquirer proceeds once released");
        executor.shutdownNow();
    }

    @Test
    void overlappingAcquireInTheSameJvmWaitsForTheHolder() throws Exception {
        // Same-JVM contention surfaces as OverlappingFileLockException; the
        // acquire loop must serialise it exactly like cross-process contention.
        var file = tmp.resolve("s").resolve(".rebuild.lock");
        var started = new CountDownLatch(1);
        var firstDone = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> {
                try (var lock = RebuildLock.acquire(file)) {
                    started.countDown();
                    assertTrue(firstDone.await(10, TimeUnit.SECONDS));
                }
                return true;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                try (var lock = RebuildLock.acquire(file, Duration.ofSeconds(20))) {
                    return true;
                }
            });
            Thread.sleep(300);
            firstDone.countDown();
            assertTrue(holder.get(10, TimeUnit.SECONDS));
            assertTrue(second.get(10, TimeUnit.SECONDS),
                "the overlapping acquirer must succeed once the holder releases");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aTimeoutWaitingForAStuckHolderIsReportedAsSuch() throws Exception {
        var file = tmp.resolve("t").resolve(".rebuild.lock");
        var executor = Executors.newSingleThreadExecutor();
        try (var holder = RebuildLock.acquire(file)) {
            var future = executor.submit(() -> RebuildLock.acquire(file, Duration.ofMillis(400)));
            var failure = assertThrows(ExecutionException.class, future::get);
            assertTrue(failure.getCause() instanceof java.io.IOException,
                failure.getCause().toString());
            assertTrue(failure.getCause().getMessage().contains("rebuild lock"),
                failure.getCause().getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theLockFileBeingLeftBehindDoesNotPreventReacquisition() throws Exception {
        var file = tmp.resolve("stale").resolve(".rebuild.lock");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "left behind by a process that died");
        try (var lock = RebuildLock.acquire(file)) {
            assertTrue(Files.isRegularFile(file));
        }
    }
}