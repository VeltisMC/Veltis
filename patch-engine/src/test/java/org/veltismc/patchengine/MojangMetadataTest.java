package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing Mojang's metadata, with no network involved.
 *
 * <p>The version manifest and the per-version metadata are the only place the
 * pipeline learns where Minecraft comes from and what it is supposed to be. If
 * either is read wrongly the build downloads the wrong bytes or cannot verify
 * them, so the shapes Mojang actually publishes are pinned here as literals
 * rather than fixtures that could be edited into agreement with the parser.
 */
class MojangMetadataTest {

    private static final String MANIFEST = """
        {
          "latest": { "release": "26.3", "snapshot": "26.3-w1" },
          "versions": [
            { "id": "26.3", "type": "release",
              "url": "https://piston-meta.mojang.com/v2/versions/26.3.json" },
            { "id": "26.3-w1", "type": "snapshot",
              "url": "https://piston-meta.mojang.com/v2/versions/26.3-w1.json" },
            { "id": "1.21.1", "type": "release",
              "url": "https://piston-meta.mojang.com/v2/versions/1.21.1.json" }
          ]
        }
        """;

    private static final String VERSION_JSON = """
        {
          "id": "26.3",
          "javaVersion": { "majorVersion": 25 },
          "downloads": {
            "server": {
              "sha1": "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
              "size": 62294556,
              "url": "https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar"
            }
          },
          "libraries": [
            {
              "name": "com.mojang:datafixerupper:8.3.1",
              "downloads": {
                "artifact": {
                  "path": "com/mojang/datafixerupper/8.3.1/datafixerupper-8.3.1.jar",
                  "sha1": "4d7b1a2d4d6dbbbd20a92ee5e1e6c0c2b8dbcfb1",
                  "size": 5098,
                  "url": "https://libraries.minecraft.net/com/mojang/datafixerupper/8.3.1/datafixerupper-8.3.1.jar"
                }
              }
            },
            {
              "name": "org.slf4j:slf4j-api:2.0.9",
              "downloads": {
                "artifact": {
                  "path": "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
                  "sha1": "2ea76b59e8b9b4a1cddb35a4b4a1f6d0c1f1e9c3",
                  "size": 34985,
                  "url": "https://libraries.minecraft.net/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl",
              "downloads": { "classifiers": {} }
            },
            {
              "name": "linuxx64:org.lwjgl:lwjgl:3.3.3:natives-linux",
              "downloads": {
                "artifact": {
                  "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
                  "sha1": "9b9dc0d0c1e8a2c9d5b6a3f2e1d0c9b8a7f6e5d",
                  "size": 1000,
                  "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar"
                }
              }
            }
          ]
        }
        """;

    @Test
    void theManifestEndpointIsMojangsV2One() {
        assertEquals("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            MojangMetadata.VERSION_MANIFEST_URL);
        assertTrue(MojangMetadata.VERSION_MANIFEST_URL.contains("piston-meta.mojang.com"));
    }

    @Test
    void aManifestResolvesAKnownVersionToItsMetadataUrl() {
        var manifest = MojangMetadata.parseManifest(MANIFEST);
        assertEquals("26.3", manifest.latestRelease());
        assertEquals(3, manifest.versions().size());

        var entry = manifest.find(MinecraftVersion.parse("26.3"));
        assertEquals("release", entry.type());
        assertEquals("https://piston-meta.mojang.com/v2/versions/26.3.json", entry.url());
    }

    @Test
    void anUnknownVersionIsReportedWithWhatIsAvailable() {
        var manifest = MojangMetadata.parseManifest(MANIFEST);
        var failure = assertThrows(PatchEngineException.class,
            () -> manifest.find(MinecraftVersion.parse("9.9")));
        var msg = failure.getMessage();
        assertTrue(msg.startsWith("[VeltisMinecraft] Unknown Minecraft version: 9.9"), msg);
        assertTrue(msg.contains(MojangMetadata.VERSION_MANIFEST_URL), msg);
        assertTrue(msg.contains("latest release 26.3"), msg);
        assertTrue(msg.contains("1.21.1"), msg);
    }

    @Test
    void versionMetadataYieldsTheServerArtifactAndItsLibraries() {
        var metadata = MojangMetadata.parseVersionMetadata(VERSION_JSON);
        assertEquals("26.3", metadata.id());
        assertEquals(25, metadata.javaVersion());
        assertEquals("33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c", metadata.serverArtifact().sha1());
        assertEquals(62294556L, metadata.serverArtifact().size());
        // Mojang publishes no `path` for the server jar, only for libraries, so the
        // name is taken from the URL. Without this the real 26.3 metadata is
        // rejected as unverifiable.
        assertEquals("server.jar", metadata.serverArtifact().path());

        // The library with no artifact download (rule-gated / natives-only) is
        // dropped rather than turned into an unresolvable dependency.
        assertEquals(3, metadata.libraries().size());
        assertEquals(List.of("com.mojang:datafixerupper:8.3.1",
                "org.slf4j:slf4j-api:2.0.9",
                "org.lwjgl:lwjgl:3.3.3"),
            metadata.libraries().stream().map(MojangMetadata.Library::coordinates).toList(),
            "a platform prefix and a classifier are both dropped: what is left is a"
                + " module Gradle can resolve, and the exact file is fetched by path");
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
            metadata.libraries().get(2).artifact().path(),
            "a library keeps Mojang's own layout, so the fetched tree mirrors theirs");
    }

