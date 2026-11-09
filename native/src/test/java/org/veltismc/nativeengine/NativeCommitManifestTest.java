package org.veltismc.nativeengine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCommitManifestTest {

    private static final String COMMIT = "20421c2" + "0".repeat(33);
    private static final String SHORT = COMMIT.substring(0, 7);
    private static final String PLATFORM = "linux-x86_64";
    private static final String ASSET = PLATFORM + "-" + SHORT + ".zip";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String URL = "https://github.com/VeltisMC/Veltis/releases/download/"
            + "veltis-pre-alpha-26.3-" + SHORT + "/" + ASSET;

    @Test
    void parsesAValidCommitPinnedManifest() throws Exception {
        var manifest = NativeCommitManifest.parse(validManifest().getBytes(StandardCharsets.UTF_8));

        assertEquals(COMMIT, manifest.commit());
        assertEquals(SHORT, manifest.shortCommit());
        assertEquals("veltis-pre-alpha-26.3-" + SHORT, manifest.releaseTag());
        var artifact = manifest.platform(PLATFORM);
        assertEquals(PLATFORM, artifact.platform());
        assertEquals("linux", artifact.os());
        assertEquals("x86_64", artifact.architecture());
        assertEquals(ASSET, artifact.asset());
        assertEquals(CHECKSUM, artifact.sha256());
        assertTrue(artifact.verified());
        assertEquals(1, artifact.modules().size());
        assertEquals("transform", artifact.modules().get(0).name());
    }

    @Test
    void rejectsAManifestWithNoArchiveChecksum() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM).remove("sha256");
        assertRefused(root, "sha256");
    }

    @Test
    void rejectsAMalformedArchiveChecksum() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .addProperty("sha256", "not-a-digest");
        assertRefused(root, "checksum");
    }

    @Test
    void rejectsAManifestForAnotherCommit() {
        var root = parsedValid();
        root.addProperty("commit", "b".repeat(40));
        assertRefused(root, "commit");
    }

    @Test
    void rejectsAShortCommitThatDoesNotMatchTheFullCommit() {
        var root = parsedValid();
        root.addProperty("shortCommit", "ffffff0");
        assertRefused(root, "shortCommit");
    }

    @Test
    void rejectsAWrongPlatformTriple() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .addProperty("architecture", "aarch64");
        assertRefused(root, "platform");
    }

    @Test
    void rejectsAPlatformKeyThatDoesNotMatchTheEntry() {
        var root = parsedValid();
        var entry = root.getAsJsonObject("platforms").remove(PLATFORM);
        root.getAsJsonObject("platforms").add("linux-aarch64", entry);
        assertRefused(root, "platform");
    }

    @Test
    void rejectsAnAssetNameOtherThanThisPlatformsArchive() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .addProperty("asset", "veltis-native-" + PLATFORM + ".zip");
        assertRefused(root, "asset");
    }

    @Test
    void rejectsADownloadUrlFromAnUntrustedHost() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .addProperty("downloadUrl", "https://example.invalid/" + ASSET);
        assertRefused(root, "host");
    }

    @Test
    void rejectsADownloadUrlThatIsNotHttps() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .addProperty("downloadUrl", "ftp://github.com/" + ASSET);
        assertRefused(root, "HTTPS");
    }

    @Test
    void rejectsAnIncompatibleModuleAbi() {
        var root = parsedValid();
        root.getAsJsonObject("platforms").getAsJsonObject(PLATFORM)
                .getAsJsonArray("modules").get(0).getAsJsonObject()
                .addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION + 1);
        assertRefused(root, "ABI");
    }

    @Test
    void rejectsAWrongSchemaOrKind() {
        var wrongSchema = parsedValid();
        wrongSchema.addProperty("schemaVersion", 1);
        assertRefused(wrongSchema, "schema");

        var wrongKind = parsedValid();
        wrongKind.addProperty("kind", "veltis-native-distribution");
        assertRefused(wrongKind, "schema");
    }

    @Test
    void rejectsEmptyInvalidAndOversizedDocuments() {
        assertThrows(IOException.class, () -> NativeCommitManifest.parse(new byte[0]));
        assertThrows(IOException.class, () -> NativeCommitManifest.parse(null));
        assertThrows(IOException.class, () -> NativeCommitManifest.parse("[]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> NativeCommitManifest.parse("{".getBytes(StandardCharsets.UTF_8)));
        var oversized = new byte[(int) NativeManifest.MAX_MANIFEST_BYTES + 1];
        assertThrows(IOException.class, () -> NativeCommitManifest.parse(oversized));
    }

    @Test
    void rejectsAManifestWithNoPlatforms() {
        var root = parsedValid();
        root.add("platforms", new JsonObject());
        assertRefused(root, "no platform");
    }

    private static JsonObject parsedValid() {
        return com.google.gson.JsonParser.parseString(validManifest()).getAsJsonObject();
    }

    private static void assertRefused(JsonObject root, String expectedFragment) {
        var error = assertThrows(IOException.class,
                () -> NativeCommitManifest.parse((root + "\n").getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT)
                        .contains(expectedFragment.toLowerCase(java.util.Locale.ROOT)),
                "expected '" + expectedFragment + "' in: " + error.getMessage());
        assertFalse(error.getMessage().isBlank());
    }

    private static String validManifest() {
        var module = new JsonObject();
        module.addProperty("name", "transform");
        module.addProperty("library", "libtransform.so");
        module.addProperty("sha256", "c".repeat(64));
        module.addProperty("abiVersion", NativeLoader.EXPECTED_ABI_VERSION);
        module.addProperty("nativeVersion", "1.0.0");

        var entry = new JsonObject();
        entry.addProperty("platform", PLATFORM);
        entry.addProperty("os", "linux");
        entry.addProperty("architecture", "x86_64");
        entry.addProperty("asset", ASSET);
        entry.addProperty("downloadUrl", URL);
        entry.addProperty("sha256", CHECKSUM);
        entry.addProperty("sizeBytes", 4096);
        entry.addProperty("verified", true);
        var modules = new JsonArray();
        modules.add(module);
        entry.add("modules", modules);

        var platforms = new JsonObject();
        platforms.add(PLATFORM, entry);

        var root = new JsonObject();
        root.addProperty("schemaVersion", NativeCommitManifest.SCHEMA_VERSION);
        root.addProperty("kind", NativeCommitManifest.KIND);
        root.addProperty("releaseTag", "veltis-pre-alpha-26.3-" + SHORT);
        root.addProperty("commit", COMMIT);
        root.addProperty("shortCommit", SHORT);
        root.add("platforms", platforms);
        return root + "\n";
    }
}
