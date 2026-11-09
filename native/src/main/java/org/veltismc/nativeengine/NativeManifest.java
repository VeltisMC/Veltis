package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The signed, cross-platform manifest that tells one running server which
 * module artifact it may download.
 *
 * <p>The manifest is fetched as bytes first and verified as bytes second:
 * {@link #verifyAndParse} checks the Ed25519 signature over the exact bytes it
 * was given and only then parses those same bytes as JSON. Nothing about the
 * document's *format* is trusted before the signature — there is no canonical
 * JSON re-serialisation to disagree about, because the signature covers the
 * file exactly as published.
 *
 * <p>Every field a download decision depends on is validated while parsing:
 * digest shapes, size bounds, the asset name (which must be this platform's
 * release asset, so a signed manifest still cannot point the downloader at a
 * path), and the platform/os/architecture triple. A manifest that fails any of
 * these is refused as a whole: it was signed by this repository, so a malformed
 * one means corruption or a bug, not a platform to skip.
 *
 * <p>The commit-pinned distribution does not publish the document sketched
 * below; it reads {@code NativeCommitManifest} instead, whose release tag and
 * asset names are derived from the build's commit. What this class still owns
 * for both formats is the trust anchor, the Ed25519 verification, and the size
 * limits, so the retired version-pinned shape is kept here only as the input
 * its signature tests exercise.
 *
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "kind": "veltis-native-distribution-manifest",
 *   "release": "veltis-native-v1.0.0",
 *   "nativeVersion": "1.0.0",
 *   "abiVersion": 1,
 *   "generatedAt": "2026-11-05T00:00:00Z",
 *   "artifacts": {
 *     "transform/linux-x86_64": {
 *       "module": "transform",
 *       "platform": "linux-x86_64",
 *       "os": "linux",
 *       "architecture": "x86_64",
 *       "asset": "veltis-native-transform-linux-x86_64.zip",
 *       "sha256": "&lt;64 hex, the archive&gt;",
 *       "sizeBytes": 123456,
 *       "library": "libtransform.so",
 *       "librarySha256": "&lt;64 hex, the library&gt;",
 *       "abiVersion": 1,
 *       "nativeVersion": "1.0.0",
 *       "verified": true,
 *       "verification": "host"
 *     }
 *   }
 * }
 * </pre>
 */
final class NativeManifest {

    static final int SCHEMA_VERSION = 1;
    static final String KIND = "veltis-native-distribution-manifest";

    /** The two files the release publishes, and the names the cache keeps. */
    static final String MANIFEST_ASSET = "native-manifest.json";
    static final String SIGNATURE_ASSET = "native-manifest.json.sig";

    /**
     * A manifest larger than this is refused before it is parsed. The real
     * document is a few kilobytes; the limit exists so a hostile or looping
     * endpoint cannot turn "read the manifest" into an unbounded allocation.
     */
    static final long MAX_MANIFEST_BYTES = 1024L * 1024L;

    /**
     * The largest archive this runtime will read. The published libraries are
     * well under a megabyte; 64 MiB is headroom measured in orders of
     * magnitude, and it bounds both the download and the in-memory extraction.
     */
    static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;

