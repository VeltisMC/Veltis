package org.veltismc.launcher;

import java.util.Locale;

/**
 * The stopwatch behind the server's single {@code Done} line.
 *
 * <p>Vanilla measures from the moment it begins preparing the level, which is
 * the last of the twenty things a cold start does. On a machine that has to
 * download Mojang's artifact, decompile five thousand files, apply the patch
 * set and compile it first, that number can be off by minutes — the log said
 * {@code Done (2.806s)!} for a start that had been running for the better part
 * of a minute, and there was no number anywhere that described the whole thing.
 *
 * <p>So this owns it instead. The clock starts in the first statement of the
 * launcher's {@code main}, before logging is configured, before the working
 * directory is inspected and long before anything is downloaded; and the patched
 * {@code DedicatedServer} asks for the value rather than computing its own. One
 * authoritative message, one clock, covering process start through world
 * preparation and Veltis initialization.
 *
 * <h2>Why the state is a system property</h2>
 *
 * <p>Because the patched Minecraft classes are loaded by a <em>child</em>
 * classloader whose parent is the platform loader: it cannot see a static field
 * of a class loaded by the application loader, and giving it one would mean
 * loading a second copy of this class whose field is zero. A system property is
 * visible to every loader in the JVM exactly once, which is what makes the value
 * the same for the process that recorded it and the class that reads it.
 *
 * <h2>Why the parent's elapsed time is added rather than its start</h2>
 *
 * <p>{@link VeltisLauncher} re-executes itself when the requested server home is
 * not the working directory, so the process that prints {@code Done} is not the
 * process the user started. {@link System#nanoTime()} has an arbitrary origin
 * that is only comparable within one JVM, so the parent passes the time it had
 * already spent rather than the instant it began; the child adds it to its own
 * elapsed time. The two intervals are each measured monotonically in the JVM
 * that measured them, and their sum is what the operator asked for.
 */
public final class VeltisStartup {

    /** Set by {@link #begin()}: the instant this launch started, in nanoTime terms. */
    public static final String START_PROPERTY = "veltismc.startNanos";

    /** Set on a re-executed child: nanoseconds the parent had already spent. */
    public static final String PARENT_ELAPSED_PROPERTY = "veltismc.parentElapsedNanos";

    private VeltisStartup() {
    }

    /**
     * Starts the clock. The first statement of {@code main}, and idempotent, so
     * a re-executed process that inherits a start instant does not reset it.
     */
    public static void begin() {
        if (System.getProperty(START_PROPERTY) != null) {
            return;
        }
        System.setProperty(START_PROPERTY,
            Long.toString(System.nanoTime() - parentElapsedNanos()));
    }

    /** Whether {@link #begin()} has run in this process. */
    public static boolean started() {
        return System.getProperty(START_PROPERTY) != null;
    }

    /**
     * Nanoseconds this launch has been running, for handing to a re-executed
     * child process as {@link #PARENT_ELAPSED_PROPERTY}.
     *
     * <p>The complement of {@link #begin()}: it reads <em>this</em> process's
     * start — which begin() backdated to the chain's origin by subtracting the
     * inherited interval — so the value is the whole logical launch so far, not
     * just this process's slice of it. That is exactly what a re-executed child
     * must be told: pass it on and every hop's clock continues the same line.
     *
     * @return 0 when the clock was never started, so a caller that forgot to
     *         start it passes on nothing rather than a nonsense value
     */
    public static long elapsedNanos() {
        var raw = System.getProperty(START_PROPERTY);
        if (raw == null) {
            return 0L;
        }
        try {
            var start = Long.parseLong(raw);
            // begin() already subtracted whatever the parent had spent, so the
            // start sits that far in the past and now - start is the whole
            // launch so far: this process's wall time plus every parent's.
            // Adding the parent's interval again would count it twice, which
            // is how a second re-execution reports more seconds than passed.
            return Math.max(0L, System.nanoTime() - start);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Seconds since {@link #begin()}, or {@code -1} when the clock was never started. */
    public static double elapsedSeconds() {
        var raw = System.getProperty(START_PROPERTY);
        if (raw == null) {
            return -1.0d;
        }
        try {
            return (System.nanoTime() - Long.parseLong(raw)) / 1.0E9d;
        } catch (NumberFormatException e) {
            return -1.0d;
        }
    }

    /**
     * The duration the {@code Done} line reports.
     *
     * @param vanillaSeconds the number vanilla measured — level preparation and
     *                       nothing before it — used only when no Veltis clock
     *                       is running, which is the case when the patched jar
     *                       is started without this launcher
     * @return a {@code %.3fs} duration, exactly vanilla's format, so one message
     *         replaces another rather than sitting beside it
     */
    public static String formatDone(double vanillaSeconds) {
        var total = elapsedSeconds();
        if (total < 0.0d) {
            return String.format(Locale.ROOT, "%.3fs", vanillaSeconds);
        }
        return String.format(Locale.ROOT, "%.3fs", total);
    }

    private static long parentElapsedNanos() {
        var raw = System.getProperty(PARENT_ELAPSED_PROPERTY);
        if (raw == null) {
            return 0L;
        }
        try {
            var value = Long.parseLong(raw);
            return value > 0L ? value : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
