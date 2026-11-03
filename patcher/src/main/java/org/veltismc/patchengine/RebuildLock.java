package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Serialises runtime rebuilds of one cache identity across processes.
 *
 * <p>Two launchers starting the same server home at the same moment both
 * validate the same cache key and both miss. Without a lock they would both
 * re-download the same Minecraft artifacts and both write the same staging jar,
 * which is waste at best and a torn publish at worst. The lock makes the
 * rebuild the critical section: the second process waits, and when it finally
 * acquires the lock its own validation finds the artifact current — build-for-
 * one, verify-for-everyone.
 *
 * <p>The lock is an OS-level file lock, so it survives {@code kill -9} and a
 * power cut: the moment the holding process dies the kernel releases the lock,
 * and the next process can rebuild from a known state (the {@code .writing}
 * staging sibling a torn build left behind is discarded first). Nothing about
 * the lock is trusted beyond "one process at a time"; the artifact itself is
 * always re-validated after acquisition.
 */
final class RebuildLock implements AutoCloseable {

    private static final Duration DEFAULT_WAIT = Duration.ofMinutes(30);
    private static final long RETRY_MILLIS = 200L;

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private RebuildLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the rebuild lock for {@code file}, waiting up to {@code waitFor}
     * for another holder. A lock held for longer is reported as what it is: a
     * rebuild is expected to take minutes, so a hold that outlives the wait is a
     * stuck peer the caller should not silently run over.
     */
    static RebuildLock acquire(Path file) throws IOException {
        return acquire(file, DEFAULT_WAIT);
    }

    static RebuildLock acquire(Path file, Duration waitFor) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var deadline = System.nanoTime() + waitFor.toNanos();
        FileChannel channel = null;
        try {
            while (true) {
                if (channel == null) {
                    channel = FileChannel.open(file, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                }
                FileLock lock = null;
                try {
                    // Same-JVM holders surface as OverlappingFileLockException
                    // rather than a null from tryLock; both mean "someone else
                    // is building", so both are waited out the same way.
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException ignored) {
                    lock = null;
                }
                if (lock != null) {
                    return new RebuildLock(file, channel, lock);
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException(
                        "Timed out after " + waitFor.toSeconds() + "s waiting for the rebuild"
                            + " lock at " + file
                            + "\n  Reason: another process is building this runtime and has not"
                            + " finished; it may be stuck or the network it needs may be slow");
                }
                Thread.sleep(RETRY_MILLIS);
            }
        } catch (IOException | InterruptedException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // The original failure is the one that matters.
                }
            }
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the rebuild lock at " + file,
                    ie);
            }
            throw (IOException) e;
        }
    }

    @Override
    public void close() {
        try {
            lock.close();
        } catch (IOException ignored) {
            // Released on process death regardless; the channel close below is
            // the guarantee that matters.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Same: an unreachable close never leaves a lock held past death.
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A left-behind lock file is still safe: acquiring it again works,
            // because the lock is the file lock, not the file's existence.
        }
    }
}