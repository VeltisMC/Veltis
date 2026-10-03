package org.veltismc.patchengine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, byte[]> routes = new LinkedHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            var body = routes.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
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

    private MojangMetadata.Library library(String coordinates, String path, byte[] body) {
        serve("/" + path, body);
        return new MojangMetadata.Library(coordinates, coordinates,
            new MojangMetadata.Artifact(path, url("/" + path), sha1(body), body.length));
    }
}
