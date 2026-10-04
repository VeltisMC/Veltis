package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decompile cache must prove what it holds, not just that it exists.
 *
 * <p>Decompiling takes minutes. Skipping it when the workspace already has a
 * tree is only safe if the tree is provably the decompile of the artifact being
 * built. Every state that would make skipping wrong — a marker for a different
 * version, a marker for a different jar, sources with no marker, a marker with no
 * sources — has to force a re-decompile, and this test covers all of them.
 *
 * <p>The check itself is exercised directly rather than by decompiling: the
 * property under test is a decision about on-disk state, and running a real
 * decompile would test Vineflower instead.
 */
class MinecraftDecompilerCacheTest {

    @TempDir
    Path tmp;

    private static final String SERVER_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c";

    private VeltisWorkspace workspace(String name) {
        return TestWorkspace.create(tmp.resolve(name)).workspace();
    }

    private static MojangMetadata.VersionMetadata metadata(String id, String serverSha1) {
        return new MojangMetadata.VersionMetadata(id, 25,
            new MojangMetadata.Artifact("server.jar", "https://example.invalid/server.jar",
                serverSha1, 62294556L),
            List.of(
                new MojangMetadata.Library("com.mojang:datafixerupper:8.3.1", "datafixerupper",
                    new MojangMetadata.Artifact("p", "u", "s", 1L))));
    }

