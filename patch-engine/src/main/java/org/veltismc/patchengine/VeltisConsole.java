package org.veltismc.patchengine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Console and logging bootstrap shared by the launcher and the server entry
 * point.
 *
 * <p>VeltisMC has exactly one logging system: Log4j2, the same one Minecraft
 * uses. Every Veltis phase and lifecycle message goes through it with the
 * {@code [HH:mm:ss LEVEL]: msg} pattern; nothing else should write to the
 * console (no banners, diagnostic tables or raw {@code System.out} prints).
 *
 * <p>All methods are idempotent so both entry points can call them; whoever
 * runs first wins, which keeps the behaviour identical whether the server is
 * started through the launcher or directly.
 */
public final class VeltisConsole {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private VeltisConsole() {}

    /**
     * Points Log4j2 at VeltisMC's configuration file. Must run before the
     * first log statement anywhere — vanilla's own classpath carries a
     * competing config that must never win, because that is what produces
     * mixed patterns and the {@code Queue}/{@code Listener}/{@code Tracy}
     * appender errors.
     *
     * <p>No-op when {@code log4j.configurationFile} is already set (for
     * example by the launcher before the server entry point runs).
     */
    public static void configureLog4j() {
        if (System.getProperty("log4j.configurationFile") != null) return;
        for (var resource : new String[] {"/veltis-log4j2.xml", "/log4j2.xml"}) {
            var url = VeltisConsole.class.getResource(resource);
            if (url == null) continue;
            try (var in = url.openStream()) {
                var config = Files.createTempFile("veltis-log4j2", ".xml");
                config.toFile().deleteOnExit();
                Files.copy(in, config, StandardCopyOption.REPLACE_EXISTING);
                // A plain file:/// URI: jar:file: and bare paths make Log4j2
                // silently fall back to classpath discovery, which is what
                // mixed output patterns come from.
                System.setProperty("log4j.configurationFile", config.toUri().toString());
            } catch (IOException e) {
                System.err.println("[VeltisMC] Could not prepare log4j configuration: " + e.getMessage());
            }
            return;
        }
    }

    /**
     * Prepares the console: UTF-8 streams, the Windows console code page, and
     * a single logging pipeline ({@code java.util.logging} and therefore
     * {@code System.getLogger} route into Log4j2).
     *
     * <p>Deliberately native-library-free: Jansi (removed) and JNA (dropped
     * after testing) both trigger the JDK 24+ restricted
     * {@code System::load} warning on startup. A plain {@code chcp} child
     * process sets the console code page without any native access.
     */
    public static void installConsole() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        System.setProperty("jdk.console.encoding", "UTF-8");
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        enableUtf8Console();
    }

    /**
     * Human-readable duration for completion messages: {@code 42ms} for short
     * phases, {@code 1.243s} (three decimals, like vanilla's Done line) for
     * longer ones.
     */
    public static String formatDuration(long nanos) {
        var millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        if (millis < 1000) return millis + "ms";
        return String.format(Locale.ROOT, "%.3fs", millis / 1000.0);
    }

    /**
     * A {@link PrintStream} that routes each completed line through Log4j2
     * instead of raw stdout — used for third-party tool output (the
     * decompiler) so it keeps the same log format as everything else.
     * Warnings and errors are recognised by level keyword; everything else is
     * logged at DEBUG so internal chatter stays invisible.
     */
    public static PrintStream logStream(String loggerName) {
        var logger = LogManager.getLogger(loggerName);
        return new PrintStream(new LineRedirect(logger), false, StandardCharsets.UTF_8);
    }

    /**
     * Best-effort switch of the Windows console to code page 65001 (UTF-8)
     * so the UTF-8 bytes this JVM writes are decoded correctly by the
     * terminal. Runs as a child {@code cmd /c chcp} because any native route
     * (JNA, FFM) would emit the restricted-access warning we are eliminating.
     * Failure is never fatal: with redirected output the console code page
     * does not matter anyway.
     */
    private static void enableUtf8Console() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            var process = new ProcessBuilder("cmd.exe", "/c", "chcp", "65001")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // No usable console; plain text output still works.
        }
    }

    private static final class LineRedirect extends OutputStream {

        private final Logger log;
        private final StringBuilder line = new StringBuilder();

        private LineRedirect(Logger log) {
            this.log = log;
        }

        @Override
        public void write(int b) {
            var c = (char) b;
            if (c == '\n') {
                emit();
            } else if (c != '\r') {
                line.append(c);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) write(b[i]);
        }

        private void emit() {
            if (line.isEmpty()) return;
            var text = line.toString();
            line.setLength(0);
            if (text.contains("ERROR")) {
                log.error(text);
            } else if (text.contains("WARN")) {
                log.warn(text);
            } else {
                log.debug(text);
            }
        }
    }
}
