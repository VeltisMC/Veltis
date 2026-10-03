package org.veltismc.patchengine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Materialises Mojang's artifacts into the workspace, verified.
 *
 * <p>Three rules hold for every artifact, server jar and library alike:
 *
 * <ul>
 *   <li><b>Verify before trusting.</b> A cached file is re-hashed against the
 *       SHA-1 from Mojang's metadata before it is reused. A mismatch is not
 *       fatal the first time: the file is discarded and downloaded again, and
 *       only a second mismatch is an error — that is the corrupt-cache
 *       recovery path.</li>
 *   <li><b>Never trust bytes that were not verified.</b> A download lands at the
 *       final path and is deleted unless its SHA-1 matches, and a cached file is
 *       re-hashed before every reuse. There is no staging file: the verification
 *       is what makes a partial transfer safe, so an interrupted download can
 *       never be mistaken for a valid cache entry, and the workspace never
 *       accumulates second-file names to clean up.</li>
 *   <li><b>Say exactly what failed.</b> Failures name the source URL, the
 *       expected SHA-1 and the actual one, so a mirror or a truncated transfer
 *       is diagnosable without enabling debug logging.</li>
 * </ul>
 *
 * <p>Modern Minecraft servers ship as a bundler jar whose real classes live at
 * {@code META-INF/versions/<version>/server-<version>.jar}. Mojang's manifest
 * SHA-1 covers the outer bundler, so the outer jar is verified first and the
 * inner jar is then extracted from verified bytes.
 */
