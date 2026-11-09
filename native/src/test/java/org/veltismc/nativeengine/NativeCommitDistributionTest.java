package org.veltismc.nativeengine;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCommitDistributionTest {

    private static final String COMMIT = "20421c2" + "0".repeat(33);
    private static final String PLATFORM = "linux-x86_64";
    private static final NativeConfig ENABLED = new NativeConfig(true, true, true, false, "");

    @TempDir
    Path home;

    private HttpServer server;
    private String baseUrl;
    private KeyPair keyPair;
    private final Map<String, byte[]> responses = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private boolean truncateArchive;

    @BeforeEach
    void startServer() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(path);
            }
            var body = responses.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else if (truncateArchive && path.endsWith(".zip")) {
                exchange.sendResponseHeaders(200, body.length + 256);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void installsOnlyTheMatchingPlatformArchiveAndReusesItOffline() throws Exception {
        var library = elfImage(0x3E);
        var archive = platformZip(PLATFORM, COMMIT, library, false, false);
        publish(COMMIT, PLATFORM, library, archive, false, null);

        var result = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
        assertEquals(List.of("transform"), result.modules());
        assertTrue(Files.isRegularFile(home.resolve("native").resolve(PLATFORM)
                .resolve("transform").resolve("libtransform.so")));
        assertTrue(Files.isRegularFile(home.resolve("native").resolve(PLATFORM)
                .resolve(NativeCommitInstaller.BUILD_INFO)));
        assertTrue(Files.isRegularFile(home.resolve("cache").resolve("native")
                .resolve(PLATFORM + "-" + COMMIT.substring(0, 7) + ".zip")));
        assertEquals(1, countRequestsEndingWith(".zip"));

        var cachedLibrary = home.resolve("native").resolve(PLATFORM)
                .resolve("transform").resolve("libtransform.so");
        Files.delete(cachedLibrary);
        var offline = new NativeDistributionConfig(true, true,
                NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5, baseUrl);
        var restored = ensure(PLATFORM, COMMIT, offline);
        assertEquals(NativeDistributionManager.Outcome.INSTALLED, restored.outcome(), restored.detail());
        assertEquals(1, countRequestsEndingWith(".zip"),
                "offline recovery must use the previously verified archive");
        assertTrue(Files.isRegularFile(cachedLibrary));

        var before = requestCount();
        var warm = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.REUSED, warm.outcome(), warm.detail());
        assertEquals(before, requestCount(), "a verified installation is reused without a network request");
    }

    @Test
    void rejectsInvalidSignatureAndZipChecksumBeforeInstallation() throws Exception {
        var library = elfImage(0x3E);
        var archive = platformZip(PLATFORM, COMMIT, library, false, false);
        publish(COMMIT, PLATFORM, library, archive, true, null);
        assertEquals(NativeDistributionManager.Outcome.FETCH_FAILED,
                ensure(PLATFORM, COMMIT, online()).outcome());
        assertFalse(Files.exists(home.resolve("native").resolve(PLATFORM)));

        responses.clear();
        synchronized (requests) {
            requests.clear();
        }
        publish(COMMIT, PLATFORM, library, archive, false, "f".repeat(64));
        var result = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, result.outcome(), result.detail());
        assertFalse(Files.exists(home.resolve("native").resolve(PLATFORM)
                .resolve("transform").resolve("libtransform.so")));
    }

    @Test
    void rejectsZipSlipUnexpectedRootAndWrongArchitecture() throws Exception {
        var library = elfImage(0x3E);
        var traversal = platformZip(PLATFORM, COMMIT, library, true, false);
        publish(COMMIT, PLATFORM, library, traversal, false, null);
        assertEquals(NativeDistributionManager.Outcome.INSTALL_FAILED,
                ensure(PLATFORM, COMMIT, online()).outcome());

        responses.clear();
        synchronized (requests) {
            requests.clear();
        }
        var wrongRoot = platformZip(PLATFORM, COMMIT, library, false, true);
        publish(COMMIT, PLATFORM, library, wrongRoot, false, null);
        assertEquals(NativeDistributionManager.Outcome.INSTALL_FAILED,
                ensure(PLATFORM, COMMIT, online()).outcome());

        responses.clear();
        synchronized (requests) {
            requests.clear();
        }
        var armLibrary = elfImage(0xB7);
        var wrongArchitecture = platformZip(PLATFORM, COMMIT, armLibrary, false, false);
        publish(COMMIT, PLATFORM, armLibrary, wrongArchitecture, false, null);
        var result = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.INSTALL_FAILED, result.outcome(), result.detail());
        assertTrue(result.detail().contains("aarch64") || result.detail().contains("architecture"),
                result.detail());
    }

    @Test
    void anInterruptedDownloadLeavesNoPartialArchiveAndDoesNotBlockStartup() throws Exception {
        var library = elfImage(0x3E);
        var archive = platformZip(PLATFORM, COMMIT, library, false, false);
        publish(COMMIT, PLATFORM, library, archive, false, null);
        truncateArchive = true;

        var result = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, result.outcome(), result.detail());
        try (var files = Files.walk(home.resolve("cache").resolve("native"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void missingExactCommitAndUnsupportedPlatformKeepJavaFallbackAvailable() {
        var missing = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.PLATFORM_ABSENT, missing.outcome(), missing.detail());
        var requestsBeforeUnsupported = requestCount();
        var unsupported = ensure("freebsd-x86_64", COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.UNSUPPORTED_PLATFORM, unsupported.outcome());
        assertEquals(requestsBeforeUnsupported, requestCount(),
                "unsupported platforms do not make additional release requests");
    }

    @Test
    void refusesAReleaseForAnotherCommit() throws Exception {
        var otherCommit = "a".repeat(40);
        var library = elfImage(0x3E);
        var archive = platformZip(PLATFORM, otherCommit, library, false, false);
        publish(otherCommit, PLATFORM, library, archive, false, null);

        var result = ensure(PLATFORM, COMMIT, online());
        assertEquals(NativeDistributionManager.Outcome.PLATFORM_ABSENT, result.outcome(), result.detail());
        assertTrue(result.detail().contains("no native release exists"), result.detail());
    }

    private NativeDistributionManager.Result ensure(String platform, String commit,
            NativeDistributionConfig config) {
        var result = NativeCommitDistributionManager.ensureInstalled(home, ENABLED, config,
                keyPair.getPublic(), commit, platform);
        return new NativeDistributionManager.Result(result.outcome(), result.platform(),
                result.detail(), result.modules());
    }

    private NativeDistributionConfig online() {
        return new NativeDistributionConfig(true, false,
                NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5, baseUrl);
    }

    private void publish(String commit, String platform, byte[] library, byte[] archive,
            boolean invalidSignature, String checksumOverride) throws Exception {
        var shortCommit = commit.substring(0, 7);
        var tag = "veltis-native-" + shortCommit;
        var assetName = platform + "-" + shortCommit + ".zip";
        var zipPath = "/assets/" + assetName;
        var manifestPath = "/assets/native-manifest.json";
        var signaturePath = "/assets/native-manifest.json.sig";
        responses.put(zipPath, archive);
        var manifest = commitManifest(commit, platform, assetName, zipPath, archive,
                library, checksumOverride);
        var signature = invalidSignature
                ? "invalid".getBytes(StandardCharsets.US_ASCII)
                : NativeManifest.sign(manifest, keyPair.getPrivate());
        responses.put(manifestPath, manifest);
        responses.put(signaturePath, signature);
        var assets = List.of(
                releaseAsset("native-manifest.json", manifestPath, manifest.length),
                releaseAsset("native-manifest.json.sig", signaturePath, signature.length),
                releaseAsset(assetName, zipPath, archive.length));
        var release = new com.google.gson.JsonObject();
        release.addProperty("tag_name", tag);
        release.add("assets", new com.google.gson.Gson().toJsonTree(assets));
        responses.put("/tags/" + tag, release.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> releaseAsset(String name, String path, int size) {
        return Map.of(
                "name", name,
                "browser_download_url", baseUrl + path.substring(1),
                "size", size);
    }

    private byte[] commitManifest(String commit, String platform, String asset, String assetPath,
            byte[] archive, byte[] library, String checksumOverride) throws Exception {
        var parts = platform.split("-", 2);
        var module = new com.google.gson.JsonObject();
        module.addProperty("name", "transform");
        module.addProperty("library", "libtransform.so");
        module.addProperty("sha256", sha256(library));
        module.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        module.addProperty("nativeVersion", "1.0.0");

        var platformEntry = new com.google.gson.JsonObject();
        platformEntry.addProperty("platform", platform);
        platformEntry.addProperty("os", parts[0]);
        platformEntry.addProperty("architecture", parts[1]);
        platformEntry.addProperty("asset", asset);
        platformEntry.addProperty("downloadUrl", baseUrl + assetPath.substring(1));
        platformEntry.addProperty("sha256", checksumOverride == null ? sha256(archive) : checksumOverride);
        platformEntry.addProperty("sizeBytes", archive.length);
        platformEntry.addProperty("verified", true);
        platformEntry.add("modules", new com.google.gson.Gson().toJsonTree(List.of(module)));

        var platforms = new com.google.gson.JsonObject();
        platforms.add(platform, platformEntry);
        var root = new com.google.gson.JsonObject();
        root.addProperty("schemaVersion", NativeCommitManifest.SCHEMA_VERSION);
        root.addProperty("kind", NativeCommitManifest.KIND);
        root.addProperty("releaseTag", "veltis-native-" + commit.substring(0, 7));
        root.addProperty("commit", commit);
        root.addProperty("shortCommit", commit.substring(0, 7));
        root.add("platforms", platforms);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] platformZip(String platform, String commit, byte[] library,
            boolean zipSlip, boolean wrongRoot) throws Exception {
        var parts = platform.split("-", 2);
        var module = Map.of(
                "name", "transform",
                "library", "libtransform.so",
                "sha256", sha256(library),
                "abiVersion", NativeLoader.EXPECTED_ABI_VERSION,
                "nativeVersion", "1.0.0");
        var info = new com.google.gson.JsonObject();
        info.addProperty("schemaVersion", 1);
        info.addProperty("commit", commit);
        info.addProperty("shortCommit", commit.substring(0, 7));
        info.addProperty("platform", platform);
        info.addProperty("os", parts[0]);
        info.addProperty("architecture", parts[1]);
        info.add("modules", new com.google.gson.Gson().toJsonTree(List.of(module)));

        var root = wrongRoot ? "other-platform" : platform;
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            put(zip, root + "/", new byte[0]);
            put(zip, root + "/transform/", new byte[0]);
            put(zip, root + "/transform/libtransform.so", library);
            put(zip, root + "/native-build-info.json",
                    (info + "\n").getBytes(StandardCharsets.UTF_8));
            if (zipSlip) {
                put(zip, platform + "/../../outside.txt", new byte[] {1});
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private int countRequestsEndingWith(String suffix) {
        synchronized (requests) {
            return (int) requests.stream().filter(path -> path.endsWith(suffix)).count();
        }
    }

    private int requestCount() {
        synchronized (requests) {
            return requests.size();
        }
    }

    private static byte[] elfImage(int machine) {
        var bytes = new byte[64];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[18] = (byte) (machine & 0xff);
        bytes[19] = (byte) ((machine >>> 8) & 0xff);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