    @Test
    void declaredLibraryNamesBecomeGradleCoordinates() {
        assertEquals("com.mojang:datafixerupper:8.3.1",
            MojangMetadata.toCoordinates("com.mojang:datafixerupper:8.3.1"));
        assertEquals("org.lwjgl:lwjgl:3.3.3",
            MojangMetadata.toCoordinates("org.lwjgl:lwjgl:3.3.3"));
        assertEquals("org.lwjgl:lwjgl:3.3.3",
            MojangMetadata.toCoordinates("org.lwjgl:lwjgl:3.3.3:natives-linux"),
            "a classifier is not a coordinate segment Gradle can resolve");
        assertEquals("org.example:thing:1.0:jar",
            MojangMetadata.toCoordinates("org.example:thing:1.0@jar"));
        // A platform-prefixed name still yields a resolvable module.
        assertEquals("org.lwjgl:lwjgl:3.3.3",
            MojangMetadata.toCoordinates("linuxx64:org.lwjgl:lwjgl:3.3.3"));
        assertEquals("org.lwjgl:lwjgl:3.3.3",
            MojangMetadata.toCoordinates("linuxx64:org.lwjgl:lwjgl:3.3.3:natives-linux"),
            "this is the form Mojang actually publishes for natives");
    }

    @Test
    void anArtifactWithoutASha1IsRefusedRatherThanDownloaded() {
        var noHash = VERSION_JSON.replace(
            "\"sha1\": \"2ea76b59e8b9b4a1cddb35a4b4a1f6d0c1f1e9c3\"", "\"size\": 10");
        var failure = assertThrows(PatchEngineException.class,
            () -> MojangMetadata.parseVersionMetadata(noHash));
        assertTrue(failure.getMessage().contains("neither a file to download nor a SHA-1"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains("unverifiable artifact"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains(
                "https://libraries.minecraft.net/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"),
            "the report names the artifact that could not be verified: " + failure.getMessage());
    }

    @Test
    void malformedMetadataIsReportedRatherThanThrownAsSomethingElse() {
        var failure = assertThrows(PatchEngineException.class,
            () -> MojangMetadata.parseVersionMetadata("{\"id\": \"26.3\"}"));
        assertTrue(failure.getMessage().contains("Malformed version metadata"),
            failure.getMessage());

        var manifestFailure = assertThrows(PatchEngineException.class,
            () -> MojangMetadata.parseManifest("[]"));
        assertTrue(manifestFailure.getMessage().contains("Malformed version manifest"),
            manifestFailure.getMessage());
    }

    @Test
    void aVersionParsesToItsCanonicalFormAndOrdersNaturally() {
        assertEquals("26.3", MinecraftVersion.parse("26.3").toString());
        assertEquals("1.21.1", MinecraftVersion.parse("1.21.1").toString());
        assertEquals("26.3", MinecraftVersion.parse("26.3.0").toString());
        assertEquals(0, MinecraftVersion.parse("26.3.0").patch(),
            "a trailing zero is dropped, so '26.3' and '26.3.0' are one workspace");
        assertTrue(MinecraftVersion.parse("1.9").compareTo(MinecraftVersion.parse("1.10")) < 0);
        assertEquals(MinecraftVersion.parse("26.3"), MinecraftVersion.parse("26.3.0"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftVersion.parse("26.3-w1"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftVersion.parse("v26.3"));
    }
}
