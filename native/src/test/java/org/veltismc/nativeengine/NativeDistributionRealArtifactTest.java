package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CI-to-runtime chain, in one process, with the library this build actually
 * linked.
 *
 * <p>Everything else in this package proves the distribution against synthetic
 * container images, which is what makes the failure cases testable. That is not
 * the same claim as "a server started from a published release runs natively",
 * and only a real artifact can make it. Here the bytes are the DLL the CMake
 * build just produced: they are packaged the way the release packages them,
 * published through a signed manifest, downloaded by the manager, installed
 * under {@code <home>/native/<platform>/}, and then loaded through
 * {@link NativeLoader} and called through the FFM bindings.
 *
 * <p>The manifest here lists two platforms and the runtime requests one archive.
 * That is the selective-download rule stated at the level of a real release:
 * metadata for another platform is present, and it changes nothing.
 *
 * <p><b>What this does not prove.</b> The signature comes from a key generated
 * per run and handed to the package-private overload, because the private half
 * of the embedded anchor exists only as the CI secret. That split is deliberate
 * and each half is covered where it belongs:
 * {@code :veltis-native:generateNativeManifestKey} +
 * {@code :veltis-native:verifyNativeManifestKeyPairing} prove the shipped anchor
 * and the release secret are one pair, and {@code packageNativeDistribution}
 * reads its own manifest back through this very class before publishing.
 *
 * <p>Skipped when {@code veltis.native.test.library} names no file, so a
 * Java-only run stays green; {@code verifyNativeRelease} fails the build if that
 * skip happens in a lane that claims native support.
 */
class NativeDistributionRealArtifactTest {

    private static final NativeConfig NATIVE_ENABLED =
            new NativeConfig(true, true, true, false, "");

    @TempDir
    Path home;

    private HttpServer server;
    private String repository;
    private KeyPair keyPair;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> bodies = new LinkedHashMap<>();

    @BeforeEach
    void startRepository() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        requests.clear();
        bodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            requests.add(path);
            var body = bodies.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopRepository() {
        server.stop(0);
    }

