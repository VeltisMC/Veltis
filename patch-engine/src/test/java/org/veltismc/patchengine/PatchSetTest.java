package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The development source patch set: which patches exist, and in what order they
 * apply.
 *
 * <p>Only the directory form is tested here, because the directory form is all
 * that exists. The packaged-source form — the same patches read out of the
 * launcher jar through an index file — was removed along with the runtime's
 * ability to apply source patches at all: a running server has no decompiler and
 * no compiler, so a text diff inside {@code server.jar} would have been a second
 * patch representation nothing could use. What ships is
 * {@link BytecodePatch}; what stays here is the part Git depends on.
 */
class PatchSetTest {

    private static final String CODE_PATCH = """
        --- a/net/minecraft/server/MinecraftServer.java
        +++ b/net/minecraft/server/MinecraftServer.java
        @@ -1,1 +1,2 @@
         class MinecraftServer {
        +    int patched;
        """;

    private static final String DATA_PATCH = """
        --- /dev/null
        +++ b/data/veltis/config.json
        @@ -0,0 +1,1 @@
        +{"veltis": true}
        """;

    private static final String MODULE_PATCH = """
        --- /dev/null
        +++ b/org/veltismc/module.txt
        @@ -0,0 +1,1 @@
        +core
        """;

    /** Writes a patch directory and returns its root. */
    private static Path patchesDirectory(Path root) throws IOException {
        write(root.resolve("code/001-Server.patch"), CODE_PATCH);
        write(root.resolve("code/002-Commands.patch"), CODE_PATCH.replace(
            "MinecraftServer.java", "commands/Commands.java"));
        write(root.resolve("data/001-Config.patch"), DATA_PATCH);
        write(root.resolve("modules/001-Core.patch"), MODULE_PATCH);
        return root;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Order
    // ------------------------------------------------------------------

    @Test
    void theOrderIsCodeThenDataThenModulesThenByName(@TempDir Path tmp) throws Exception {
        var patches = patchesDirectory(tmp.resolve("src"));
        var discovered = PatchSet.fromDirectory(patches, TestWorkspace.VERSION_ID, new PatchStats());

        assertEquals(List.of("code", "code", "data", "modules"),
            discovered.stream().map(p -> p.category().directoryName()).toList(),
            "category order is code, data, modules — it is what decides whether a code"
                + " patch sees a module patch's output");

        var codeNames = discovered.stream()
            .filter(p -> p.category() == PatchCategory.CODE)
            .map(VeltisPatch::name).toList();
        assertEquals(codeNames.stream().sorted().toList(), codeNames,
            "within a category the order is by name, so an added patch lands where its"
                + " number says it should");
    }

    @Test
    void discoveryIsIndependentOfFilesystemIterationOrder(@TempDir Path tmp) throws Exception {
        // The same set discovered twice must fold to one revision, or every
        // second build would rebuild a runtime that was already correct. Two
        // roots written in different orders is as close to "different iteration
        // order" as a single filesystem will give us without reaching into the
        // JDK.
        var first = patchesDirectory(tmp.resolve("one"));
        var second = patchesDirectory(tmp.resolve("two"));
        assertEquals(RuntimeIdentity.sourceRevisionOf(
                PatchSet.fromDirectory(first, TestWorkspace.VERSION_ID, new PatchStats())),
            RuntimeIdentity.sourceRevisionOf(
                PatchSet.fromDirectory(second, TestWorkspace.VERSION_ID, new PatchStats())),
            "an identical patch set must fold to one revision, in any order it was"
                + " discovered in");
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void anEmptyPatchDirectoryIsALegitimateSet(@TempDir Path tmp) throws Exception {
        // A Shulker/ directory that exists but holds nothing is a real state — a
        // project that has not written its first patch yet — and it must not be
        // confused with a Shulker/ directory that is not there at all. The second
        // is the dangerous one: it is what a wrong working directory, a missing
        // jar resource or a bad --patches argument looks like, and the
        // difference between "no patches" and "no patch set" is the difference
        // between a VeltisMC server and a vanilla one wearing its name.
        var empty = tmp.resolve("empty").resolve("Shulker");
        Files.createDirectories(empty);
        assertEquals(List.of(), PatchSet.fromDirectory(empty, TestWorkspace.VERSION_ID,
                new PatchStats()),
            "an existing but empty patch set is a legitimate empty set");

        assertThrows(PatchEngineException.class,
            () -> PatchSet.fromDirectory(tmp.resolve("nothing-here"),
                TestWorkspace.VERSION_ID, new PatchStats()),
            "a missing patch root must fail: the alternative is a silently vanilla server");
    }

    @Test
    void onlyTheDirectoryFormExists() {
        // The runtime source-patch loader is what this guards against coming
        // back. A method taking a ClassLoader would mean the launcher could once
        // again be handed source patches it has no way to apply, and the two
        // patch representations — one Git's, one the server's — would drift
        // apart without anyone noticing until a distributed server ran a
        // different runtime than the build verified.
        var loaderShaped = List.of(PatchSet.class.getDeclaredMethods()).stream()
            .filter(m -> !m.isSynthetic())
            .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
            .filter(m -> java.util.Arrays.stream(m.getParameterTypes())
                .anyMatch(p -> ClassLoader.class.isAssignableFrom(p)))
            .map(java.lang.reflect.Method::getName)
            .toList();
        assertEquals(List.of(), loaderShaped,
            "PatchSet must only read a checkout: " + loaderShaped);
    }

    @Test
    void theOnlyEntryPointIsTheDirectoryForm() {
        var names = List.of(PatchSet.class.getDeclaredMethods()).stream()
            .filter(m -> !m.isSynthetic())
            .map(java.lang.reflect.Method::getName)
            .sorted()
            .toList();
        assertEquals(List.of("fromDirectory"), names,
            "a second entry point here would be a second way to get source patches,"
                + " and a running server cannot apply the one it has");
    }
}
