package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Signed metadata for one immutable native release, tied to one Veltis commit. */
final class NativeCommitManifest {

    static final int SCHEMA_VERSION = 2;
    static final String KIND = "veltis-native-commit-distribution";
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){0,3}");

    record Module(String name, String library, String sha256, int abiVersion, String nativeVersion) {}

    record PlatformArtifact(
            String platform,
            String os,
            String architecture,
            String asset,
            URI downloadUrl,
            String sha256,
            long sizeBytes,
            boolean verified,
            List<Module> modules) {}

    private final String releaseTag;
    private final String commit;
    private final String shortCommit;
    private final Map<String, PlatformArtifact> platforms;

    private NativeCommitManifest(String releaseTag, String commit, String shortCommit,
            Map<String, PlatformArtifact> platforms) {
        this.releaseTag = releaseTag;
        this.commit = commit;
        this.shortCommit = shortCommit;
        this.platforms = Map.copyOf(platforms);
    }

    String releaseTag() {
        return releaseTag;
    }

    String commit() {
        return commit;
    }

    String shortCommit() {
        return shortCommit;
    }

    PlatformArtifact platform(String key) {
        return platforms.get(key);
    }

    static NativeCommitManifest verifyAndParse(byte[] bytes, byte[] signature, PublicKey trustedKey)
            throws IOException {
        NativeManifest.verifySignature(bytes, signature, trustedKey);
        final JsonObject root;
        try {
            var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("the commit manifest is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("the commit manifest is not valid JSON: " + e.getMessage(), e);
        }

        if (number(root, "schemaVersion") != SCHEMA_VERSION
                || !KIND.equals(string(root, "kind"))) {
            throw new IOException("unsupported native commit manifest schema or kind");
        }
        var commit = string(root, "commit");
        if (!COMMIT.matcher(commit).matches()) {
            throw new IOException("manifest commit is not a full Git SHA");
        }
        var shortCommit = string(root, "shortCommit");
        if (!shortCommit.equals(commit.substring(0, 7))) {
            throw new IOException("manifest shortCommit does not match commit");
        }
        var tag = string(root, "releaseTag");
        if (!tag.equals("veltis-native-" + shortCommit)) {
            throw new IOException("manifest releaseTag does not identify its commit");
        }
        if (!root.has("platforms") || !root.get("platforms").isJsonObject()) {
            throw new IOException("manifest has no platform artifacts");
        }

        var artifacts = new LinkedHashMap<String, PlatformArtifact>();
        for (var entry : root.getAsJsonObject("platforms").entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IOException("platform entry " + entry.getKey() + " is not an object");
            }
            var object = entry.getValue().getAsJsonObject();
            var platform = string(object, "platform");
            var parts = platform.split("-", 2);
            if (parts.length != 2 || !entry.getKey().equals(platform)
                    || !platform.equals(string(object, "os") + "-" + string(object, "architecture"))
                    || !NativeLoader.isRecognizedPlatform(platform)) {
                throw new IOException("invalid or unsupported platform key '" + platform + "'");
            }
            var asset = string(object, "asset");
            var expectedAsset = platform + "-" + shortCommit + ".zip";
            if (!expectedAsset.equals(asset)) {
                throw new IOException("platform " + platform + " names asset '" + asset
                        + "', expected '" + expectedAsset + "'");
            }
            var url = parseDownloadUrl(string(object, "downloadUrl"));
            var digest = string(object, "sha256");
            if (!SHA256.matcher(digest).matches()) {
                throw new IOException("platform " + platform + " has an invalid ZIP checksum");
            }
            var size = longNumber(object, "sizeBytes");
            if (size <= 0 || size > NativeManifest.MAX_ARCHIVE_BYTES) {
                throw new IOException("platform " + platform + " has an invalid ZIP size");
            }
            if (!object.has("verified") || !object.get("verified").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("verified").isBoolean()) {
                throw new IOException("platform " + platform + " has no verification status");
            }
            var modules = parseModules(object, platform);
            if (modules.isEmpty()) {
                throw new IOException("platform " + platform + " includes no native modules");
            }
            var artifact = new PlatformArtifact(platform, parts[0], parts[1], asset,
                    url, digest, size, object.get("verified").getAsBoolean(), List.copyOf(modules));
            if (artifacts.putIfAbsent(platform, artifact) != null) {
                throw new IOException("manifest repeats platform " + platform);
            }
        }
        if (artifacts.isEmpty()) {
            throw new IOException("manifest has no platform artifacts");
        }
        return new NativeCommitManifest(tag, commit, shortCommit, artifacts);
    }

    private static List<Module> parseModules(JsonObject platform, String platformKey) throws IOException {
        if (!platform.has("modules") || !platform.get("modules").isJsonArray()) {
            throw new IOException("platform " + platformKey + " has no module list");
        }
        var result = new ArrayList<Module>();
        var names = new java.util.HashSet<String>();
        for (var value : platform.getAsJsonArray("modules")) {
            if (!value.isJsonObject()) {
                throw new IOException("platform " + platformKey + " has a malformed module entry");
            }
            var object = value.getAsJsonObject();
            var name = string(object, "name");
            if (!NativeLoader.isValidModuleName(name) || !names.add(name)) {
                throw new IOException("platform " + platformKey + " has an invalid or duplicate module " + name);
            }
            var library = string(object, "library");
            if (!library.equals(NativeLoader.moduleLibraryFileName(name, platformKey))) {
                throw new IOException("module " + name + " declares the wrong library name for " + platformKey);
            }
            var sha256 = string(object, "sha256");
            if (!SHA256.matcher(sha256).matches()) {
                throw new IOException("module " + name + " has an invalid library checksum");
            }
            var abi = number(object, "abiVersion");
            if (abi < 1 || abi != NativeLoader.EXPECTED_ABI_VERSION) {
                throw new IOException("module " + name + " declares an incompatible ABI " + abi);
            }
            var version = string(object, "nativeVersion");
            if (!VERSION.matcher(version).matches()) {
                throw new IOException("module " + name + " has an invalid native version");
            }
            result.add(new Module(name, library, sha256, abi, version));
        }
        return result;
    }

    private static URI parseDownloadUrl(String value) throws IOException {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("manifest downloadUrl is malformed", e);
        }
        var host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || ("http".equalsIgnoreCase(uri.getScheme())
                                && NativeDistributionTransport.isLoopbackAddress(host)))) {
            throw new IOException("manifest downloadUrl must be HTTPS to a trusted release host");
        }
        if (!host.equalsIgnoreCase("github.com") && !host.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".githubusercontent.com")
                && !NativeDistributionTransport.isLoopbackAddress(host)) {
            throw new IOException("manifest downloadUrl uses an untrusted host");
        }
        return uri;
    }

    private static String string(JsonObject object, String field) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()
                || object.get(field).getAsString().isBlank()) {
            throw new IOException("missing or blank string field '" + field + "'");
        }
        return object.get(field).getAsString();
    }

    private static int number(JsonObject object, String field) throws IOException {
        long value = longNumber(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("field '" + field + "' is outside the integer range");
        }
        return (int) value;
    }

    private static long longNumber(JsonObject object, String field) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isNumber()) {
            throw new IOException("missing numeric field '" + field + "'");
        }
        try {
            return object.get(field).getAsLong();
        } catch (NumberFormatException e) {
            throw new IOException("invalid numeric field '" + field + "'", e);
        }
    }
}