    /**
     * The one key this runtime trusts for manifest signatures: the Ed25519
     * public half of the release signing key, embedded so verification needs
     * no configuration, no network and no trust-on-first-use. A manifest
     * signed by any other key is rejected before a single field is read.
     *
     * <p>Rotating it is a deliberate two-step change to this file and the
     * repository's {@code VELTIS_NATIVE_MANIFEST_KEY} secret; see
     * {@code native/docs/DISTRIBUTION.md}. Both halves of the pair below came
     * from {@code :veltis-native:generateNativeManifestKey} on 2026-11-05, and
     * {@code :veltis-native:verifyNativeManifestKeyPairing} is what proves the
     * secret still matches this constant — the previous value carried no record
     * of which private key satisfied it, so a release could never have been
     * signed, and it is retired rather than kept as a second anchor.
     */
    static final String TRUSTED_PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEAjwUaYYJywSNXMHvmO0dyJlLsMxRN3NqCSLLFeRIK4so=";

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PLATFORM_KEY = Pattern.compile("[a-z0-9]+-[a-z0-9_]+");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){0,3}");

    /** One publishable artifact, with every field a download decision needs. */
    record Artifact(
            String module,
            String platform,
            String os,
            String architecture,
            String asset,
            String sha256,
            long sizeBytes,
            String library,
            String librarySha256,
            int abiVersion,
            String nativeVersion,
            boolean verified,
            String verification) {}

    private final String release;
    private final String nativeVersion;
    private final int abiVersion;
    private final String generatedAt;
    private final Map<String, Artifact> artifacts;

    private NativeManifest(String release, String nativeVersion, int abiVersion, String generatedAt,
            Map<String, Artifact> artifacts) {
        this.release = release;
        this.nativeVersion = nativeVersion;
        this.abiVersion = abiVersion;
        this.generatedAt = generatedAt;
        this.artifacts = artifacts;
    }

    String release() {
        return release;
    }

    String nativeVersion() {
        return nativeVersion;
    }

    int abiVersion() {
        return abiVersion;
    }

    String generatedAt() {
        return generatedAt;
    }

    Optional<Artifact> artifact(String platform) {
        return artifact(NativeLoader.DEFAULT_MODULE_NAME, platform);
    }

    Optional<Artifact> artifact(String module, String platform) {
        return Optional.ofNullable(artifacts.get(module + "/" + platform));
    }

    /** The artifact keys this manifest publishes, in document order. */
    Map<String, Artifact> artifacts() {
        return artifacts;
    }

    /** The embedded trust anchor, decoded. */
    static PublicKey trustedPublicKey() throws IOException {
        try {
            var encoded = Base64.getDecoder().decode(TRUSTED_PUBLIC_KEY_BASE64.trim());
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            throw new IOException("the embedded manifest public key is not decodable", e);
        }
    }

    /**
     * Verifies the signature over {@code manifestBytes} with {@code trustedKey}
     * and parses those same bytes.
     *
     * @param signatureBytes the contents of the signature file: base64 of the
     *                       Ed25519 signature over {@code manifestBytes}
     * @throws IOException when the signature does not verify, the base64 is
     *                     malformed, or the document is not a valid manifest
     */
    static NativeManifest verifyAndParse(byte[] manifestBytes, byte[] signatureBytes, PublicKey trustedKey)
            throws IOException {
        if (manifestBytes == null || manifestBytes.length == 0) {
            throw new IOException("the manifest is empty");
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("the manifest is " + manifestBytes.length
                    + " bytes, more than the " + MAX_MANIFEST_BYTES + "-byte limit");
        }
        verifySignature(manifestBytes, signatureBytes, trustedKey);
        return parse(manifestBytes);
    }

    static void verifySignature(byte[] manifestBytes, byte[] signatureBytes, PublicKey trustedKey)
            throws IOException {
        if (signatureBytes == null || signatureBytes.length == 0) {
            throw new IOException("the manifest signature file is missing or empty");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(new String(signatureBytes, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("the manifest signature is not valid base64", e);
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedKey);
            verifier.update(manifestBytes);
            if (!verifier.verify(signature)) {
                throw new IOException("the manifest signature does not verify against the trusted key");
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Ed25519 verification is unavailable: " + e.getMessage(), e);
        }
    }

    private static NativeManifest parse(byte[] manifestBytes) throws IOException {
        final JsonObject root;
        try {
            var parsed = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("the manifest is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("the manifest is not valid JSON: " + e.getMessage(), e);
        }

        var schemaVersion = intField(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("unsupported manifest schemaVersion " + schemaVersion
                    + "; this server reads " + SCHEMA_VERSION);
        }
        var kind = stringField(root, "kind");
        if (!KIND.equals(kind)) {
            throw new IOException("unexpected manifest kind '" + kind + "'");
        }
        var abiVersion = intField(root, "abiVersion");
        if (abiVersion != NativeLoader.EXPECTED_ABI_VERSION) {
            throw new IOException("manifest abiVersion " + abiVersion
                    + " but this server speaks " + NativeLoader.EXPECTED_ABI_VERSION);
        }
        var nativeVersion = stringField(root, "nativeVersion");
        if (!VERSION.matcher(nativeVersion).matches()) {
            throw new IOException("manifest nativeVersion '" + nativeVersion + "' is not a version");
        }
        var release = stringField(root, "release");
        var generatedAt = root.has("generatedAt") && root.get("generatedAt").isJsonPrimitive()
                ? root.get("generatedAt").getAsString()
                : "";

        if (!root.has("artifacts") || !root.get("artifacts").isJsonObject()) {
            throw new IOException("the manifest has no artifacts object");
        }
        var artifactsObject = root.getAsJsonObject("artifacts");
        var artifacts = new LinkedHashMap<String, Artifact>();
        for (var entry : artifactsObject.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("artifact '" + entry.getKey() + "' is not a JSON object");
            }
            var artifact = parseArtifact(
                    entry.getKey(), entry.getValue().getAsJsonObject(), abiVersion, nativeVersion);
            artifacts.put(entry.getKey(), artifact);
        }
        if (artifacts.isEmpty()) {
            throw new IOException("the manifest publishes no artifacts");
        }
        return new NativeManifest(release, nativeVersion, abiVersion, generatedAt, artifacts);
    }

    private static Artifact parseArtifact(
            String key, JsonObject object, int manifestAbiVersion, String manifestNativeVersion) throws IOException {
        var module = stringField(object, "module");
        if (!module.matches("[a-z][a-z0-9_]*")) {
            throw new IOException("artifact module '" + module + "' is not a module identifier");
        }
        var platform = stringField(object, "platform");
        if (!PLATFORM_KEY.matcher(platform).matches()) {
            throw new IOException("artifact platform '" + platform + "' is not a platform key");
        }
        if (!(module + "/" + platform).equals(key)) {
            throw new IOException("artifact is keyed '" + key + "' but claims module/platform '"
                    + module + "/" + platform + "'");
        }
        var os = stringField(object, "os");
        var architecture = stringField(object, "architecture");
        if (!platform.equals(os + "-" + architecture)) {
            throw new IOException("artifact " + platform + " declares os=" + os
                    + " architecture=" + architecture + ", which is a different platform");
        }

        var asset = stringField(object, "asset");
        // The release asset this platform's zip is published as. Requiring the
        // exact conventional name means a signed manifest can never describe a
        // path, a URL or another platform's archive.
        var expectedAsset = "veltis-native-" + module + "-" + platform + ".zip";
        if (!asset.equals(expectedAsset)) {
            throw new IOException("artifact " + module + "/" + platform + " names asset '" + asset
                    + "' but this release publishes '" + expectedAsset + "'");
        }

        var sha256 = stringField(object, "sha256");
        if (!HEX_64.matcher(sha256).matches()) {
            throw new IOException("artifact " + platform + " has a sha256 that is not 64 lowercase hex digits");
        }
        var librarySha256 = stringField(object, "librarySha256");
        if (!HEX_64.matcher(librarySha256).matches()) {
            throw new IOException("artifact " + platform + " has a librarySha256 that is not 64 lowercase hex digits");
        }
        var sizeBytes = longField(object, "sizeBytes");
        if (sizeBytes <= 0 || sizeBytes > MAX_ARCHIVE_BYTES) {
            throw new IOException("artifact " + platform + " declares sizeBytes " + sizeBytes
                    + ", outside 1.." + MAX_ARCHIVE_BYTES);
        }
        var library = stringField(object, "library");
        if (!library.equals(NativeLoader.moduleLibraryFileName(module, platform))) {
            throw new IOException("artifact " + module + "/" + platform + " names library '" + library
                    + "' but that platform loads '" + NativeLoader.moduleLibraryFileName(module, platform) + "'");
        }
        var abiVersion = intField(object, "abiVersion");
        if (abiVersion != manifestAbiVersion) {
            throw new IOException("artifact " + module + "/" + platform + " declares abiVersion " + abiVersion
                    + " but the manifest declares " + manifestAbiVersion);
        }
        var nativeVersion = stringField(object, "nativeVersion");
        if (!VERSION.matcher(nativeVersion).matches()) {
            throw new IOException("artifact " + platform + " has nativeVersion '" + nativeVersion + "'");
        }
        if (!nativeVersion.equals(manifestNativeVersion)) {
            throw new IOException("artifact " + module + "/" + platform + " declares nativeVersion "
                    + nativeVersion + " but the manifest declares " + manifestNativeVersion);
        }
        if (!object.has("verified") || !object.get("verified").isJsonPrimitive()
                || !object.get("verified").getAsJsonPrimitive().isBoolean()) {
            throw new IOException("artifact " + platform + " has no boolean 'verified' field");
        }
        var verified = object.get("verified").getAsBoolean();
        var verification = stringField(object, "verification");
        if (!"host".equals(verification) && !"cross".equals(verification)) {
            throw new IOException("artifact " + platform + " has verification '" + verification
                    + "', which is neither host nor cross");
        }
        return new Artifact(module, platform, os, architecture, asset, sha256, sizeBytes, library,
                librarySha256, abiVersion, nativeVersion, verified, verification);
    }

    private static String stringField(JsonObject object, String name) throws IOException {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isString()) {
            throw new IOException("missing string field '" + name + "'");
        }
        var value = object.get(name).getAsString();
        if (value.isBlank()) {
            throw new IOException("field '" + name + "' is blank");
        }
        return value;
    }

    private static int intField(JsonObject object, String name) throws IOException {
        var value = longField(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("field '" + name + "' is out of int range");
        }
        return (int) value;
    }

    private static long longField(JsonObject object, String name) throws IOException {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !object.get(name).getAsJsonPrimitive().isNumber()) {
            throw new IOException("missing numeric field '" + name + "'");
        }
        return object.get(name).getAsLong();
    }

    /**
     * Signs {@code manifestBytes} the way the release pipeline does. Used by
     * the tests and by any local tool that publishes a manifest; production
     * signing happens in Gradle, where the release artifacts are assembled.
     */
    static byte[] sign(byte[] manifestBytes, PrivateKey key) throws IOException {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(manifestBytes);
            return Base64.getEncoder().encode(signer.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("Ed25519 signing is unavailable: " + e.getMessage(), e);
        }
    }
}
