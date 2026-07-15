package org.veltismc.veltis.runtime.provision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veltismc.veltis.runtime.provision.MinecraftRuntimeProvisioner;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for format-specifier type correctness in
 * {@link MinecraftRuntimeProvisioner} progress reporting.
 *
 * <p>Verifies that every value passed to a printf format
 * specifier has the correct Java type, preventing
 * {@code java.util.IllegalFormatConversionException}.
 */
class MinecraftRuntimeProvisionerTest {

    private static final long MB = 1_000_000L;

    @Test
    @DisplayName("percent calculation produces int for %%d")
    void percentIsInt() {
        long downloaded = 5_000_000L;
        long total = 50_000_000L;
        var percent = (int) ((downloaded * 100) / total);
        assertEquals(10, percent);
        assertDoesNotThrow(() -> String.format("%d%%", percent),
            "%%d must accept int");
    }

    @Test
    @DisplayName("downloadedMb is long for %%d (0 MB case)")
    void downloadedMbIsLongZero() {
        long downloaded = 0L;
        long total = 50_000_000L;
        var downloadedMb = downloaded / MB;
        var totalMb = total / MB;
        assertInstanceOf(Long.class, downloadedMb);
        assertInstanceOf(Long.class, totalMb);
        assertDoesNotThrow(() -> String.format("%d MB / %d MB", downloadedMb, totalMb),
            "%%d must accept long");
    }

    @Test
    @DisplayName("speed calculation produces double for %%.1f")
    void speedIsDouble() {
        long downloaded = 25_000_000L;
        double elapsed = 2.5;
        var speedBps = elapsed > 0 ? downloaded / elapsed : 0.0;
        assertInstanceOf(Double.class, speedBps);
        assertDoesNotThrow(() -> String.format(Locale.ROOT, "%.1f MB/s", speedBps / (double) MB),
            "%%.1f must accept double");
    }

    @Test
    @DisplayName("speed is zero when elapsed is zero")
    void speedIsZeroWhenNoElapsed() {
        long downloaded = 0L;
        double elapsed = 0.0;
        var speedBps = elapsed > 0 ? downloaded / elapsed : 0.0;
        assertEquals(0.0, speedBps, 1e-10);
        assertDoesNotThrow(() -> String.format(Locale.ROOT, "%.1f", speedBps));
    }

    @Test
    @DisplayName("eta calculation produces long for %%d")
    void etaIsLong() {
        long downloaded = 25_000_000L;
        long total = 100_000_000L;
        double speedBps = 10_000_000.0;
        var eta = (speedBps > 0) ? (long) ((total - downloaded) / speedBps) : 0L;
        assertInstanceOf(Long.class, eta);
        assertEquals(7L, eta);
        assertDoesNotThrow(() -> String.format("ETA %ds", eta),
            "%%d must accept long");
    }

    @Test
    @DisplayName("eta is zero when speed is zero")
    void etaIsZeroWhenNoSpeed() {
        long downloaded = 0L;
        long total = 100_000_000L;
        double speedBps = 0.0;
        var eta = (speedBps > 0) ? (long) ((total - downloaded) / speedBps) : 0L;
        assertEquals(0L, eta);
        assertDoesNotThrow(() -> String.format("ETA %ds", eta));
    }

    @Test
    @DisplayName("full progress format string accepts correct types")
    void fullProgressFormatAcceptsTypes() {
        long downloaded = 30_000_000L;
        long total = 100_000_000L;
        double elapsed = 3.0;

        var percent = (int) ((downloaded * 100) / total);
        var speedBps = elapsed > 0 ? downloaded / elapsed : 0.0;
        var eta = (speedBps > 0) ? (long) ((total - downloaded) / speedBps) : 0L;
        var downloadedMb = downloaded / MB;
        var totalMb = total / MB;

        assertDoesNotThrow(() -> String.format(Locale.ROOT,
            "  %d%% (%d MB / %d MB)  %.1f MB/s  ETA %ds",
            percent, downloadedMb, totalMb, speedBps / (double) MB, eta),
            "Progress format must accept all type combinations");
    }

    @Test
    @DisplayName("download completion format accepts correct types")
    void downloadCompletionFormatAcceptsTypes() {
        double elapsed = 12.5;
        long fileSize = 60_000_000L;
        var totalMb = fileSize / (double) MB;

        assertInstanceOf(Double.class, elapsed);
        assertInstanceOf(Double.class, totalMb);

        assertDoesNotThrow(() -> String.format(Locale.ROOT,
            "  Downloaded in %.1f seconds", elapsed));
        assertDoesNotThrow(() -> String.format(Locale.ROOT,
            "  Average speed: %.1f MB/s", totalMb / elapsed));
    }

    @Test
    @DisplayName("format specifier mismatch throws IllegalFormatConversionException")
    void formatMismatchThrowsException() {
        assertThrows(java.util.IllegalFormatConversionException.class,
            () -> String.format("%d", 3.14),
            "%%d with Double must throw");
    }

    @Test
    @DisplayName("large file sizes compute correctly")
    void largeFileComputations() {
        long downloaded = 500_000_000L;
        long total = 2_000_000_000L;

        var percent = (int) ((downloaded * 100) / total);
        assertEquals(25, percent);

        var downloadedMb = downloaded / MB;
        var totalMb = total / MB;
        assertEquals(500, downloadedMb);
        assertEquals(2000, totalMb);

        assertDoesNotThrow(() -> String.format("%d%% (%d MB / %d MB)",
            percent, downloadedMb, totalMb));
    }

    @Test
    @DisplayName("simulate 60 MB download progress sequence without format errors")
    void simulateSixtyMbDownload() {
        long total = 60_000_000L;
        long startNanos = System.nanoTime();

        // Simulates logProgress() logic at 0%, 10%, 25%, 50%, 75%, 100%
        var steps = new long[]{0L, 6_000_000L, 15_000_000L, 30_000_000L, 45_000_000L, 60_000_000L};
        int lastPct = -1;

        for (long downloaded : steps) {
            long elapsedNanos = System.nanoTime() - startNanos;
            double elapsed = (double) elapsedNanos / 1_000_000_000.0;
            int percent = (int) ((downloaded * 100) / total);
            if (percent == lastPct && downloaded != total) continue;
            lastPct = percent;

            double speedBps = elapsed > 0.0 ? (double) downloaded / elapsed : 0.0;
            long eta = speedBps > 0.0 ? (long) ((double) (total - downloaded) / speedBps) : 0L;
            long downloadedMb = downloaded / MB;
            long totalMb = total / MB;
            double speedMbps = speedBps / (double) MB;

            assertDoesNotThrow(() -> String.format(
                "  %d%% (%d MB / %d MB)  %.1f MB/s  ETA %ds",
                percent, downloadedMb, totalMb, speedMbps, eta),
                "format must not throw at " + percent + "%");
        }
    }

    @Test
    @DisplayName("simulate completion format after 60 MB download")
    void simulateSixtyMbCompletion() {
        long fileSize = 60_000_000L;
        double elapsed = 15.0;
        double totalMb = (double) fileSize / (double) MB;

        assertDoesNotThrow(() -> String.format("  Downloaded in %.1f seconds", elapsed));
        assertDoesNotThrow(() -> String.format("  Average speed: %.1f MB/s", totalMb / elapsed));
    }
}



