package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selective distribution end to end, against a local HTTP server that
 * records every request.
 *
 * <p>The load-bearing assertion of this suite is the request log: a run must
 * fetch the manifest, its signature and <em>exactly one</em> archive — the one
 * for the platform being installed — and must never request another
 * platform's binary, retry in a loop, or touch the network when the policy,
 * the offline flag or an already-verified installation says it must not.
 *
 * <p>No test here reaches GitHub: the repository is always
 * {@code http://127.0.0.1:<port>/}, which the transport accepts only because
 * no packet leaves this machine.
 */
class NativeDistributionManagerTest {

    private static final List<String> ALL_PLATFORMS = List.of(
            "windows-x86_64", "windows-aarch64",
            "linux-x86_64", "linux-aarch64",
            "macos-x86_64", "macos-aarch64");

    private static final NativeConfig NATIVE_ENABLED =
            new NativeConfig(true, true, true, false, "");

    @TempDir
    Path home;

    private HttpServer server;
    private String repository;
    private KeyPair keyPair;

    /** Every path the server was asked for, in order. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> bodies = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private final Map<String, String> locations = new HashMap<>();

    @BeforeEach
    void startRepository() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        requests.clear();
        bodies.clear();
        statuses.clear();
        locations.clear();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            requests.add(path);
            var status = statuses.getOrDefault(path, bodies.containsKey(path) ? 200 : 404);
            var location = locations.get(path);
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            var body = bodies.get(path);
            if (status != 200 || body == null) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopRepository() {
        server.stop(0);
    }

    // ------------------------------------------------------------------
    // Platform selection: six keys, and nothing outside them
    // ------------------------------------------------------------------

    @Test
    void eachOfTheSixPlatformsDownloadsExactlyItsOwnArchive() throws Exception {
        var publication = publish(ALL_PLATFORMS);

        for (var platform : ALL_PLATFORMS) {
            requests.clear();
            var runHome = home.resolve(platform);

            var result = run(runHome, platform, policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

            assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
            assertEquals(List.of(
                    "/" + NativeManifest.MANIFEST_ASSET,
                    "/" + NativeManifest.SIGNATURE_ASSET,
                    "/veltis-native-transform-" + platform + ".zip"),
                    List.copyOf(requests),
                    platform + " must fetch one manifest pair and one archive, never another platform's");
            assertTrue(Files.isRegularFile(
                            runHome.resolve("native").resolve(platform)
                                    .resolve(NativeLoader.libraryFileName(platform))),
                    platform + "'s library must be installed under its own directory");
            assertArrayEquals(libraryFor(platform), Files.readAllBytes(
                    runHome.resolve("native").resolve(platform)
                            .resolve(NativeLoader.libraryFileName(platform))));
        }
        assertEquals(ALL_PLATFORMS.size(), publication.zips.size(),
                "the publication must cover every platform this loop exercises");
    }

    @Test
    void theArchitectureIsTakenFromThePlatformKeyNotFromTheOperatingSystem() throws Exception {
        // A manifest that publishes both Linux architectures: the x86-64 run
        // must take the x86-64 archive even though the arm64 one is present,
        // which is the JVM-mismatch case in one line.
        publish(List.of("linux-x86_64", "linux-aarch64"));
        var runHome = home.resolve("x86-run");

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
        assertEquals(List.of("/veltis-native-transform-linux-x86_64.zip"),
                archiveRequests());
        assertFalse(requests.contains("/veltis-native-transform-linux-aarch64.zip"));
    }

    @Test
    void aPlatformOutsideTheSixIsNeverGivenARequest() throws Exception {
        var config = policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING);

        var freebsd = run(home.resolve("freebsd"), "freebsd-x86_64", config);
        var riscv = run(home.resolve("riscv"), "linux-riscv64", config);

        assertEquals(NativeDistributionManager.Outcome.UNSUPPORTED_PLATFORM, freebsd.outcome());
        assertEquals(NativeDistributionManager.Outcome.UNSUPPORTED_PLATFORM, riscv.outcome());
        assertTrue(freebsd.detail().contains("linux-x86_64"), "the message must list what IS supported");
        assertTrue(requests.isEmpty(), "an unsupported platform must not touch the network");
        assertTrue(freebsd.shouldReportSkip(),
                "an unsupported platform must be reported to the operator as a skip");
    }

    @Test
    void aReleaseThatSkippedThisPlatformIsReportedNotDownloaded() throws Exception {
        publish(List.of("linux-x86_64"));

        var result = run(home.resolve("mac"), "macos-aarch64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.PLATFORM_ABSENT, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("macos-aarch64"));
        assertTrue(archiveRequests().isEmpty(), "a skipped platform downloads nothing");
        assertTrue(result.shouldReportSkip(), "the operator must be told, as a skip line");
    }

    @Test
    void anArtifactThatWasNotVerifiedOnItsHostIsNeverInstalled() throws Exception {
        publish(List.of("linux-x86_64"), artifact ->
                artifact.addProperty("verification", "cross"));

        var result = run(home.resolve("cross"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.PLATFORM_ABSENT, result.outcome(),
                result.detail());
        assertTrue(archiveRequests().isEmpty(),
                "a cross-compiled artifact must never be downloaded for runtime use");
    }

    // ------------------------------------------------------------------
    // Switches: nothing is fetched until an operator asks for it
    // ------------------------------------------------------------------

    @Test
    void everyDisabledSwitchMakesZeroRequests() throws Exception {
        publish(ALL_PLATFORMS);

        var off = run(home.resolve("off"), "linux-x86_64",
                new NativeDistributionConfig(false, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5, repository));
        var masterOff = run(home.resolve("master"), "linux-x86_64",
                new NativeDistributionConfig(true, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5, repository),
                new NativeConfig(false, true, true, false, ""));
        var pinned = run(home.resolve("pinned"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING),
                new NativeConfig(true, true, true, false,
                        home.resolve("some").resolve("lib.so").toString()));

        assertEquals(NativeDistributionManager.Outcome.DISABLED, off.outcome());
        assertEquals(NativeDistributionManager.Outcome.DISABLED, masterOff.outcome());
        assertEquals(NativeDistributionManager.Outcome.DISABLED, pinned.outcome());
        assertTrue(pinned.detail().contains(NativeConfig.PATH_PROPERTY));
        assertTrue(requests.isEmpty(), "a disabled distribution must not open a connection");
    }

    // ------------------------------------------------------------------
    // Cache and update policy
    // ------------------------------------------------------------------

    @Test
    void ifMissingInstallsOnceAndThenReusesWithoutASecondRequest() throws Exception {
        publish(ALL_PLATFORMS);
        var runHome = home.resolve("warm");

        var first = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));
        assertEquals(NativeDistributionManager.Outcome.INSTALLED, first.outcome(), first.detail());
        var afterFirst = List.copyOf(requests);

        var second = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.REUSED, second.outcome(), second.detail());
        assertEquals(afterFirst, List.copyOf(requests),
                "a warm installation must perform zero requests under if-missing");
        assertTrue(second.installed() == false);
        assertFalse(second.shouldReportSkip());
    }

    @Test
    void aSignatureValidManifestCacheAnswersIfMissingWithNoManifestRequest() throws Exception {
        var publication = publish(ALL_PLATFORMS);
        var runHome = home.resolve("cached");
        seedCache(runHome, publication);

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
        assertEquals(List.of("/veltis-native-transform-linux-x86_64.zip"), List.copyOf(requests),
                "a valid cache must replace the manifest fetch, not sit beside it");
    }

    @Test
    void alwaysRefetchesTheManifestButDoesNotRedownloadAMatchingArchive() throws Exception {
        publish(ALL_PLATFORMS);
        var runHome = home.resolve("always");
        run(runHome, "linux-x86_64", policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));
        requests.clear();

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_ALWAYS));

        assertEquals(NativeDistributionManager.Outcome.REUSED, result.outcome(), result.detail());
        assertEquals(List.of(
                "/" + NativeManifest.MANIFEST_ASSET,
                "/" + NativeManifest.SIGNATURE_ASSET),
                List.copyOf(requests),
                "always re-reads the manifest; identical published bytes need no archive");
    }

    @Test
    void alwaysFallsBackToTheCachedManifestWhenTheRefreshFails() throws Exception {
        var publication = publish(ALL_PLATFORMS);
        var runHome = home.resolve("fallback");
        run(runHome, "linux-x86_64", policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));
        seedCache(runHome, publication);
        requests.clear();
        statuses.put("/" + NativeManifest.MANIFEST_ASSET, 500);

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_ALWAYS));

        assertEquals(NativeDistributionManager.Outcome.REUSED, result.outcome(), result.detail());
        assertEquals(List.of("/" + NativeManifest.MANIFEST_ASSET), List.copyOf(requests),
                "the failed refresh is the only request; the cache answered the rest");
        assertTrue(archiveRequests().isEmpty());
    }

    @Test
    void neverAndOfflinePerformZeroRequestsInEveryCombination() throws Exception {
        publish(ALL_PLATFORMS);
        var configured = home.resolve("configured");

        var neverAlone = run(home.resolve("never"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_NEVER));
        var offlineAlone = run(home.resolve("offline"), "linux-x86_64",
                offlineConfig());
        assertEquals(NativeDistributionManager.Outcome.NO_NETWORK, neverAlone.outcome(),
                neverAlone.detail());
        assertEquals(NativeDistributionManager.Outcome.OFFLINE, offlineAlone.outcome(),
                offlineAlone.detail());
        assertTrue(requests.isEmpty(), "'never' and 'offline' with nothing installed fetch nothing");

        // A verified installation short-circuits both: nothing to ask for.
        run(configured, "linux-x86_64", policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));
        requests.clear();
        var neverWarm = run(configured, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_NEVER));
        var offlineWarm = run(configured, "linux-x86_64", offlineConfig());
        assertEquals(NativeDistributionManager.Outcome.REUSED, neverWarm.outcome());
        assertEquals(NativeDistributionManager.Outcome.REUSED, offlineWarm.outcome());
        assertTrue(requests.isEmpty(), "a warm installation needs no network under any policy");
    }

    // ------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------

    @Test
    void anInvalidRepositoryIsRefusedBeforeAnyRequest() throws Exception {
        publish(ALL_PLATFORMS);

        var plainHttp = run(home.resolve("http"), "linux-x86_64",
                new NativeDistributionConfig(true, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5,
                        "http://example.com/veltis"));
        var ftp = run(home.resolve("ftp"), "linux-x86_64",
                new NativeDistributionConfig(true, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5,
                        "ftp://example.com/veltis"));
        var nonsense = run(home.resolve("nonsense"), "linux-x86_64",
                new NativeDistributionConfig(true, false,
                        NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5,
                        "not a url"));

        assertEquals(NativeDistributionManager.Outcome.INVALID_REPOSITORY, plainHttp.outcome(),
                plainHttp.detail());
        assertEquals(NativeDistributionManager.Outcome.INVALID_REPOSITORY, ftp.outcome());
        assertEquals(NativeDistributionManager.Outcome.INVALID_REPOSITORY, nonsense.outcome());
        assertTrue(requests.isEmpty(), "a refused repository URL must not be contacted");
    }

    @Test
    void aTamperedManifestIsRefusedBeforeAnyDownload() throws Exception {
        var publication = publish(ALL_PLATFORMS);
        // Same signature, different bytes: the classic manifest-swap attempt.
        var tampered = Arrays.copyOf(publication.manifest, publication.manifest.length);
        var text = new String(tampered, StandardCharsets.UTF_8).replace("1.0.0", "9.9.9");
        tampered = text.getBytes(StandardCharsets.UTF_8);
        bodies.put("/" + NativeManifest.MANIFEST_ASSET, tampered);

        var result = run(home.resolve("tampered"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.FETCH_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("does not verify"), result.detail());
        assertTrue(archiveRequests().isEmpty(), "no archive may be fetched on an untrusted manifest");
    }

    @Test
    void aManifestSignedByAnotherKeyIsRefused() throws Exception {
        publish(ALL_PLATFORMS);
        var otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var manifest = bodies.get("/" + NativeManifest.MANIFEST_ASSET);
        bodies.put("/" + NativeManifest.SIGNATURE_ASSET,
                NativeManifest.sign(manifest, otherPair.getPrivate()));

        var result = run(home.resolve("wrongkey"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.FETCH_FAILED, result.outcome(),
                result.detail());
        assertTrue(archiveRequests().isEmpty());
    }

    @Test
    void aRedirectToANonOfficialHostIsRefusedMidFlight() throws Exception {
        publish(ALL_PLATFORMS);
        statuses.put("/" + NativeManifest.MANIFEST_ASSET, 302);
        locations.put("/" + NativeManifest.MANIFEST_ASSET, "http://evil.example/native-manifest.json");

        var result = run(home.resolve("redirect"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.FETCH_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("evil.example"), result.detail());
        assertEquals(List.of("/" + NativeManifest.MANIFEST_ASSET), List.copyOf(requests),
                "the redirect target must be refused before any request is sent to it");
    }

    @Test
    void anOversizedManifestIsRefusedWhileItIsBeingRead() throws Exception {
        bodies.put("/" + NativeManifest.MANIFEST_ASSET,
                new byte[(int) NativeManifest.MAX_MANIFEST_BYTES + 1]);

        var result = run(home.resolve("huge"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.FETCH_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("limit"), result.detail());
        assertTrue(archiveRequests().isEmpty());
    }

    @Test
    void aMissingArchiveIsAFailedDownloadNotAnInstall() throws Exception {
        publish(List.of("linux-x86_64"));
        bodies.remove("/veltis-native-transform-linux-x86_64.zip");

        var result = run(home.resolve("gone"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("404"), result.detail());
        assertFalse(Files.exists(home.resolve("gone").resolve("native").resolve("linux-x86_64")
                .resolve("libtransform.so")));
    }

    @Test
    void anArchiveOfTheWrongSizeIsRefusedDuringDownload() throws Exception {
        publish(List.of("linux-x86_64"));
        var zip = bodies.get("/veltis-native-transform-linux-x86_64.zip");
        var manifest = JsonParser.parseString(
                new String(bodies.get("/" + NativeManifest.MANIFEST_ASSET), StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.getAsJsonObject("artifacts").getAsJsonObject("transform/linux-x86_64")
                .addProperty("sizeBytes", zip.length + 5L);
        restampAndServe(manifest);

        var result = run(home.resolve("short"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.DOWNLOAD_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("manifest declares"), result.detail());
    }

    @Test
    void anArchiveWhoseDigestDisagreesWithTheManifestIsRefused() throws Exception {
        publish(List.of("linux-x86_64"));
        var manifest = JsonParser.parseString(
                new String(bodies.get("/" + NativeManifest.MANIFEST_ASSET), StandardCharsets.UTF_8))
                .getAsJsonObject();
        manifest.getAsJsonObject("artifacts").getAsJsonObject("transform/linux-x86_64")
                .addProperty("sha256", "c".repeat(64));
        restampAndServe(manifest);

        var result = run(home.resolve("liar"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.INSTALL_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("sha256"), result.detail());
        assertFalse(Files.exists(home.resolve("liar").resolve("native").resolve("linux-x86_64")
                .resolve("libtransform.so")),
                "a library that failed verification must not be installed");
    }

    @Test
    void aZipSlipEntryIsRefusedByTheAllowlistBeforeAnythingIsWritten() throws Exception {
        var library = libraryFor("linux-x86_64");
        var inner = innerManifest("linux-x86_64", library);
        var zip = zipOf(new LinkedHashMap<>(Map.of(
                NativeLoader.libraryFileName("linux-x86_64"), library,
                "veltis-native-manifest.json", inner)),
                Map.of("../../escape.txt", "pwned".getBytes(StandardCharsets.UTF_8)));
        serveSinglePlatform("linux-x86_64", zip, library);

        var result = run(home.resolve("slip"), "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.INSTALL_FAILED, result.outcome(),
                result.detail());
        assertTrue(result.detail().contains("unexpected entry"), result.detail());
        assertFalse(Files.exists(home.resolve("escape.txt")), "nothing may escape the install directory");
        assertFalse(Files.exists(home.resolve("slip").resolve("native").resolve("linux-x86_64")
                .resolve("libtransform.so")));
    }

    // ------------------------------------------------------------------
    // Concurrency, upgrades and adoption
    // ------------------------------------------------------------------

    @Test
    void aHeldLockIsReportedInsteadOfDownloadingAlongsideTheHolder() throws Exception {
        publish(ALL_PLATFORMS);
        var runHome = home.resolve("locked");

        var held = NativeDistributionInstaller.tryLock(runHome);
        var blocked = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.LOCKED, blocked.outcome(), blocked.detail());
        assertTrue(requests.isEmpty(), "a second server must not start fetching alongside the first");
        assertTrue(blocked.shouldReportSkip());

        held.close();
        requests.clear();
        var afterRelease = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));
        assertEquals(NativeDistributionManager.Outcome.INSTALLED, afterRelease.outcome(),
                afterRelease.detail());
    }

    @Test
    void anInstalledNewerVersionIsNeverDowngraded() throws Exception {
        publish(List.of("linux-x86_64"));
        var runHome = home.resolve("newer");
        var newerLibrary = Arrays.copyOf(libraryFor("linux-x86_64"),
                libraryFor("linux-x86_64").length + 16);
        seedInstalled(runHome, "linux-x86_64", newerLibrary, "2.0.0");
        requests.clear();

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_ALWAYS));

        assertEquals(NativeDistributionManager.Outcome.REUSED, result.outcome(), result.detail());
        assertTrue(result.detail().contains("downgrade"), result.detail());
        assertTrue(archiveRequests().isEmpty(), "a refused downgrade must not fetch the older archive");
        assertArrayEquals(newerLibrary, Files.readAllBytes(
                runHome                .resolve("native").resolve("linux-x86_64").resolve("libtransform.so")));
    }

    @Test
    void aPublishedIdenticalLibraryIsAdoptedAndRecordedWithoutDownloading() throws Exception {
        publish(List.of("linux-x86_64"));
        var runHome = home.resolve("adopt");
        var installDir = runHome.resolve("native").resolve("linux-x86_64");
        Files.createDirectories(installDir);
        // Present, byte-identical to the release, but unrecorded — an
        // operator's manual copy. It must be trusted and recorded, not
        // re-downloaded over itself.
        Files.write(installDir.resolve("libtransform.so"), libraryFor("linux-x86_64"));

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.REUSED, result.outcome(), result.detail());
        assertEquals(List.of(
                "/" + NativeManifest.MANIFEST_ASSET,
                "/" + NativeManifest.SIGNATURE_ASSET),
                List.copyOf(requests),
                "identical bytes need the manifest to confirm them, never the archive");
        assertTrue(Files.isRegularFile(installDir.resolve(NativeDistributionInstaller.INSTALL_RECORD)),
                "the adopted library must be recorded so the next run can verify it offline");
    }

    @Test
    void aCorruptedInstallationIsReplacedByThePublishedArchive() throws Exception {
        publish(List.of("linux-x86_64"));
        var runHome = home.resolve("corrupt");
        var installDir = runHome.resolve("native").resolve("linux-x86_64");
        Files.createDirectories(installDir);
        // Right file name, wrong bytes, no trustworthy record: the
        // distribution's job is to replace exactly this.
        Files.write(installDir.resolve("libtransform.so"),
                "not a library".getBytes(StandardCharsets.UTF_8));

        var result = run(runHome, "linux-x86_64",
                policy(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING));

        assertEquals(NativeDistributionManager.Outcome.INSTALLED, result.outcome(), result.detail());
        assertArrayEquals(libraryFor("linux-x86_64"), Files.readAllBytes(
                installDir.resolve("libtransform.so")));
    }

    // ------------------------------------------------------------------
    // Reporting and version arithmetic
    // ------------------------------------------------------------------

    @Test
    void onlyDisabledReusedAndInstalledAreSilent() {
        for (var outcome : NativeDistributionManager.Outcome.values()) {
            var result = new NativeDistributionManager.Result(outcome, "linux-x86_64", "because");
            var expected = switch (outcome) {
                case DISABLED, REUSED, INSTALLED -> false;
                default -> true;
            };
            assertEquals(expected, result.shouldReportSkip(), outcome.name());
        }
    }

    @Test
    void versionsCompareNumericallyAndRefuseToGuessAtGarbage() {
        assertEquals(0, NativeDistributionManager.compareVersions("1.0", "1.0.0"));
        assertEquals(0, NativeDistributionManager.compareVersions("1.0.0", "1.0.0"));
        assertTrue(NativeDistributionManager.compareVersions("1.0.1", "1.0.0") > 0);
        assertTrue(NativeDistributionManager.compareVersions("2.0.0", "1.9.9") > 0);
        assertTrue(NativeDistributionManager.compareVersions("1.0.0", "1.0.1") < 0);
        assertEquals(Integer.MIN_VALUE, NativeDistributionManager.compareVersions("banana", "1.0.0"));
        assertEquals(Integer.MIN_VALUE, NativeDistributionManager.compareVersions("1.0.0", null));
        assertEquals(Integer.MIN_VALUE, NativeDistributionManager.compareVersions("1.2.3.4.5", "1.0"));
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private NativeDistributionManager.Result run(Path runHome, String platform,
            NativeDistributionConfig config) {
        return run(runHome, platform, config, NATIVE_ENABLED);
    }

    private NativeDistributionManager.Result run(Path runHome, String platform,
            NativeDistributionConfig config, NativeConfig nativeConfig) {
        return NativeDistributionManager.ensureInstalled(runHome, nativeConfig, config,
                keyPair.getPublic(), platform);
    }

    private NativeDistributionConfig policy(String updatePolicy) {
        return new NativeDistributionConfig(true, false, updatePolicy, 5, repository);
    }

    private NativeDistributionConfig offlineConfig() {
        return new NativeDistributionConfig(true, true,
                NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, 5, repository);
    }

    private List<String> archiveRequests() {
        synchronized (requests) {
            return requests.stream().filter(path -> path.endsWith(".zip")).toList();
        }
    }

    /** Serves a signed manifest publishing {@code platforms} plus their zips. */
    private Publication publish(List<String> platforms) throws Exception {
        return publish(platforms, null);
    }

    private Publication publish(List<String> platforms, Consumer<JsonObject> artifactMutation)
            throws Exception {
        var zips = new LinkedHashMap<String, byte[]>();
        var artifacts = new JsonObject();
        for (var platform : platforms) {
            var library = libraryFor(platform);
            var zip = releaseZip(platform, library);
            zips.put("veltis-native-transform-" + platform + ".zip", zip);
            var artifact = artifactFor(platform, library, zip);
            if (artifactMutation != null) {
                artifactMutation.accept(artifact);
            }
            artifacts.add("transform/" + platform, artifact);
        }
        var manifest = manifestBytes(artifacts);
        var signature = NativeManifest.sign(manifest, keyPair.getPrivate());
        bodies.put("/" + NativeManifest.MANIFEST_ASSET, manifest);
        bodies.put("/" + NativeManifest.SIGNATURE_ASSET, signature);
        zips.forEach((name, bytes) -> bodies.put("/" + name, bytes));
        return new Publication(manifest, signature, zips);
    }

    /** Publishes one platform from an already-built archive. */
    private void serveSinglePlatform(String platform, byte[] zip, byte[] library) throws Exception {
        var artifacts = new JsonObject();
        artifacts.add("transform/" + platform, artifactFor(platform, library, zip));
        var manifest = manifestBytes(artifacts);
        bodies.put("/" + NativeManifest.MANIFEST_ASSET, manifest);
        bodies.put("/" + NativeManifest.SIGNATURE_ASSET,
                NativeManifest.sign(manifest, keyPair.getPrivate()));
        bodies.put("/veltis-native-transform-" + platform + ".zip", zip);
    }

    /**
     * Re-signs a mutated manifest and serves it, keeping sizeBytes truthful so
     * a test fails at the check it means to fail at.
     */
    private void restampAndServe(JsonObject manifest) throws Exception {
        var bytes = (manifest + "\n").getBytes(StandardCharsets.UTF_8);
        bodies.put("/" + NativeManifest.MANIFEST_ASSET, bytes);
        bodies.put("/" + NativeManifest.SIGNATURE_ASSET,
                NativeManifest.sign(bytes, keyPair.getPrivate()));
    }

    private static JsonObject artifactFor(String platform, byte[] library, byte[] zip) {
        var parts = platform.split("-", 2);
        var artifact = new JsonObject();
        artifact.addProperty("module", NativeLoader.DEFAULT_MODULE_NAME);
        artifact.addProperty("platform", platform);
        artifact.addProperty("os", parts[0]);
        artifact.addProperty("architecture", parts[1]);
        artifact.addProperty("asset", "veltis-native-transform-" + platform + ".zip");
        artifact.addProperty("sha256", sha256(zip));
        artifact.addProperty("sizeBytes", zip.length);
        artifact.addProperty("library", NativeLoader.moduleLibraryFileName("transform", platform));
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

    /** The three-file release archive the runtime accepts, plus any extra entry. */
    private static byte[] releaseZip(String platform, byte[] library) throws Exception {
        var inner = innerManifest(platform, library);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put(NativeLoader.moduleLibraryFileName("transform", platform), library);
        entries.put("veltis-native-manifest.json", inner);
        entries.put("SHA256SUMS", (sha256(library) + "  " + NativeLoader.moduleLibraryFileName("transform", platform)
                + "\n" + sha256(inner) + "  veltis-native-manifest.json\n")
                .getBytes(StandardCharsets.UTF_8));
        return zipOf(entries, Map.of());
    }

    private static byte[] innerManifest(String platform, byte[] library) {
        var inner = new JsonObject();
        inner.addProperty("schemaVersion", 1);
        inner.addProperty("module", "transform");
        inner.addProperty("platform", platform);
        inner.addProperty("library", NativeLoader.moduleLibraryFileName("transform", platform));
        inner.addProperty("sha256", sha256(library));
        inner.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        inner.addProperty("version", "1.0.0");
        return (inner + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zipOf(LinkedHashMap<String, byte[]> entries,
            Map<String, byte[]> extra) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            for (var entry : extra.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void seedCache(Path runHome, Publication publication) throws Exception {
        var dir = runHome.resolve("native").resolve("manifests");
        Files.createDirectories(dir);
        Files.write(dir.resolve(NativeManifest.MANIFEST_ASSET), publication.manifest);
        Files.write(dir.resolve(NativeManifest.SIGNATURE_ASSET), publication.signature);
    }

    /** A verified installation: library plus the record that makes it trusted. */
    private void seedInstalled(Path runHome, String platform, byte[] library, String version)
            throws Exception {
        var installDir = runHome.resolve("native").resolve(platform);
        Files.createDirectories(installDir);
        Files.write(installDir.resolve(NativeLoader.moduleLibraryFileName("transform", platform)), library);
        var record = new JsonObject();
        record.addProperty("schemaVersion", 1);
        record.addProperty("module", "transform");
        record.addProperty("platform", platform);
        record.addProperty("library", NativeLoader.moduleLibraryFileName("transform", platform));
        record.addProperty("sha256", sha256(library));
        record.addProperty("nativeVersion", version);
        record.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        record.addProperty("asset", "veltis-native-transform-" + platform + ".zip");
        record.addProperty("installedAt", "2026-11-05T00:00:00Z");
        Files.writeString(installDir.resolve(NativeDistributionInstaller.INSTALL_RECORD),
                record.toString());
    }

    /** A container header the loader's own probe accepts, per platform. */
    private static byte[] libraryFor(String platform) {
        return switch (platform) {
            case "windows-x86_64" -> peImage(0x8664);
            case "windows-aarch64" -> peImage(0xAA64);
            case "linux-x86_64" -> elfImage(0x3E, true);
            case "linux-aarch64" -> elfImage(0xB7, true);
            case "macos-x86_64" -> machOImage(0x01000007, true);
            case "macos-aarch64" -> machOImage(0x0100000C, true);
            default -> throw new IllegalArgumentException("no fixture for " + platform);
        };
    }

    private static byte[] peImage(int machine) {
        var bytes = new byte[0x80];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        putLe32(bytes, 0x3C, 0x40);
        bytes[0x40] = 'P';
        bytes[0x41] = 'E';
        putLe16(bytes, 0x44, machine);
        return bytes;
    }

    private static byte[] elfImage(int machine, boolean littleEndian) {
        var bytes = new byte[64];
        bytes[0] = 0x7F;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = (byte) (littleEndian ? 1 : 2);
        if (littleEndian) {
            putLe16(bytes, 18, machine);
        } else {
            bytes[18] = (byte) (machine >>> 8);
            bytes[19] = (byte) machine;
        }
        return bytes;
    }

    private static byte[] machOImage(int cpuType, boolean littleEndian) {
        var bytes = new byte[32];
        if (littleEndian) {
            putLe32(bytes, 0, 0xFEEDFACF);
            putLe32(bytes, 4, cpuType);
        } else {
            bytes[0] = (byte) (0xFEEDFACF >>> 24);
            bytes[1] = (byte) (0xFEEDFACF >>> 16);
            bytes[2] = (byte) (0xFEEDFACF >>> 8);
            bytes[3] = (byte) 0xFEEDFACF;
            putLe32(bytes, 4, cpuType);
        }
        return bytes;
    }

    private static void putLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putLe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private record Publication(byte[] manifest, byte[] signature, Map<String, byte[]> zips) {}
}