    /** Writes a source file and the marker that claims this tree is the artifact's. */
    private void writeCachedTree(VeltisWorkspace workspace, MojangMetadata.VersionMetadata meta) {
        try {
            var source = workspace.sourceDirectory().resolve("net/minecraft/Server.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Server {}\n");
            Files.writeString(workspace.decompileMarker(), marker(workspace, meta),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The marker the engine would write for this workspace.
     *
     * <p>Built the same way the engine builds it — including the decompiler
     * settings — rather than transcribed, so the test cannot drift away from the
     * format it is about.
     */
    private static String marker(VeltisWorkspace workspace, MojangMetadata.VersionMetadata meta)
        throws Exception {
        Files.createDirectories(workspace.vanillaDirectory());
        Files.writeString(workspace.widenedServerJar(), "not really a jar\n",
            StandardCharsets.UTF_8);
        return "minecraft=" + meta.id() + "\n"
            + "serverSha1=" + meta.serverArtifact().sha1() + "\n"
            + "libraries=" + meta.libraries().size() + "\n"
            + "decompileInputSha1=" + MojangMetadata.sha1(workspace.widenedServerJar()) + "\n"
            + decompilerSettings();
    }

    private static String decompilerSettings() {
        var settings = new StringBuilder();
        MinecraftDecompiler.OPTIONS().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(e -> settings.append("decompiler.").append(e.getKey())
                .append('=').append(e.getValue()).append('\n'));
        return settings.toString();
    }

    @Test
    void aTreeThatBelongsToThisArtifactIsReused() {
        var workspace = workspace("valid");
        var meta = metadata("26.3", SERVER_SHA1);
        writeCachedTree(workspace, meta);
        assertTrue(MinecraftDecompiler.isValid(workspace, meta));
    }

    @Test
    void aTreeFromADifferentVersionIsNotReused() {
        var workspace = workspace("other-version");
        writeCachedTree(workspace, metadata("1.21.1", SERVER_SHA1));
        assertFalse(MinecraftDecompiler.isValid(workspace, metadata("26.3", SERVER_SHA1)),
            "a version bump must invalidate the cached decompile");
    }

    @Test
    void aTreeFromADifferentServerJarIsNotReused() {
        var workspace = workspace("other-jar");
        writeCachedTree(workspace, metadata("26.3", "0123456789abcdef0123456789abcdef01234567"));
        assertFalse(MinecraftDecompiler.isValid(workspace, metadata("26.3", SERVER_SHA1)),
            "patches authored against different bytecode must not be applied to it");
    }

    @Test
    void aTreeWithADifferentLibrarySetIsNotReused() {
        var workspace = workspace("other-libs");
        var fewer = metadata("26.3", SERVER_SHA1);
        var more = new MojangMetadata.VersionMetadata("26.3", 25,
            fewer.serverArtifact(),
            List.of(fewer.libraries().get(0),
                new MojangMetadata.Library("org.slf4j:slf4j-api:2.0.9", "slf4j",
                    new MojangMetadata.Artifact("p", "u", "s", 1L))));
        writeCachedTree(workspace, fewer);
        assertFalse(MinecraftDecompiler.isValid(workspace, more));
    }

    @Test
    void aTreeDecompiledFromADifferentWidenedJarIsNotReused() {
        var workspace = workspace("other-widened-jar");
        var meta = metadata("26.3", SERVER_SHA1);
        writeCachedTree(workspace, meta);
        try {
            // Same version, same server jar, same libraries — but the widening
            // ran again and produced a different input.
            Files.writeString(workspace.widenedServerJar(), "a different jar\n",
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, meta),
            "a tree decompiled from a superseded widening must not be reused");
    }

    @Test
    void aTreeDecompiledWithDifferentOptionsIsNotReused() {
        var workspace = workspace("other-options");
        var meta = metadata("26.3", SERVER_SHA1);
        writeCachedTree(workspace, meta);
        assertTrue(MinecraftDecompiler.isValid(workspace, meta), "precondition");
        try {
            // Identical version, jar, widening and libraries: the only thing that
            // changed is a decompiler setting, which is just as capable of changing
            // the emitted source as a different input jar is.
            var option = MinecraftDecompiler.OPTIONS().keySet().iterator().next();
            var altered = marker(workspace, meta)
                .replaceAll("(?m)^decompiler\\." + java.util.regex.Pattern.quote(option) + "=.*$",
                    java.util.regex.Matcher.quoteReplacement("decompiler." + option + "=9999"));
            Files.writeString(workspace.decompileMarker(), altered, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, meta),
            "a tree decompiled under different options must not be reused");
    }

    @Test
    void aTreeWhoseWidenedJarIsGoneIsNotReused() {
        var workspace = workspace("widened-jar-gone");
        var meta = metadata("26.3", SERVER_SHA1);
        writeCachedTree(workspace, meta);
        try {
            Files.delete(workspace.widenedServerJar());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, meta),
            "with the decompile input gone the tree cannot be proven to match it");
    }

    @Test
    void sourcesWithoutAMarkerAreNotReused() {
        var workspace = workspace("no-marker");
        writeCachedTree(workspace, metadata("26.3", SERVER_SHA1));
        try {
            Files.delete(workspace.decompileMarker());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, metadata("26.3", SERVER_SHA1)),
            "a tree with no marker is a tree that was never finished");
    }

    @Test
    void aMarkerWithoutSourcesIsNotReused() {
        var workspace = workspace("no-sources");
        writeCachedTree(workspace, metadata("26.3", SERVER_SHA1));
        try (var walk = Files.walk(workspace.sourceDirectory())) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, metadata("26.3", SERVER_SHA1)),
            "a marker whose sources are gone is not a usable cache");
    }

    @Test
    void aDamagedMarkerIsNotReused() {
        var workspace = workspace("damaged-marker");
        writeCachedTree(workspace, metadata("26.3", SERVER_SHA1));
        try {
            Files.writeString(workspace.decompileMarker(), "not a marker at all\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertFalse(MinecraftDecompiler.isValid(workspace, metadata("26.3", SERVER_SHA1)));
    }

    @Test
    void decompilingWithoutAWidenedServerJarSaysWhichStepToRun() {
        var workspace = workspace("no-jar");
        var failure = assertThrows(PatchEngineException.class,
            () -> new MinecraftDecompiler().decompile(workspace, metadata("26.3", SERVER_SHA1)));

        var msg = failure.getMessage();
        assertTrue(msg.contains("No access-widened server jar to decompile"), msg);
        assertTrue(msg.contains("widenServerJarAccess"), msg);
        assertTrue(msg.contains("26.3"), msg);
    }

    @Test
    void theMarkerLivesInsideTheSourceTreeSoItTravelsWithIt() {
        var workspace = workspace("marker-location");
        assertEquals(workspace.sourceDirectory().resolve(".decompile-marker"),
            workspace.decompileMarker(),
            "the marker is build state inside the tree it describes, so copying the"
                + " tree without it correctly invalidates the cache");
        assertEquals(workspace.metadataDirectory().resolve("libraries.txt"),
            workspace.libraryCoordinatesFile());
        assertEquals(workspace.buildDirectory().resolve("patch-targets.txt"),
            workspace.patchTargetsFile());
    }
}
