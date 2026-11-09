package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest is the trust boundary: everything the downloader does is
 * derived from it, so this class pins down what it refuses — a signature that
 * does not verify, a document that is not this schema, and an artifact entry
 * that could steer a download anywhere other than this release's own asset.
 *
 * <p>Signing covers the exact bytes that are parsed: there is no canonical
 * re-serialisation step in which a document could mean one thing to the
 * verifier and another to the reader.
 */
class NativeManifestTest {

    private final PrivateKey signingKey;
    private final PublicKey verifyingKey;

    NativeManifestTest() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        signingKey = pair.getPrivate();
        verifyingKey = pair.getPublic();
    }

    // ------------------------------------------------------------------
    // Signature
    // ------------------------------------------------------------------

    @Test
    void theEmbeddedTrustAnchorIsADecodableEd25519Key() throws Exception {
        var embedded = NativeManifest.trustedPublicKey();
        assertNotNull(embedded);
        // SunEC names every Edwards curve "EdDSA"; the curve itself is the
        // thing worth pinning, because a key from another curve would verify
        // signatures this pipeline never produced.
        assertEquals("EdDSA", embedded.getAlgorithm());
        var edwards = assertInstanceOf(java.security.interfaces.EdECPublicKey.class, embedded);
        assertEquals("Ed25519", edwards.getParams().getName(),
                "the anchor must be the Ed25519 curve, not another EdDSA curve");
    }

    @Test
    void aCrossCompiledArtifactIsRepresentableInTheManifest() throws Exception {
        // A cross-only target has to be publishable as itself: the parser
        // accepts the 'cross' mark, and NativeDistributionManager is what turns
        // a cross artifact down to a Java fallback. Refusing it here instead
        // would make the whole release unparseable for every platform.
        var bytes = withArtifact(artifact -> artifact.addProperty("verification", "cross"));
        var manifest = NativeManifest.verifyAndParse(bytes,
                NativeManifest.sign(bytes, signingKey), verifyingKey);
        var artifact = manifest.artifact("linux-x86_64").orElseThrow();
        assertEquals("cross", artifact.verification());
    }

    @Test
    void aSignatureOverTheExactBytesVerifiesAndParses() throws Exception {
        var bytes = validManifest();
        var signature = NativeManifest.sign(bytes, signingKey);

        var manifest = NativeManifest.verifyAndParse(bytes, signature, verifyingKey);

        assertEquals("veltis-native-v1.0.0", manifest.release());
        assertEquals(1, manifest.abiVersion());
        assertTrue(manifest.artifact("linux-x86_64").isPresent());
    }

    @Test
    void oneChangedByteInvalidatesTheSignature() throws Exception {
        var bytes = validManifest();
        var signature = NativeManifest.sign(bytes, signingKey);
        var tampered = bytes.clone();
        tampered[tampered.length - 4] ^= 0x01;

        var refused = assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(tampered, signature, verifyingKey));

        assertTrue(refused.getMessage().contains("does not verify"), refused.getMessage());
    }

    @Test
    void aSignatureFromAnotherKeyIsRefused() throws Exception {
        var bytes = validManifest();
        var otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signature = NativeManifest.sign(bytes, otherPair.getPrivate());

        var refused = assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(bytes, signature, verifyingKey));

        assertTrue(refused.getMessage().contains("does not verify"), refused.getMessage());
    }

    @Test
    void anEmptyOrMalformedSignatureFileIsRefused() throws Exception {
        var bytes = validManifest();

        assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(bytes, new byte[0], verifyingKey));
        assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(bytes,
                        "not base64 at all!".getBytes(StandardCharsets.US_ASCII), verifyingKey));
        assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(bytes, null, verifyingKey));
    }

    @Test
    void anEmptyOrOversizedManifestIsRefusedBeforeParsing() throws Exception {
        var signature = NativeManifest.sign(new byte[] {1}, signingKey);

        assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(new byte[0], signature, verifyingKey));

        var oversized = new byte[(int) NativeManifest.MAX_MANIFEST_BYTES + 1];
        var refused = assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(oversized, signature, verifyingKey));
        assertTrue(refused.getMessage().contains("limit"), refused.getMessage());
    }

    // ------------------------------------------------------------------
    // Document shape
    // ------------------------------------------------------------------

    @Test
    void aDocumentWithTheWrongSchemaKindOrAbiIsRefused() throws Exception {
        assertRefused(withRoot(root -> root.addProperty("schemaVersion", 2)), "schemaVersion");
        assertRefused(withRoot(root -> root.addProperty("kind", "something-else")), "kind");
        assertRefused(withRoot(root -> root.addProperty("abiVersion", 99)), "abiVersion");
        assertRefused(withRoot(root -> root.addProperty("nativeVersion", "banana")), "nativeVersion");
    }

    @Test
    void aDocumentThatIsNotAnObjectOrNotJsonIsRefused() throws Exception {
        assertRefused("[1,2,3]".getBytes(StandardCharsets.UTF_8), "not a JSON object");
        assertRefused("{not json".getBytes(StandardCharsets.UTF_8), "not valid JSON");
    }

    @Test
    void aManifestWithNoArtifactsIsRefused() throws Exception {
        assertRefused(withRoot(root -> root.add("artifacts",
                        new com.google.gson.JsonObject())), "no artifacts");
    }

    @Test
    void anArtifactEntryThatCouldSteerTheDownloadIsRefused() throws Exception {
        // Key and platform disagree.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("platform", "linux-aarch64");
        }), "keyed");

        // os/architecture do not spell the platform key.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("os", "windows");
        }), "different platform");

        // The asset name is the one path a signed manifest gets: it must be
        // this release's conventional file name, never a URL or a directory.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("asset", "../../etc/passwd");
        }), "publishes");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("asset", "https://evil.example/x.zip");
        }), "publishes");

        // Digest and size bounds.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("sha256", "abc");
        }), "64 lowercase hex");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("librarySha256", "Z".repeat(64));
        }), "64 lowercase hex");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("sizeBytes", 0);
        }), "outside");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("sizeBytes", NativeManifest.MAX_ARCHIVE_BYTES + 1);
        }), "outside");

        // The library must be the one this platform's loader looks for.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("library", "libsomethingelse.so");
        }), "loads");

        // ABI and verification claims.
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("abiVersion", 7);
        }), "declares abiVersion 7");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("nativeVersion", "2.0.0");
        }), "declares nativeVersion 2.0.0");
        assertRefused(withArtifact(artifact -> {
            artifact.addProperty("verification", "we-think-it-is-fine");
        }), "neither host nor cross");
        assertRefused(withArtifact(artifact -> {
            artifact.remove("verified");
        }), "verified");
    }

    @Test
    void aMissingFieldIsNamedInTheRefusal() {
        var refused = assertThrows(IOException.class, () -> NativeManifest.verifyAndParse(
                "{}".getBytes(StandardCharsets.UTF_8),
                NativeManifest.sign("{}".getBytes(StandardCharsets.UTF_8), signingKey),
                verifyingKey));
        assertTrue(refused.getMessage().contains("schemaVersion"), refused.getMessage());
    }

    @Test
    void theTrustedKeyOfTheRepositoryWouldRefuseAnUnsignedAttempt() throws Exception {
        // The embedded key is the only key the server trusts; a manifest
        // signed by the test key must not satisfy it.
        var bytes = validManifest();
        var signature = NativeManifest.sign(bytes, signingKey);
        var embedded = NativeManifest.trustedPublicKey();

        assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(bytes, signature, embedded));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertRefused(byte[] manifestBytes, String messagePart) throws Exception {
        var signature = NativeManifest.sign(manifestBytes, signingKey);
        var refused = assertThrows(IOException.class,
                () -> NativeManifest.verifyAndParse(manifestBytes, signature, verifyingKey));
        assertTrue(refused.getMessage().contains(messagePart),
                "expected '" + messagePart + "' in: " + refused.getMessage());
    }

    /** The manifest bytes after {@code mutate} has adjusted the document. */
    private static byte[] withRoot(java.util.function.Consumer<JsonObject> mutate) {
        var root = baseRoot();
        mutate.accept(root);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withArtifact(java.util.function.Consumer<JsonObject> mutateArtifact) {
        return withRoot(root -> {
            var artifact = root.getAsJsonObject("artifacts").getAsJsonObject("transform/linux-x86_64");
            mutateArtifact.accept(artifact);
        });
    }

    private static byte[] validManifest() {
        return withRoot(root -> {
        });
    }

    private static JsonObject baseRoot() {
        var artifact = new JsonObject();
        artifact.addProperty("module", "transform");
        artifact.addProperty("platform", "linux-x86_64");
        artifact.addProperty("os", "linux");
        artifact.addProperty("architecture", "x86_64");
        artifact.addProperty("asset", "veltis-native-transform-linux-x86_64.zip");
        artifact.addProperty("sha256", "a".repeat(64));
        artifact.addProperty("sizeBytes", 4096);
        artifact.addProperty("library", "libtransform.so");
        artifact.addProperty("librarySha256", "b".repeat(64));
        artifact.addProperty("abiVersion", 1);
        artifact.addProperty("nativeVersion", "1.0.0");
        artifact.addProperty("verified", true);
        artifact.addProperty("verification", "host");

        var artifacts = new JsonObject();
        artifacts.add("transform/linux-x86_64", artifact);

        var root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("kind", "veltis-native-distribution-manifest");
        root.addProperty("release", "veltis-native-v1.0.0");
        root.addProperty("nativeVersion", "1.0.0");
        root.addProperty("abiVersion", 1);
        root.addProperty("generatedAt", "2026-11-05T00:00:00Z");
        root.addProperty("commit", "0".repeat(40));
        root.add("artifacts", artifacts);
        return root;
    }
}
