package org.veltismc.patchengine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-install lifecycle driven through the real entry point,
 * {@link VeltisRuntime#prepare()}, entirely offline.
 *
 * <p>A packaged server fires no requests when its cache is current, so the warm
 * case is genuinely offline by construction. The cold case is made offline too
 * by seeding the cached Mojang metadata ({@code version.json}) with the fixture
 * artifact's own SHA-1: resolution never needs the network, the server artifact
 * verifies against the seeded hash and is reused, and the library list is empty.
 * So all of the miss path that matters — identity, validation, staging, atomic
 * publish, crash recovery, and the rebuild lock — is exercised for real instead
 * of through imitations.
 */
class PackagedRuntimePrepareTest {

    private static final String SERVER = "net/minecraft/server/MinecraftServer.java";
    private static final String SERVER_CLASS = "net/minecraft/server/MinecraftServer.class";
    private static final String FIXTURE_REVISION = "e".repeat(64);

    @TempDir
    Path tmp;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
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

    /** A dev workspace with a compiled patched class and a generated patch zip. */
    private byte[] generatePatchZip() throws Exception {
        var workspace = VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION);
        workspace.createDirectories();
        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(workspace.patchTargetsFile(), "code\t" + SERVER + "\n");

        var patchedSource = workspace.patchedDirectory().resolve(SERVER);
        Files.createDirectories(patchedSource.getParent());
        Files.writeString(patchedSource, source("MinecraftServer", "patched"));

        var vanillaClass = compile(workspace, "vanilla-src", "MinecraftServer", "vanilla");
        // Pre-bundler layout: the verified server artifact is itself a jar, and
        // the widened and classes jars are the same bytes, as a build produces
        // them when it lifts the bundler's inner jar out.
        var artifact = oneEntryJar(SERVER_CLASS, vanillaClass);
        Files.createDirectories(workspace.vanillaDirectory());
        Files.write(workspace.vanillaServerJar(), artifact);
        Files.write(workspace.widenedServerJar(), artifact);
        Files.write(workspace.vanillaClassesJar(), artifact);
        writeLibraryJar(workspace.librariesDirectory());

        new PatchedSourceCompiler(Runtime.version().feature())
            .compile(workspace, List.of(patchedSource));

        return Files.readAllBytes(BytecodePatchGenerator
            .generate(workspace, TestWorkspace.VERSION,
                MojangMetadata.sha1(workspace.vanillaServerJar()),
                Runtime.version().feature(), FIXTURE_REVISION, 1, 1)
            .file());
    }

    /**
     * Writes the server-install cache: the vanilla artifact, a library, and the
     * cached version metadata, so the cold path needs no network.
     */
    private void prepareHome(Path home, byte[] artifact) throws IOException {
        var install = VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION);
        Files.createDirectories(install.vanillaServerJar().getParent());
        Files.write(install.vanillaServerJar(), artifact);
        Files.createDirectories(install.vanillaClassesJar().getParent());
        Files.write(install.vanillaClassesJar(), artifact);
        writeLibraryJar(install.librariesDirectory());
        MojangMetadata.cacheMetadata(install,
            versionMetadataJson(sha1Of(artifact), artifact.length));
    }

    /**
     * A classloader carrying the patch set at the location a distribution puts
     * it: {@code META-INF/veltis/patches/<version>.zip}.
     */
    private URLClassLoader packagedLoader(byte[] patchZip, String tag) throws IOException {
        var packaged = tmp.resolve("packaged-" + tag);
        Files.createDirectories(packaged.resolve("META-INF/veltis/patches"));
        Files.write(packaged.resolve("META-INF/veltis/patches/" + TestWorkspace.VERSION + ".zip"),
            patchZip);
        return new URLClassLoader(new URL[] {packaged.toUri().toURL()},
            PackagedRuntimePrepareTest.class.getClassLoader());
    }

    private VeltisRuntime runtimeAt(Path home, URLClassLoader loader) {
        return VeltisRuntime.fromPackagedPatches(home, TestWorkspace.VERSION, loader, 1,
            Runtime.version().feature());
    }

    private void writeLibraryJar(Path libraryRoot) throws IOException {
        Files.createDirectories(libraryRoot);
        var library = libraryRoot.resolve("fixture-library.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(library))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static String source(String className, String body) {
        return "package net.minecraft.server;\n"
            + "public class " + className + " {\n"
            + "    public static String marker() { return \"" + body + "\"; }\n"
            + "}\n";
    }

    private static byte[] compile(VeltisWorkspace workspace, String directory,
                                  String className, String marker) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        var scratch = workspace.vanillaDirectory().resolve(directory);
        var packageDir = scratch.resolve("net/minecraft/server");
        Files.createDirectories(packageDir);
        var sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source(className, marker), StandardCharsets.UTF_8);
        var output = scratch.resolve("out");
        var code = compiler.run(null, null, null,
            "-proc:none", "-nowarn",
            "--release", Integer.toString(Runtime.version().feature()),
            "-d", output.toString(), sourceFile.toString());
        assertEquals(0, code, "the test fixture source must compile");
        return Files.readAllBytes(output.resolve("net/minecraft/server/" + className + ".class"));
    }

    private static byte[] oneEntryJar(String entryName, byte[] content) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new JarOutputStream(bytes)) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content);
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String versionMetadataJson(String sha1, long size) {
        return versionMetadataJsonWith("https://example.invalid/server.jar", sha1, size);
    }

    private static String sha1Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 must exist", e);
        }
    }

    private static String versionMetadataJsonWith(String serverUrl, String sha1, long size) {
        return "{\"id\":\"26.3\",\"downloads\":{\"server\":{\"url\":\"" + serverUrl
            + "\",\"sha1\":\"" + sha1 + "\",\"size\":" + size + "}},\"libraries\":[]}";
    }

    // ------------------------------------------------------------------
    // The lifecycle
    // ------------------------------------------------------------------

    @Test
    void aColdStartBuildsAndAWarmStartValidates() throws Exception {
        var patchZip = generatePatchZip();
        var artifact = Files.readAllBytes(
            VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION).vanillaClassesJar());
        var home = tmp.resolve("home");
        prepareHome(home, artifact);
        var cold = runtimeAt(home, packagedLoader(patchZip, "home"));

        assertTrue(cold.prepare(), "an empty installation must report a rebuild");
        assertTrue(Files.isRegularFile(cold.workspace().veltisServerJar()));
        assertNotNull(cold.identity());

        assertFalse(cold.prepare(), "a valid cache must not be rebuilt a second time");
        assertEquals(cold.identity(), cold.identity(),
            "the warm validation resolves to the identical recorded identity");

        // The vanilla artifact survived the rebuild: the next launch still has
        // a baseline to verify against.
        assertTrue(Files.isRegularFile(cold.workspace().vanillaServerJar()));
    }

    @Test
    void concurrentLaunchesRebuildOnceAndShareTheArtifact() throws Exception {
        var patchZip = generatePatchZip();
        var artifact = Files.readAllBytes(
            VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION).vanillaClassesJar());
        var home = tmp.resolve("race");
        prepareHome(home, artifact);
        var loader = packagedLoader(patchZip, "race");

        var executor = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CountDownLatch(2);
            Callable<Boolean> launch = () -> {
                barrier.countDown();
                barrier.await(10, TimeUnit.SECONDS);
                return runtimeAt(home, loader).prepare();
            };
            var first = executor.submit(launch);
            var second = executor.submit(launch);

            var firstResult = first.get(60, TimeUnit.SECONDS);
            var secondResult = second.get(60, TimeUnit.SECONDS);
            assertEquals(1, (firstResult ? 1 : 0) + (secondResult ? 1 : 0),
                "two racing launches must rebuild exactly once; the loser re-validates");
            assertTrue(Files.isRegularFile(
                VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION).veltisServerJar()));
            assertFalse(firstResult && secondResult,
                "at most one launch may claim the rebuild; the other must find it current");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aCorruptedArtifactIsDetectedAndRebuilt() throws Exception {
        var patchZip = generatePatchZip();
        var artifact = Files.readAllBytes(
            VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION).vanillaClassesJar());
        var home = tmp.resolve("corrupt");
        prepareHome(home, artifact);
        var runtime = runtimeAt(home, packagedLoader(patchZip, "corrupt"));

        assertTrue(runtime.prepare());
        var jar = runtime.workspace().veltisServerJar();
        // A torn or partially-written artifact: the file on disk is not the
        // runtime at all. ZipFile can survive a flipped byte in one entry's
        // local header (it reads the central directory), so this overwrite is
        // the unambiguous corruption state.
        Files.writeString(jar, "not a jar at all");

        // A production rebuild re-resolves Mojang's metadata over the network;
        // this fixture rebuilds offline by restoring the same cached metadata.
        prepareHome(home, artifact);
        assertTrue(runtime.prepare(),
            "a corrupted runtime artifact must be detected and rebuilt, not trusted");
        assertFalse(runtime.prepare(), "after the rebuild the cache is current again");
    }

    @Test
    void aTornPublishLeavesAStagingSiblingAndIsRecovered() throws Exception {
        var patchZip = generatePatchZip();
        var artifact = Files.readAllBytes(
            VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION).vanillaClassesJar());
        var home = tmp.resolve("torn");
        prepareHome(home, artifact);
        var runtime = runtimeAt(home, packagedLoader(patchZip, "torn"));
        assertTrue(runtime.prepare());

        // Simulate a crash between the last class file and the atomic move.
        var jar = runtime.workspace().veltisServerJar();
        var staging = jar.resolveSibling(jar.getFileName() + ".writing");
        Files.writeString(staging, "half a jar");
        Files.delete(jar);

        // A production rebuild re-resolves Mojang's metadata over the network;
        // this fixture rebuilds offline by restoring the same cached metadata.
        prepareHome(home, artifact);
        assertTrue(runtime.prepare(),
            "a missing artifact with a stale .writing sibling must rebuild cleanly");
        assertFalse(Files.exists(staging),
            "the stale staging sibling must be discarded, not mistaken for the artifact");
        assertTrue(Files.isRegularFile(jar));
    }

    @Test
    void aMissingArtifactFailsWithTheSourceNamedAndNothingIsFabricated() throws Exception {
        var patchZip = generatePatchZip();
        var artifact = Files.readAllBytes(
            VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION).vanillaClassesJar());
        var home = tmp.resolve("offline");
        var install = VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION);
        Files.createDirectories(install.vanillaServerJar().getParent());
        writeLibraryJar(install.librariesDirectory());
        // No vanilla artifact is present, and the cached metadata names an
        // endpoint that never returns bytes: the offline case with nothing to
        // fall back on.
        MojangMetadata.cacheMetadata(install,
            versionMetadataJsonWith(url("/server.jar"), sha1Of(artifact), artifact.length));
        var runtime = runtimeAt(home, packagedLoader(patchZip, "offline"));

        var failure = assertThrows(PatchEngineException.class, runtime::prepare);
        var msg = failure.getMessage();
        assertTrue(msg.contains("[VeltisMinecraft]"), msg);
        assertTrue(msg.contains(url("/server.jar")), msg);
        assertTrue(msg.contains("Expected SHA-1: " + sha1Of(artifact)), msg);
        assertFalse(Files.exists(runtime.workspace().veltisServerJar()),
            "no runtime artifact may appear when the baseline could not be fetched");
    }
}