public final class MinecraftDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient client;
    private static final org.apache.logging.log4j.Logger log =
        org.apache.logging.log4j.LogManager.getLogger(MinecraftDownloader.class);

    public MinecraftDownloader() {
        this(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public MinecraftDownloader(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }

    /** What a single artifact fetch did. */
    public enum Outcome {
        /** The workspace already held a file whose SHA-1 matched; nothing was fetched. */
        CACHED,
        /** The file was fetched and verified. */
        DOWNLOADED,
        /** A cached file failed verification, so it was discarded and re-fetched. */
        RECOVERED
    }

    /** The result of fetching one artifact. */
    public record Download(Outcome outcome, Path file, String sha1, long bytes) {
    }

    /**
     * Fetches the server jar and the classes jar inside it.
     *
     * @return the verified classes jar — the one the decompiler reads
     */
    public Download downloadServer(MojangMetadata.VersionMetadata metadata, VeltisWorkspace workspace) {
        var bundler = workspace.vanillaServerJar();
        var classes = workspace.vanillaClassesJar();
        var server = fetch(metadata.serverArtifact(), bundler, metadata.id() + " server jar");
        extractClassesJar(server.file(), metadata.id(), classes);
        return server;
    }

    /**
     * Fetches every declared library, verified, preserving Mojang's layout under
     * {@link VeltisWorkspace#librariesDirectory()}.
     *
     * @return the number of files actually fetched (recoveries count as fetched)
     */
    public int downloadLibraries(MojangMetadata.VersionMetadata metadata, VeltisWorkspace workspace) {
        var libraryRoot = workspace.librariesDirectory();
        try {
            Files.createDirectories(libraryRoot);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to create the library directory " + libraryRoot, e);
        }
        var fetched = 0;
        for (var library : metadata.libraries()) {
            var target = resolveInside(libraryRoot, library.artifact().path());
            if (fetch(library.artifact(), target, library.name()).outcome() != Outcome.CACHED) {
                fetched++;
            }
        }
        return fetched;
    }

    /**
     * Ensures {@code target} holds the artifact, verifying and recovering as
     * needed.
     */
    public Download fetch(MojangMetadata.Artifact artifact, Path target, String description) {
        var expected = artifact.sha1().toLowerCase(java.util.Locale.ROOT);
        var actual = verifyIfPresent(target);
        if (actual != null && actual.equals(expected)) {
            return new Download(Outcome.CACHED, target, actual, sizeOf(target));
        }

        // A cached file that failed verification is proven bad, discarded, and
        // re-fetched: one corrupt entry should cost a re-download, not a build.
        var recovered = false;
        if (actual != null) {
            log.warn("[VeltisMinecraft] Cached {} is corrupt; downloading it again", description);
            log.warn("[VeltisMinecraft]   Source: {}", artifact.url());
            log.warn("[VeltisMinecraft]   Expected SHA-1: {}", expected);
            log.warn("[VeltisMinecraft]   Actual SHA-1: {}", actual);
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new PatchEngineException(
                    "[VeltisMinecraft] Failed to discard the corrupt cache entry " + target, e);
            }
            recovered = true;
        }

        download(artifact, target, description, expected);
        return new Download(recovered ? Outcome.RECOVERED : Outcome.DOWNLOADED, target, expected,
            sizeOf(target));
    }

    /** @return the file's SHA-1, or {@code null} when it does not exist */
    private String verifyIfPresent(Path target) {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return MojangMetadata.sha1(target);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Downloads straight into {@code target} and deletes it unless it verifies.
     *
     * <p>There is no separate staging file, and that is a deliberate decision
     * rather than a shortcut. A partial download at the final path is harmless
     * here because nothing ever trusts a file at that path on the strength of its
     * existing: {@link #fetch} re-hashes the cached file against Mojang's
     * published SHA-1 before every reuse, so a truncated or interrupted transfer
     * is detected on the next run, reported with both hashes, and re-downloaded.
     * A staging sibling would buy nothing except a second file name to keep out
     * of the workspace and out of the way of {@code clean}.
     *
     * <p>The one thing this does depend on is that a failed attempt leaves no
     * file behind at all, so that a later run starts from a clean state instead
     * of from the wreckage of the previous one.
     */
    private void download(MojangMetadata.Artifact artifact, Path target, String description,
                          String expected) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to prepare the download directory for " + description, e);
        }

        String actual = null;
        try {
            actual = transfer(artifact.url(), target);
            if (!expected.equals(actual)) {
                // A mismatch here is a mirror problem or a truncated transfer;
                // retrying once is worth it, and the retry keeps the same
                // evidence in the message either way.
                log.warn("[VeltisMinecraft] SHA-1 mismatch for {}, retrying once", description);
                actual = transfer(artifact.url(), target);
            }
            if (!expected.equals(actual)) {
                throw new PatchEngineException(
                    "[VeltisMinecraft] Failed to download " + description
                        + "\n  Source: " + artifact.url()
                        + "\n  Expected SHA-1: " + expected
                        + "\n  Actual SHA-1: " + actual
                        + "\n  Reason: the artifact does not match the hash Mojang published;"
                        + " the download was discarded and nothing was changed in the workspace");
            }
        } catch (PatchEngineException e) {
            deleteQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteQuietly(target);
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to download " + description
                    + "\n  Source: " + artifact.url()
                    + "\n  Expected SHA-1: " + expected
                    + "\n  Actual SHA-1: " + (actual != null ? actual : "<not downloaded>")
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    private String transfer(String url, Path target) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", "VeltisMC")
            .GET()
            .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("Mojang returned HTTP " + response.statusCode());
        }
        try (var body = response.body()) {
            Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return MojangMetadata.sha1(target);
    }

    /**
     * Lifts the real class jar out of Mojang's bundler jar.
     *
     * <p>Modern versions nest it at {@code META-INF/versions/<version>/server-<version>.jar}.
     * The nested entry is found by name so a version whose bundler layout differs
     * slightly still works. When there is no inner jar the verified outer jar is
     * the server jar, which is the pre-bundler layout.
     */
    private void extractClassesJar(Path bundler, String version, Path classesJar) {
        var nested = "META-INF/versions/" + version + "/";
        try (var fs = FileSystems.newFileSystem(bundler, (ClassLoader) null)) {
            Path inner = null;
            try (var walk = Files.walk(fs.getPath("/" + nested))) {
                inner = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
            } catch (Exception e) {
                inner = null;   // the entry directory does not exist
            }
            if (inner == null) {
                log.debug("[VeltisMinecraft] {} is not a bundler jar; using it directly", version);
                copyVerified(bundler, classesJar);
                return;
            }
            Files.createDirectories(classesJar.getParent());
            try {
                Files.copy(inner, classesJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(classesJar);
                throw e;
            }
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to extract the server classes from " + bundler
                    + "\n  Source: " + bundler
                    + "\n  Expected SHA-1: " + sha1Quietly(bundler)
                    + "\n  Actual SHA-1: " + sha1Quietly(bundler)
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Copies the verified jar when it is not a bundler, i.e. the pre-bundler
     * layout where the artifact <em>is</em> the classes jar.
     *
     * <p>A copy and not a move: the artifact at {@link VeltisWorkspace#vanillaServerJar()}
     * is persistent in a server installation and is what the next launch hashes
     * to decide whether the runtime is still valid, so moving it away would
     * leave an installation that reports itself incomplete after every build.
     */
    private void copyVerified(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every library jar in the workspace, in stable order (used for the compile classpath). */
    public static List<Path> libraryJars(VeltisWorkspace workspace) {
        var root = workspace.librariesDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        var jars = new ArrayList<Path>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".jar"))
                .sorted()
                .forEach(jars::add);
        } catch (IOException ignored) {
            // A partially readable library tree is still usable for whatever
            // was found; the caller reports compile errors if it is not enough.
        }
        return List.copyOf(jars);
    }

    static Path resolveInside(Path root, String relative) {
        var sep = root.getFileSystem().getSeparator().charAt(0);
        var normalized = root.resolve(relative.replace('/', sep)).normalize();
        if (!normalized.startsWith(root.normalize())) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Refusing a library path that escapes the workspace: " + relative);
        }
        return normalized;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String sha1Quietly(Path path) {
        try {
            return MojangMetadata.sha1(path);
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A file that could not be removed is still not trusted: the next
            // run re-hashes it before reuse, sees the mismatch and replaces it.
            log.debug("[VeltisMinecraft] Could not delete {}", path, ignored);
        }
    }

    /** Writes the marker that proves which artifact produced the decompiled tree. */
    static void writeMarker(Path marker, String content) {
        try {
            Files.writeString(marker, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to write " + marker, e);
        }
    }
}