    @Test
    void aPublishedRealLibraryIsDownloadedInstalledAndLoadedThroughFfm() throws Exception {
        var libraryBytes = Files.readAllBytes(realLibrary());
        var host = NativeLoader.platformKey();
        // Any recognised key that is not this one: the point is that a second
        // platform in the manifest must not become a second download.
        var foreign = host.startsWith("windows") ? "linux-x86_64" : "windows-x86_64";

        var hostZip = releaseZip(host, libraryBytes);
        var foreignZip = releaseZip(foreign, libraryBytes);
        var artifacts = new JsonObject();
        artifacts.add(host, artifactFor(host, libraryBytes, hostZip));
        artifacts.add(foreign, artifactFor(foreign, libraryBytes, foreignZip));
        var manifest = manifestBytes(artifacts);
        bodies.put("/" + NativeManifest.MANIFEST_ASSET, manifest);
        bodies.put("/" + NativeManifest.SIGNATURE_ASSET,
                NativeManifest.sign(manifest, keyPair.getPrivate()));
        bodies.put("/veltis-native-" + host + ".zip", hostZip);
        bodies.put("/veltis-native-" + foreign + ".zip", foreignZip);

        var result = NativeDistributionManager.ensureInstalled(home, NATIVE_ENABLED,
                new NativeDistributionConfig(true, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 15, repository),
                keyPair.getPublic(), host);

        assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
        assertEquals(List.of("/" + NativeManifest.MANIFEST_ASSET,
                        "/" + NativeManifest.SIGNATURE_ASSET,
                        "/veltis-native-" + host + ".zip"),
                List.copyOf(requests),
                "the manifest published two platforms; this runtime must fetch exactly its own");

        var installed = home.resolve("native").resolve(host)
                .resolve(NativeLoader.libraryFileName(host));
        assertArrayEquals(libraryBytes, Files.readAllBytes(installed),
                "the installed bytes must be the bytes the release published, unmodified");

        // From here down this is the server's own startup path: the installed
        // file is what NativeLoader is pointed at, and the assertions are about
        // the C++ implementation answering through the bindings.
        var config = new NativeConfig(true, true, true, false, installed.toString());
        var load = NativeLoader.tryLoad(config);
        assertNotNull(load.library(), () -> "the installed library did not load: " + load.status().summary());
        try (var loaded = load.library()) {
            assertEquals(0, loaded.initialize(), loaded.lastError());
            assertTrue(loaded.isInitialized());
            assertEquals(0, loaded.selfTest(), loaded.lastError());

            var input = new int[] {-7, 0, 7, 1234, Integer.MAX_VALUE, Integer.MIN_VALUE};
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                var in = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_INT, input);
                var out = arena.allocate(java.lang.foreign.ValueLayout.JAVA_INT, input.length);
                assertEquals(0, loaded.transform(in, input.length, 42, out, input.length),
                        loaded.lastError());
                var actual = new int[input.length];
                for (var i = 0; i < input.length; i++) {
                    actual[i] = (int) out.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i);
                }
                assertArrayEquals(NativeEngine.javaTransform(input, 42), actual,
                        "the downloaded library must compute what this server expects");
            }
        }

        // A restart is the only way an installed library is ever replaced, so
        // the installation has to survive a second load of the same bytes.
        var restart = NativeLoader.tryLoad(config);
        assertNotNull(restart.library(), () -> "the installed library did not load on restart: "
                + restart.status().summary());
        try (var loaded = restart.library()) {
            assertEquals(0, loaded.initialize(), loaded.lastError());
            assertEquals(0, loaded.selfTest(), loaded.lastError());
        }
    }

    /** The library the Gradle build produced for this host, when there is one. */
    private static Path realLibrary() {
        var value = System.getProperty("veltis.native.test.library");
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                "veltis.native.test.library is not configured");
        var path = Path.of(value);
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "native library has not been built: " + path + " (run :veltis-native:buildNative)");
        return path;
    }

    // ------------------------------------------------------------------
    // The published shape: archive, inner manifest, artifact entry
    // ------------------------------------------------------------------

    /** The three entries a release archive carries, and nothing else. */
    private static byte[] releaseZip(String platform, byte[] library) throws Exception {
        var inner = innerManifest(platform, library);
        var name = NativeLoader.libraryFileName(platform);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            put(zip, name, library);
            put(zip, "veltis-native-manifest.json", inner);
            put(zip, "SHA256SUMS", (sha256(library) + "  " + name + "\n"
                    + sha256(inner) + "  veltis-native-manifest.json\n").getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static byte[] innerManifest(String platform, byte[] library) {
        var inner = new JsonObject();
        inner.addProperty("schemaVersion", 1);
        inner.addProperty("platform", platform);
        inner.addProperty("library", NativeLoader.libraryFileName(platform));
        inner.addProperty("sha256", sha256(library));
        inner.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        inner.addProperty("version", "1.0.0");
        return (inner + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject artifactFor(String platform, byte[] library, byte[] zip) {
        var parts = platform.split("-", 2);
        var artifact = new JsonObject();
        artifact.addProperty("platform", platform);
        artifact.addProperty("os", parts[0]);
        artifact.addProperty("architecture", parts[1]);
        artifact.addProperty("asset", "veltis-native-" + platform + ".zip");
        artifact.addProperty("sha256", sha256(zip));
        artifact.addProperty("sizeBytes", zip.length);
        artifact.addProperty("library", NativeLoader.libraryFileName(platform));
        artifact.addProperty("librarySha256", sha256(library));
        artifact.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        artifact.addProperty("nativeVersion", "1.0.0");
        artifact.addProperty("verified", true);
        artifact.addProperty("verification", "host");
        return artifact;
    }

    private static byte[] manifestBytes(JsonObject artifacts) {
        var root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("kind", "veltis-native-distribution-manifest");
        root.addProperty("release", "veltis-native-v1.0.0");
        root.addProperty("nativeVersion", "1.0.0");
        root.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        root.addProperty("generatedAt", "2026-11-05T00:00:00Z");
        root.addProperty("commit", "0".repeat(40));
        root.add("artifacts", artifacts);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Guards the assertion above: without two entries the test would prove nothing. */
    @Test
    void thePublicationReallyDidOfferMoreThanOnePlatform() throws Exception {
        var libraryBytes = Files.readAllBytes(realLibrary());
        var host = NativeLoader.platformKey();
        var foreign = host.startsWith("windows") ? "linux-x86_64" : "windows-x86_64";
        var artifacts = new JsonObject();
        artifacts.add(host, artifactFor(host, libraryBytes, releaseZip(host, libraryBytes)));
        artifacts.add(foreign, artifactFor(foreign, libraryBytes, releaseZip(foreign, libraryBytes)));
        assertTrue(artifacts.size() > 1,
                "a single-platform manifest cannot demonstrate selective downloading");
        assertTrue(NativeManifest.verifyAndParse(manifestBytes(artifacts),
                NativeManifest.sign(manifestBytes(artifacts), keyPair.getPrivate()),
                keyPair.getPublic()).artifacts().size() == 2,
                "both entries must parse, or the download test is choosing between nothing");
    }
}
