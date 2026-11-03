package org.veltismc.patchengine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Minecraft acquisition from Mojang, and nothing else.
 *
 * <p>Resolution is always the same three hops, all against Mojang's own
 * endpoints — no third-party mirrors and no hardcoded per-version URLs:
 *
 * <ol>
 *   <li>{@code https://piston-meta.mojang.com/mc/game/version_manifest_v2.json}
 *       maps the requested version id to its metadata URL,</li>
 *   <li>that metadata names the server artifact (URL + SHA-1 + size) and every
 *       library, each with its own SHA-1,</li>
 *   <li>{@link MinecraftDownloader} fetches those artifacts and verifies each
 *       SHA-1 before anything is promoted into the workspace.</li>
 * </ol>
 *
 * <p>The manifest is the v2 endpoint on purpose: it carries SHA-1s, and the
 * deprecated v1 {@code launchermeta.mojang.com} manifest this project used
 * before does not.
 */
public final class MojangMetadata {

    /** Mojang's official version manifest. v2 carries the per-artifact SHA-1s. */
    public static final String VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient client;

    public MojangMetadata() {
        this(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public MojangMetadata(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }

    /**
     * Fetches the version manifest and resolves one version to its metadata.
     *
     * <p>The raw metadata is cached in the workspace so a rerun (or a build with
     * no network, against an already-prepared workspace) never needs Mojang.
     *
     * @throws PatchEngineException when the version is unknown or Mojang is
     *                              unreachable, with the failure spelled out
     */
    public VersionMetadata resolve(VeltisWorkspace workspace, MinecraftVersion version) {
        var cached = workspace.versionMetadataFile();
        if (isCachedFor(cached, version)) {
            try {
                return parseVersionMetadata(
                    Files.readString(cached, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new PatchEngineException(
                    "[VeltisMinecraft] Failed to read cached version metadata: " + cached, e);
            }
        }
        var entry = fetchManifest().find(version);
        var json = get(entry.url(), "version metadata for " + version);
        cacheMetadata(workspace, json);
        return parseVersionMetadata(json);
    }

    /**
     * True when the cached metadata names exactly the requested version. This is
     * what proves a cached workspace belongs to the artifact being built, rather
     * than to whatever was built last.
     */
    private static boolean isCachedFor(Path cached, MinecraftVersion version) {
        if (!Files.isRegularFile(cached)) {
            return false;
        }
        try {
            var root = JsonParser.parseString(
                Files.readString(cached, StandardCharsets.UTF_8)).getAsJsonObject();
            return version.toString().equals(root.get("id").getAsString());
        } catch (Exception e) {
            // A damaged or stale cache is not fatal: fall through to the network.
            return false;
        }
    }

    /** Fetches and parses the version manifest. */
    public Manifest fetchManifest() {
        var body = get(VERSION_MANIFEST_URL, "version manifest");
        try {
            var root = JsonParser.parseString(body).getAsJsonObject();
            var latest = root.getAsJsonObject("latest");
            var entries = new ArrayList<VersionEntry>();
            for (var element : root.getAsJsonArray("versions")) {
                var obj = element.getAsJsonObject();
                entries.add(new VersionEntry(
                    obj.get("id").getAsString(),
                    obj.get("type").getAsString(),
                    obj.get("url").getAsString()));
            }
            return new Manifest(
                entries,
                latest.get("release").getAsString(),
                latest.get("snapshot").getAsString());
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Malformed version manifest at " + VERSION_MANIFEST_URL, e);
        }
    }

    /** Parses a version manifest from JSON (test seam; no network). */
    public static Manifest parseManifest(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var latest = root.getAsJsonObject("latest");
            var entries = new ArrayList<VersionEntry>();
            for (var element : root.getAsJsonArray("versions")) {
                var obj = element.getAsJsonObject();
                entries.add(new VersionEntry(
                    obj.get("id").getAsString(),
                    obj.get("type").getAsString(),
                    obj.get("url").getAsString()));
            }
            return new Manifest(entries,
                latest.get("release").getAsString(),
                latest.get("snapshot").getAsString());
        } catch (PatchEngineException e) {
            throw e;   // already a rendered report; do not bury it
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Malformed version manifest JSON", e);
        }
    }

    /** Parses one version's metadata from JSON (test seam; no network). */
    public static VersionMetadata parseVersionMetadata(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var id = root.get("id").getAsString();
            var javaVersion = root.has("javaVersion")
                ? root.getAsJsonObject("javaVersion").get("majorVersion").getAsInt()
                : 21;

            var downloads = root.getAsJsonObject("downloads");
            var serverJson = downloads.getAsJsonObject("server");
            var server = artifact(serverJson, id + " server jar");

            var libraries = new ArrayList<Library>();
            var librariesArray = root.getAsJsonArray("libraries");
            if (librariesArray != null) {
                for (var element : librariesArray) {
                    var library = toLibrary(element);
                    if (library != null) {
                        libraries.add(library);
                    }
                }
            }
            return new VersionMetadata(id, javaVersion, server, List.copyOf(libraries));
        } catch (PatchEngineException e) {
            throw e;   // already a rendered report, e.g. an unverifiable artifact
        } catch (RuntimeException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Malformed version metadata JSON", e);
        }
    }

    /** One entry of Mojang's version manifest. */
    public record VersionEntry(String id, String type, String url) {
    }

    /** The parsed version manifest. */
    public record Manifest(List<VersionEntry> versions, String latestRelease, String latestSnapshot) {

        /** @throws PatchEngineException naming the closest available versions */
        public VersionEntry find(MinecraftVersion version) {
            var id = version.toString();
            for (var entry : versions) {
                if (entry.id().equals(id)) {
                    return entry;
                }
            }
            throw new PatchEngineException(
                "[VeltisMinecraft] Unknown Minecraft version: " + id
                    + "\n  Source: " + VERSION_MANIFEST_URL
                    + "\n  Available: " + describeAvailable());
        }

        private String describeAvailable() {
            var releases = new ArrayList<String>();
            var snapshots = new ArrayList<String>();
            for (var entry : versions) {
                if ("release".equals(entry.type())) {
                    if (releases.size() < 10) releases.add(entry.id());
                } else if ("snapshot".equals(entry.type()) && snapshots.size() < 3) {
                    snapshots.add(entry.id());
                }
            }
            return "latest release " + latestRelease + ", recent releases " + releases
                + ", latest snapshot " + latestSnapshot + (snapshots.isEmpty() ? "" : " " + snapshots);
        }
    }

    /** A downloadable file with the SHA-1 Mojang published for it. */
    public record Artifact(String path, String url, String sha1, long size) {
    }

    /** A declared library and its resolvable Gradle coordinates. */
    public record Library(String coordinates, String name, Artifact artifact) {
    }

    /** Everything {@link MinecraftDownloader} needs to materialize one version. */
    public record VersionMetadata(
        String id,
        int javaVersion,
        Artifact serverArtifact,
        List<Library> libraries
    ) {
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static Library toLibrary(JsonElement element) {
        var obj = element.getAsJsonObject();
        var downloads = obj.has("downloads") ? obj.getAsJsonObject("downloads") : null;
        if (downloads == null) return null;
        var artifactJson = downloads.has("artifact") ? downloads.getAsJsonObject("artifact") : null;
        if (artifactJson == null) return null;  // natives-only or rule-gated: not needed
        var name = obj.has("name") ? obj.get("name").getAsString() : "";
        return new Library(toCoordinates(name), name, artifact(artifactJson, name));
    }

    /** {@code group:artifact:version} from a library's declared name. */
    static String toCoordinates(String declaredName) {
        // Declared names carry an optional {@code @ext} suffix, an optional
        // classifier, and may be prefixed with the platform they apply to.
        var name = declaredName;
        var ext = "";
        var at = name.indexOf('@');
        if (at >= 0) {
            ext = ":" + name.substring(at + 1);
            name = name.substring(0, at);
        }
        var parts = name.split(":");
        // "linuxx64:org.lwjgl:lwjgl:3.3.3:natives-linux" is a platform-prefixed
        // name. A Maven group id always contains a dot, so a leading segment
        // without one is that prefix rather than a group.
        if (parts.length > 3 && parts[0].indexOf('.') < 0) {
            parts = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        }
        if (parts.length < 3) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Unrecognised library name: " + declaredName);
        }
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ext;
    }

    private static Artifact artifact(JsonObject json, String description) {
        var url = json.get("url").getAsString();
        // Mojang publishes a `path` for libraries but not for the server jar, so
        // the file name is taken from the URL when it is absent. Both shapes end
        // up as a usable relative path.
        var path = json.has("path") && !json.get("path").getAsString().isBlank()
            ? json.get("path").getAsString()
            : fileNameOf(url);
        var sha1 = json.has("sha1") ? json.get("sha1").getAsString() : null;
        var size = json.has("size") ? json.get("size").getAsLong() : -1L;
        if (path.isBlank() || sha1 == null || sha1.isBlank()) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Mojang metadata for " + description
                    + " names neither a file to download nor a SHA-1 to verify it against;"
                    + " refusing to fetch an unverifiable artifact"
                    + "\n  Source: " + url);
        }
        return new Artifact(path, url, sha1, size);
    }

    /** The last path segment of a URL, without any query string. */
    private static String fileNameOf(String url) {
        var tail = url;
        var query = tail.indexOf('?');
        if (query >= 0) {
            tail = tail.substring(0, query);
        }
        var slash = tail.lastIndexOf('/');
        return slash < 0 ? tail : tail.substring(slash + 1);
    }

    private String get(String url, String description) {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "VeltisMC")
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new PatchEngineException(
                    "[VeltisMinecraft] Mojang returned HTTP " + response.statusCode()
                        + " for " + description
                        + "\n  Source: " + url
                        + "\n  Reason: the endpoint rejected the request");
            }
            return response.body();
        } catch (PatchEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Mojang is unreachable; " + description + " could not be fetched"
                    + "\n  Source: " + url
                    + "\n  Expected SHA-1: <unknown>"
                    + "\n  Actual SHA-1: <not downloaded>"
                    + "\n  Reason: " + rootMessage(e)
                    + "\n  Check network access to piston-meta.mojang.com, or prepare the"
                    + " workspace once while online.", e);
        }
    }

    static String rootMessage(Throwable t) {
        var current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        var message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /** Writes the raw version metadata so later runs resolve without the network. */
    public static void cacheMetadata(VeltisWorkspace workspace, String json) {
        try {
            Files.createDirectories(workspace.metadataDirectory());
            var target = workspace.versionMetadataFile();
            var staging = workspace.metadataDirectory().resolve("version.json.staging");
            Files.writeString(staging, json, StandardCharsets.UTF_8);
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to cache version metadata in " + workspace, e);
        }
    }

    /** Writes the library coordinate list Gradle resolves the classpath from. */
    public static void cacheLibraryCoordinates(VeltisWorkspace workspace,
                                              List<Library> libraries) {
        // A TreeSet, not a sorted list: Mojang declares the same module several
        // times with different classifiers, and they all resolve to one Gradle
        // dependency.
        var coordinates = new java.util.TreeSet<String>();
        for (var library : libraries) {
            // Maven coordinates with an extension suffix are not resolvable
            // module dependencies; the artifact path carries the real file.
            var value = library.coordinates();
            if (value.endsWith(".jar") || value.split(":").length != 3) {
                continue;
            }
            coordinates.add(value);
        }
        try {
            Files.createDirectories(workspace.metadataDirectory());
            Files.write(workspace.libraryCoordinatesFile(), coordinates,
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to cache the library coordinate list", e);
        }
    }

    /** Streams a file through SHA-1 without loading it into memory. */
    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    /** Short SHA-256 of a file, used for cache markers. */
    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        // Every JRE is required to ship SHA-1 and SHA-256, so a failure here
        // would mean a broken JRE, not a missing optional algorithm.
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("this JRE does not provide " + algorithm, e);
        }
        var buffer = new byte[1 << 16];
        try (var in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
