package org.veltismc.patchengine;

/**
 * Phase timings and counters for one patch run.
 *
 * <p>Phases are recorded as wall time spent inside them. With parallel application
 * the phase sums can exceed the end-to-end wall time measured by the caller, so
 * callers report both. Each worker accumulates into its own instance and the caller
 * folds them together with {@link #merge}, which keeps the counters lock-free.
 */
public final class PatchStats {

    /** Locating, reading, grouping and ordering patches. */
    public long discoveryNanos;
    /** Unified-diff header and hunk parsing. */
    public long parseNanos;
    /** Context matching and hunk application (CPU-bound work). */
    public long matchNanos;
    /** Reading target source files. */
    public long readNanos;
    /** Writing results for files that actually changed. */
    public long writeNanos;

    public int patchesDiscovered;
    public int patchesApplied;
    public int hunksParsed;
    public int filesRead;
    public int filesWritten;
    public int filesChanged;
    /** Files whose content did not change - left untouched on disk. */
    public int filesUnchanged;

    /** Folds {@code other} into this instance (used to collect worker results). */
    public void merge(PatchStats other) {
        discoveryNanos += other.discoveryNanos;
        parseNanos += other.parseNanos;
        matchNanos += other.matchNanos;
        readNanos += other.readNanos;
        writeNanos += other.writeNanos;
        patchesDiscovered += other.patchesDiscovered;
        patchesApplied += other.patchesApplied;
        hunksParsed += other.hunksParsed;
        filesRead += other.filesRead;
        filesWritten += other.filesWritten;
        filesChanged += other.filesChanged;
        filesUnchanged += other.filesUnchanged;
    }

    /** Sum of the recorded phases (may exceed wall time when work runs in parallel). */
    public long phaseTotalNanos() {
        return discoveryNanos + parseNanos + matchNanos + readNanos + writeNanos;
    }

    public static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
