package org.veltismc.patchengine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downloading and verifying artifacts, against a real HTTP server.
 *
 * <p>Mojang's endpoints cannot be depended on in a test, but the behaviour that
 * matters is entirely local: a verified file is reused, a corrupt one is
 * detected and replaced, and bytes that do not match the published SHA-1 are
 * refused with a message naming the URL and both hashes. A loopback
 * {@link HttpServer} is a real HTTP client talking to a real server, so the
 * transfer, hashing and staging code is exercised as written.
 */
class MinecraftDownloaderTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private ExecutorService executor;
    private final Map<String, byte[]> routes = new LinkedHashMap<>();
    private final Map<String, Long> delays = new LinkedHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newFixedThreadPool(8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            var current = inFlight.incrementAndGet();
            peakInFlight.accumulateAndGet(current, Math::max);
            try {
                var delay = delays.get(exchange.getRequestURI().getPath());
                if (delay != null) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                var body = routes.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } finally {
                inFlight.decrementAndGet();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void serve(String path, byte[] body) {
        routes.put(path, body);
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private MinecraftDownloader downloader() {
        return new MinecraftDownloader();
    }

    /** An artifact Mojang would have published: a path, a URL and a real hash. */
    private MojangMetadata.Artifact artifact(String mojangPath, String body) {
        return artifact(url("/" + mojangPath), mojangPath, bytes(body));
    }

    private static MojangMetadata.Artifact artifact(String artifactUrl, String path, byte[] body) {
        return new MojangMetadata.Artifact(path, artifactUrl, sha1(body), body.length);
    }

    // ------------------------------------------------------------------
    // Verification
    // ------------------------------------------------------------------

    @Test
    void aVerifiedDownloadIsFetchedOnceAndThenReused() throws Exception {
        var body = "server bytes";
        serve("/server.jar", bytes(body));
        var target = tmp.resolve("vanilla").resolve("server.jar");
        var artifact = artifact("server.jar", body);

        var first = downloader().fetch(artifact, target, "test server jar");
        assertEquals(MinecraftDownloader.Outcome.DOWNLOADED, first.outcome());
        assertEquals(body, Files.readString(target));
        assertEquals(1, requests.get());

        var second = downloader().fetch(artifact, target, "test server jar");
        assertEquals(MinecraftDownloader.Outcome.CACHED, second.outcome(),
            "a file whose SHA-1 matches must not be downloaded again");
        assertEquals(1, requests.get());
        assertEquals(body, Files.readString(target));
    }

    @Test
    void aCorruptCacheEntryIsDetectedAndReplaced() throws Exception {
        var body = "the real bytes";
        serve("/server.jar", bytes(body));
        var target = tmp.resolve("vanilla").resolve("server.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "corrupted by something else");

        var recovered = downloader().fetch(artifact("server.jar", body), target,
            "test server jar");

        assertEquals(MinecraftDownloader.Outcome.RECOVERED, recovered.outcome());
        assertEquals(1, requests.get(), "a corrupt entry is discarded, not trusted");
        assertEquals(body, Files.readString(target));
    }

    @Test
    void bytesThatDoNotMatchThePublishedHashAreRefusedAndNothingIsPromoted() {
        serve("/server.jar", bytes("not what mojang published"));
        var target = tmp.resolve("vanilla").resolve("server.jar");
        var artifact = artifact("server.jar", "the real bytes");

        var failure = assertThrows(PatchEngineException.class,
            () -> downloader().fetch(artifact, target, "test server jar"));

        var msg = failure.getMessage();
        assertTrue(msg.startsWith("[VeltisMinecraft] Failed to download test server jar"), msg);
        assertTrue(msg.contains("Source: " + url("/server.jar")), msg);
        assertTrue(msg.contains("Expected SHA-1: " + sha1(bytes("the real bytes"))), msg);
        assertTrue(msg.contains("Actual SHA-1: " + sha1(bytes("not what mojang published"))), msg);
        assertTrue(msg.contains("Reason:"), msg);

        assertFalse(Files.exists(target), "unverified bytes must never reach the workspace");
        assertFalse(Files.exists(target.resolveSibling("server.jar.part")),
            "the staging file must be cleaned up after a failure");
        assertEquals(2, requests.get(), "the mismatch is retried once before giving up");
    }

    @Test
    void anHttpErrorIsReportedWithTheSourceUrl() {
        var target = tmp.resolve("vanilla").resolve("server.jar");
        var missing = new MojangMetadata.Artifact("gone.jar", url("/gone.jar"),
            sha1(bytes("anything")), 7);

        var failure = assertThrows(PatchEngineException.class,
            () -> downloader().fetch(missing, target, "test server jar"));

        var msg = failure.getMessage();
        assertTrue(msg.contains("Source: " + url("/gone.jar")), msg);
        assertTrue(msg.contains("HTTP 404"), msg);
        assertTrue(msg.contains("Actual SHA-1: <not downloaded>"), msg);
        assertFalse(Files.exists(target));
    }

    // ------------------------------------------------------------------
    // Libraries
    // ------------------------------------------------------------------

    @Test
    void librariesAreFetchedIntoMojangsOwnLayout() throws Exception {
        var workspace = TestWorkspace.create(tmp.resolve("ws")).workspace();
        var first = bytes("datafixerupper");
        var second = bytes("slf4j-api");
        var firstPath = "com/mojang/datafixerupper/8.3.1/datafixerupper-8.3.1.jar";
        var secondPath = "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar";
        serve("/" + firstPath, first);
        serve("/" + secondPath, second);

        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact("server.jar", "s"),
            List.of(library("com.mojang:datafixerupper:8.3.1", firstPath, first),
                library("org.slf4j:slf4j-api:2.0.9", secondPath, second)));

        assertEquals(2, downloader().downloadLibraries(metadata, workspace));
        assertEquals(2, MinecraftDownloader.libraryJars(workspace).size());
        assertEquals(first.length, Files.size(workspace.librariesDirectory().resolve(firstPath)));
        assertEquals(second.length, Files.size(workspace.librariesDirectory().resolve(secondPath)));

        // A second run is a no-op: both hashes already match.
        assertEquals(0, downloader().downloadLibraries(metadata, workspace));
        assertEquals(2, requests.get());
    }

    @Test
    void aLibraryPathThatEscapesTheWorkspaceIsRefused() {
        var failure = assertThrows(PatchEngineException.class,
            () -> MinecraftDownloader.resolveInside(tmp.resolve("libs"), "../../evil.jar"));
        assertTrue(failure.getMessage().contains("escapes the workspace"), failure.getMessage());
    }

    @Test
    void digestsAgreeWithTheJdk() throws Exception {
        var file = tmp.resolve("hash-me.bin");
        Files.write(file, bytes("hello world"));
        assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed", MojangMetadata.sha1(file));
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            MojangMetadata.sha256(file));
    }

    @Test
    void theServerJarIsExtractedFromTheVerifiedBundler() throws Exception {
        var bundler = tmp.resolve("bundler.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(bundler))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/versions/26.3/server-26.3.jar"));
            out.write(bytes("the real classes"));
            out.closeEntry();
        }
        serve("/server.jar", Files.readAllBytes(bundler));

        var workspace = TestWorkspace.create(tmp.resolve("ws2")).workspace();
        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact(url("/server.jar"), "server.jar", Files.readAllBytes(bundler)), List.of());

        var download = downloader().downloadServer(metadata, workspace);

        assertEquals(MinecraftDownloader.Outcome.DOWNLOADED, download.outcome());
        assertTrue(Files.isRegularFile(workspace.vanillaServerJar()));
        assertEquals("the real classes", Files.readString(workspace.vanillaClassesJar()),
            "the classes must come from inside the verified bundler");
    }

    // ------------------------------------------------------------------
    // Parallel library downloads
    // ------------------------------------------------------------------

    /** A payload whose SHA-1 differs from what Mojang "published". */
    private static String sha1Hex(byte[] body) {
        return sha1(body);
    }

    private List<MojangMetadata.Library> slowLibraries(int count, long delayMillis) {
        var libraries = new ArrayList<MojangMetadata.Library>();
        for (int i = 0; i < count; i++) {
            var body = bytes("library artifact number " + i);
            var path = "net/example/lib" + i + "/1.0/lib" + i + "-1.0.jar";
            serve("/" + path, body);
            delays.put("/" + path, delayMillis);
            libraries.add(library("net.example:lib" + i + ":1.0", path, body));
        }
        return libraries;
    }

    @Test
    void librariesAreFetchedConcurrentlyRatherThanOneRoundTripAtATime()
        throws Exception {
        var workspace = TestWorkspace.create(tmp.resolve("ws")).workspace();
        var libraries = slowLibraries(4, 600L);
        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact("server.jar", "s"), libraries);

        // concurrency 1 is the old behaviour; 2 is enough to expose it.
        var downloader = new MinecraftDownloader(HttpClient.newHttpClient(), 2);
        var started = System.nanoTime();
        var fetched = downloader.downloadLibraries(metadata, workspace);
        var elapsed = System.nanoTime() - started;

        assertEquals(4, fetched);
        assertTrue(peakInFlight.get() >= 2,
            "the pool of 2 must actually run 2 transfers at once; peak was "
                + peakInFlight.get());
        // 4 x 600ms sequential is 2400ms; with 2 workers it should be ~1200ms.
        // The generous bound keeps the assertion about overlap, not wall clock.
        assertTrue(elapsed / 1_000_000 < 2_000L,
            "4 x 600ms downloads with 2 workers should overlap; took "
                + (elapsed / 1_000_000) + "ms");

        // A warm run touches nothing.
        assertEquals(0, downloader.downloadLibraries(metadata, workspace));
        assertEquals(libraries.size(), requests.get(),
            "reuse must not re-request already-verified files");
    }

    @Test
    void serverAndLibrariesTransferInTheSameWindowRatherThanSerially()
        throws Exception {
        var workspace = TestWorkspace.create(tmp.resolve("ws3")).workspace();
        var bundler = tmp.resolve("bundler.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(bundler))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/versions/26.3/server-26.3.jar"));
            out.write(bytes("the real classes"));
            out.closeEntry();
        }
        var serverBytes = Files.readAllBytes(bundler);
        serve("/server.jar", serverBytes);
        delays.put("/server.jar", 700L);
        var libraries = slowLibraries(2, 600L);
        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact(url("/server.jar"), "server.jar", serverBytes), libraries);

        // One downloader per artifact family, exactly as fetchArtifacts does, so
        // a library failure's client teardown cannot kill the server transfer.
        var serverDownloader = new MinecraftDownloader(HttpClient.newHttpClient(), 2);
        var libraryDownloader = new MinecraftDownloader(HttpClient.newHttpClient(), 2);
        var executor = Executors.newFixedThreadPool(2);
        var started = System.nanoTime();
        var serverFuture = executor.submit(
            () -> serverDownloader.downloadServer(metadata, workspace));
        var libraryFuture = executor.submit(
            () -> libraryDownloader.downloadLibraries(metadata, workspace));
        var server = serverFuture.get();
        var fetched = libraryFuture.get();
        var elapsed = System.nanoTime() - started;
        executor.shutdownNow();

        assertEquals(MinecraftDownloader.Outcome.DOWNLOADED, server.outcome());
        assertEquals("the real classes", Files.readString(workspace.vanillaClassesJar()));
        assertEquals(2, fetched);
        assertTrue(peakInFlight.get() >= 2,
            "the server transfer and the library batch must overlap; peak was "
                + peakInFlight.get());
        // Serial would be 700 + 600 + 600 = 1900ms; overlapped ~700-1300ms. The
        // generous bound keeps the assertion about overlap, not wall clock.
        assertTrue(elapsed / 1_000_000 < 1_600L,
            "two independent artifact families should not pay the phase twice; took "
                + (elapsed / 1_000_000) + "ms");
    }

    @Test
    void aFailingLibraryFailsTheWholeBatchWithItsOwnEvidence()
        throws Exception {
        var workspace = TestWorkspace.create(tmp.resolve("ws2")).workspace();
        var good = slowLibraries(3, 200L);
        var bad = new MojangMetadata.Library("net.example:bad:1.0", "net.example:bad:1.0",
            new MojangMetadata.Artifact("net/example/bad/1.0/bad-1.0.jar",
                url("/net/example/bad/1.0/bad-1.0.jar"), sha1Hex(bytes("what we expect")), 15));
        var all = new ArrayList<>(good);
        all.add(bad);
        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact("server.jar", "s"), all);

        // The library is not served at all: HTTP 404, which is the download
        // path's "not there" case with a concrete URL in the report.
        var downloader = new MinecraftDownloader(HttpClient.newHttpClient(), 4);
        var failure = assertThrows(PatchEngineException.class,
            () -> downloader.downloadLibraries(metadata, workspace));

        var msg = failure.getMessage();
        assertTrue(msg.contains("[VeltisMinecraft]"), msg);
        assertTrue(msg.contains("bad-1.0.jar"), msg);
        assertTrue(msg.contains("HTTP 404"), msg);
        assertFalse(Files.exists(
            workspace.librariesDirectory().resolve("net/example/bad/1.0/bad-1.0.jar")),
            "the failed library must leave nothing behind");
        // No further download work may be scheduled once one library has failed;
        // the whole batch is cancelled rather than finished first.
        assertTrue(requests.get() <= all.size() + 2,
            "fail-fast means the remaining downloads are cancelled, not completed; requests="
                + requests.get());
    }

    @Test
    void aCorruptCachedLibraryIsRecoveredWhileOthersDownload()
        throws Exception {
        var workspace = TestWorkspace.create(tmp.resolve("ws3")).workspace();
        var libraries = slowLibraries(4, 100L);

        // Pre-corrupt exactly one library on disk, as a crashed transfer would.
        var corruptPath = libraries.get(1).artifact().path();
        var corruptTarget = workspace.librariesDirectory().resolve(corruptPath);
        Files.createDirectories(corruptTarget.getParent());
        Files.writeString(corruptTarget, "torn in half by a power cut");

        var metadata = new MojangMetadata.VersionMetadata("26.3", 25,
            artifact("server.jar", "s"), libraries);
        var fetched = new MinecraftDownloader(HttpClient.newHttpClient(), 4)
            .downloadLibraries(metadata, workspace);

        assertEquals(4, fetched, "the corrupt entry is discarded and re-fetched, not trusted");
        for (var library : libraries) {
            var target = workspace.librariesDirectory().resolve(library.artifact().path());
            assertTrue(Files.isRegularFile(target), "missing " + target);
            assertEquals(library.artifact().sha1(), MojangMetadata.sha1(target),
                "every library that lands in the workspace must verify");
        }
    }

    private MojangMetadata.Library library(String coordinates, String path, byte[] body) {
        serve("/" + path, body);
        return new MojangMetadata.Library(coordinates, coordinates,
            new MojangMetadata.Artifact(path, url("/" + path), sha1(body), body.length));
    }
}
