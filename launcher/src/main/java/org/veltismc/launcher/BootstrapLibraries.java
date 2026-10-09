package org.veltismc.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Stage 0: make this jar's own dependencies exist before anything looks one up.
 *
 * <p>{@code veltismc.jar} carries no third-party code. Log4j, Gson and SnakeYAML
 * are named in its manifest {@code Class-Path} as {@code libraries/<maven path>}
 * entries, which the JVM resolves lazily and relative to the jar. "Lazily" is
 * the whole problem: the first lookup of a missing {@code Class-Path} entry
 * fails <em>and the failure is cached</em>, so a class that would have been
 * found a second later is never found at all, and the report is a
 * {@code ClassNotFoundException} naming a library that the message does not say
 * how to obtain or where to put it.
 *
 * <p>So the file check happens first, in plain JDK code, before any Log4j class
 * is named: verify what is present, fetch what is missing, and fail with the
 * path, the URL and both SHA-1 values when it still cannot. Everything downstream —
 * {@link VeltisConsole#configureLog4j()}, the logger the launcher obtains right
 * after it, the JSON parser {@code MojangMetadata} needs — then starts with its
 * dependencies already on disk and verified.
 *
 * <p><b>Why a fetch means restarting.</b> Class lookup is not the only thing
 * that probes a classpath entry: the first TLS handshake initializes the
 * {@code SSLContext}, whose provider lookup sweeps <em>every</em> classpath
 * entry the way a {@code ServiceLoader} does; starting a child process probes
 * one too, and asking for a resource that is nowhere walks the whole list. A
 * probe performed while the library files are still missing caches the miss,
 * and the fetch that lands a second later cannot repair it — the symptom is a
 * {@code NoClassDefFoundError} for a file that is demonstrably on disk. So
 * {@link #ensure()} verifies with file reads and SHA-1 only; when a fetch is
 * needed it fetches in this process anyway, and returns {@code true} so the
 * launcher can re-execute itself and let a fresh JVM — one whose files already
 * exist when its first probe runs — do the booting. Whatever this process
 * cached while it fetched is harmless: it exits without naming another class.
 *
 * <p>The table this reads is written by the build ({@code writeBootstrapLibraries})
 * and is only present in a packaged jar. Running from {@code build/classes} has
 * these jars on the classpath by other means, finds no table, and does nothing.
 */
final class BootstrapLibraries {

    /** Where the build puts the table, inside the jar. */
    static final String TABLE = "/META-INF/veltis/bootstrap-libraries.txt";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private BootstrapLibraries() {
    }

    /**
     * Verifies every library the manifest points at, downloading the ones that
     * are missing or wrong — and reports whether it did.
     *
     * <p>Verification is file reads and SHA-1 and touches nothing else. The
     * fetch, when one is needed, is ordinary in-process HTTPS: it damages this
     * JVM's view of its own classpath (the handshake sweeps entries that do not
     * exist yet and remembers the misses), which is exactly why the launcher
     * restarts instead of continuing — see the class javadoc. A caller that
     * receives {@code true} must not boot the server in this JVM.
     *
     * @return {@code true} when a fetch happened, meaning this JVM's classpath
     *         can no longer be trusted to see the files it just wrote
     * @throws IllegalStateException with a complete report when a library
     *                               cannot be made available; the caller prints
     *                               it and exits, because there is no logging
     *                               yet and starting without the library would
     *                               fail somewhere far less specific
     */
    static boolean ensure() {
        var rows = readTable();
        if (rows.isEmpty()) {
            return false;
        }
        var root = librariesRoot();

        boolean fetching = false;
        for (var row : rows) {
            if (verify(root, row) != null) {
                fetching = true;
                break;
            }
        }
        if (!fetching) {
            return false;
        }

        for (var row : rows) {
            ensureOne(root, row);
        }
        for (var row : rows) {
            var reason = verify(root, row);
            if (reason != null) {
                throw new IllegalStateException(report(row, reason,
                    "check network access to " + row.url()
                        + " and start again, or put the file there yourself at "
                        + root.resolve(row.path()).toAbsolutePath()));
            }
        }
        return true;
    }

    /**
     * Returns {@code null} when the row's file is present and has the right
     * SHA-1, else the reason it does not. File reads only — never a network.
     */
    private static String verify(Path root, Row row) {
        var target = resolveWithin(root, row.path());
        if (!Files.isRegularFile(target)) {
            return "the file is missing";
        }
        try {
            var actual = hex(sha1(target));
            if (!row.sha1().equalsIgnoreCase(actual)) {
                return "expected SHA-1 " + row.sha1() + " but the file has " + actual;
            }
        } catch (IOException e) {
            return "the file could not be read: " + messageOf(e);
        }
        return null;
    }

    private static void ensureOne(Path root, Row row) {
        var target = resolveWithin(root, row.path());

        try {
            if (Files.isRegularFile(target)
                    && row.sha1().equalsIgnoreCase(hex(sha1(target)))) {
                return;
            }
        } catch (IOException e) {
            // A file that cannot be read is not a valid copy of anything, so it
            // is not treated as one: fall through and replace it. Aborting here
            // would leave an operator with a library they cannot fix, and the
            // fetch below reports its own failure if the file also cannot be
            // replaced.
            deleteQuietly(target);
        }

        Path staging = null;
        try {
            Files.createDirectories(target.getParent());
            staging = target.resolveSibling(target.getFileName() + ".part");
            Files.deleteIfExists(staging);

            var client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            var request = HttpRequest.newBuilder(URI.create(row.url()))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofFile(staging));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(report(row,
                    "the download answered HTTP " + response.statusCode(),
                    "check that " + row.url() + " is reachable from this machine"
                        + " (a proxy or a firewall is the usual reason), then start again"));
            }

            var actual = hex(sha1(staging));
            if (!row.sha1().equalsIgnoreCase(actual)) {
                throw new IllegalStateException(report(row,
                    "expected SHA-1 " + row.sha1() + " but the downloaded file has "
                        + actual,
                    "the transfer was truncated or altered; start again, and if it"
                        + " repeats the file at " + row.url() + " has changed and the"
                        + " build needs to be re-run"));
            }

            try {
                Files.move(staging, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same directory, so this should not happen; a half-written
                // library is worse than a slightly slower one either way.
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IllegalStateException e) {
            if (staging != null) {
                deleteQuietly(staging);
            }
            throw e;
        } catch (IOException | InterruptedException e) {
            if (staging != null) {
                deleteQuietly(staging);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(report(row,
                "the download failed: " + messageOf(e),
                "check network access to " + row.url() + " and start again;"
                    + " or put the file there yourself at "
                        + root.resolve(row.path()).toAbsolutePath()));
        }
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    private record Row(String path, String url, String sha1) {
    }

    /**
     * Reads the build's library table, or an empty list when this jar has none.
     *
     * <p>A table that exists but does not parse is a build fault rather than a
     * missing library, so it fails: a jar whose own metadata cannot be read
     * cannot be trusted to know what else it needs.
     */
    private static List<Row> readTable() {
        try (InputStream in = BootstrapLibraries.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                return List.of();
            }
            var rows = new ArrayList<Row>();
            var lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .toList();
            for (var line : lines) {
                var trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                var parts = trimmed.split("\t", -1);
                if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank()
                        || parts[2].isBlank()) {
                    throw new IllegalStateException("This jar's library table is"
                        + " malformed"
                        + "\n  Entry: " + trimmed
                        + "\n  Reason: the build writes one tab-separated"
                        + " path<TAB>url<TAB>sha1 per line, and this is not one, so the"
                        + " jar cannot know what to fetch"
                        + "\n  Fix: rebuild it with ./gradlew buildVeltisMC");
                }
                rows.add(new Row(parts[0].strip(), parts[1].strip(), parts[2].strip()));
            }
            return List.copyOf(rows);
        } catch (IOException e) {
            throw new IllegalStateException("This jar's library table could not"
                + " be read"
                + "\n  Reason: " + messageOf(e)
                + "\n  Fix: rebuild it with ./gradlew buildVeltisMC");
        }
    }

    /**
     * Where the manifest's {@code libraries/} entries resolve to: beside this
     * jar, because that is where the JVM looks when it reads {@code Class-Path}.
     *
     * <p>The two must agree exactly. A preflight that staged libraries somewhere
     * else would succeed and then hand the class loader a jar directory that
     * still had nothing in it.
     */
    private static Path ownCodeSource() {
        try {
            return Path.of(BootstrapLibraries.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    private static Path librariesRoot() {
        var own = ownCodeSource();
        var dir = Files.isDirectory(own) ? own : own.getParent();
        return dir == null ? Path.of("libraries") : dir.resolve("libraries");
    }

    /**
     * Resolves a table path under {@code root}, refusing one that leaves it.
     *
     * <p>The table ships inside this jar, so it is not untrusted; a path that
     * escaped would still mean writing a jar somewhere the operator did not ask
     * for, and the check costs one line.
     */
    private static Path resolveWithin(Path root, String relative) {
        var target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("This jar's library table names a"
                + " path outside its library directory"
                + "\n  Entry: " + relative
                + "\n  Reason: resolving it would write to " + target
                + "\n  Fix: rebuild it with ./gradlew buildVeltisMC");
        }
        return target;
    }

    // ------------------------------------------------------------------
    // Reporting, hashing, cleanup
    // ------------------------------------------------------------------

    private static String report(Row row, String reason, String fix) {
        return "A required library could not be made available"
            + "\n  Library: " + row.path()
            + "\n  URL: " + row.url()
            + "\n  Expected SHA-1: " + row.sha1()
            + "\n  Reason: " + reason
            + "\n  Fix: " + fix;
    }

    private static MessageDigest digester() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform ships SHA-1; this cannot happen.
            throw new IllegalStateException("SHA-1 is not available in this JVM", e);
        }
    }

    private static byte[] sha1(Path file) throws IOException {
        var digest = digester();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover .part is harmless: the next start deletes it before
            // writing, and nothing on the classpath points at it.
        }
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
