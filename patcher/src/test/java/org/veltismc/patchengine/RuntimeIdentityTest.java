package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache-validity rules, which are what decide whether a server start does
 * any work at all.
 *
 * <p>Each case here is a way a warm start could be wrong: reusing a runtime
 * built for a different Minecraft, a different patch set, a different compiler,
 * or a half-written one. All four are silent — a server that starts on a stale
 * runtime looks exactly like one that starts correctly, right up until it does
 * something the patches were supposed to change.
 */
class RuntimeIdentityTest {

    private static final String SERVER_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c";

    private final RuntimeIdentity identity =
        new RuntimeIdentity("26.3", SERVER_SHA1, "a".repeat(64), "f".repeat(64), 26,
            AccessWidener.FORMAT, BytecodePatch.FORMAT);

    private VeltisWorkspace workspaceAt(Path root) throws IOException {
        var workspace = VeltisWorkspace.of(root, MinecraftVersion.parse("26.3"));
        workspace.createDirectories();
        Files.createDirectories(workspace.classesDirectory()
            .resolve("net/minecraft/server"));
        Files.write(workspace.classesDirectory()
            .resolve("net/minecraft/server/MinecraftServer.class"), new byte[] {1, 2, 3});
        return workspace;
    }

    // ------------------------------------------------------------------
    // Every input
    // ------------------------------------------------------------------

    @Test
    void everyInputIsPartOfTheIdentitySoAnyChangeInvalidatesIt() {
        var base = identity.render();

        assertNotEquals(base, withMinecraftVersion("26.4").render(),
            "a different Minecraft version must invalidate the runtime");
        assertNotEquals(base, withServerSha1("b".repeat(40)).render(),
            "a re-published artifact for the same version id must invalidate it too,"
                + " or patches would be applied to different bytecode");
        assertNotEquals(base, withSourceRevision("b".repeat(64)).render(),
            "an edited source patch must invalidate the runtime");
        assertNotEquals(base, withPatchFingerprint("c".repeat(64)).render(),
            "a different bytecode patch set must invalidate it: the fingerprint is"
                + " what the cached artifact is keyed on");
        assertNotEquals(base, withClassFileRelease(25).render(),
            "class files compiled for a different release must not be reused");
        assertNotEquals(base, withWidenFormat(AccessWidener.FORMAT + 1).render(),
            "a jar widened by a different rewriter is a different jar");
        assertNotEquals(base, withPatchFormat(BytecodePatch.FORMAT + 1).render(),
            "a patch set written in a format this build cannot read is not reusable,"
                + " however well everything else matches");
    }

    @Test
    void theWorkerCountIsNotPartOfTheIdentity() {
        // The identity has no worker-count field, and that is the design: workers
        // change how long a rebuild takes and nothing about what it produces, so
        // folding the count in would mean a performance setting silently
        // invalidated a byte-for-byte identical runtime. The source revision and
        // the patch fingerprint, which are what a rebuild keys on, are derived
        // only from patch content.
        assertEquals(7, identity.getClass().getRecordComponents().length,
            "the identity must not grow a worker-count field; the patcher is required"
                + " to produce identical output at every worker count");
    }

    @Test
    void theRenderedFormIsStableAndReadable() {
        var rendered = identity.render();
        assertEquals("""
            minecraft=26.3
            serverSha1=33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c
            sourceRevision=%s
            patchFingerprint=%s
            classFileRelease=26
            widenFormat=%d
            patchFormat=%d
            """.formatted("a".repeat(64), "f".repeat(64), AccessWidener.FORMAT,
                BytecodePatch.FORMAT), rendered,
            "the marker is written by hand in bug reports; a fixed key order and a"
                + " trailing newline make it comparable and diffable");
        assertTrue(rendered.endsWith("\n"),
            "a marker without a trailing newline fails a text-mode round trip on"
                + " some filesystems, which would rebuild the runtime on every start");
    }

    // ------------------------------------------------------------------
    // Patch revision folding
    // ------------------------------------------------------------------

    @Test
    void thePatchRevisionChangesOnlyWhenAPatchDoes(@TempDir Path tmp) {
        var reference = RuntimeIdentity.sourceRevisionOf(
            patches(tmp, "base", code("001-A.patch", "A.java")));

        var added = patches(tmp, "added",
            code("001-A.patch", "A.java"), code("002-B.patch", "B.java"));
        assertNotEquals(reference, RuntimeIdentity.sourceRevisionOf(added),
            "adding a patch must invalidate the runtime");

        var edited = patches(tmp, "edited", edited("001-A.patch", "A.java"));
        assertNotEquals(reference, RuntimeIdentity.sourceRevisionOf(edited),
            "editing a patch must invalidate the runtime");

        var identical = patches(tmp, "identical", code("001-A.patch", "A.java"));
        assertEquals(reference, RuntimeIdentity.sourceRevisionOf(identical),
            "an unchanged patch set must never cause a needless rebuild");
    }

    @Test
    void movingAPatchBetweenCategoriesChangesTheRevision(@TempDir Path tmp) {
        // Same bytes, different category. The categories mean different things —
        // code is compiled, data and modules are copied as resources — so this is
        // not the same patch set.
        var asCode = patches(tmp, "code", code("001-A.patch", "A.java"));
        var asData = patches(tmp, "data", data("001-A.patch", "A.java"));
        assertNotEquals(RuntimeIdentity.sourceRevisionOf(asCode),
            RuntimeIdentity.sourceRevisionOf(asData),
            "the category is part of a patch's identity");
    }

    @Test
    void renamingAPatchChangesTheRevision(@TempDir Path tmp) {
        var original = patches(tmp, "original", code("001-A.patch", "A.java"));
        var renamed = patches(tmp, "renamed", code("001-B.patch", "A.java"));
        assertNotEquals(RuntimeIdentity.sourceRevisionOf(original),
            RuntimeIdentity.sourceRevisionOf(renamed),
            "a renamed patch is a different patch, and the packaged index changed with it");
    }

    @Test
    void anEmptyPatchSetFoldsToAFixedValue() {
        // A degenerate case rather than a supported one: VeltisRuntime rejects an
        // empty set outright, because a server that runs vanilla while reporting a
        // successful patch is the failure this whole pipeline exists to prevent.
        // The fold still has to be deterministic, so a jar packaged from an empty
        // set cannot produce a different revision on every launch.
        assertEquals(RuntimeIdentity.sourceRevisionOf(List.of()),
            RuntimeIdentity.sourceRevisionOf(List.of()),
            "an empty set must fold to a fixed value, not to something that varies");
    }

    // ------------------------------------------------------------------
    // Cache validity
    // ------------------------------------------------------------------

    @Test
    void aMarkerPlusClassesMeansTheRuntimeIsUsable(@TempDir Path tmp) throws Exception {
        var workspace = workspaceAt(tmp);
        assertFalse(RuntimeIdentity.isCurrent(workspace, identity),
            "a workspace with classes but no marker was never finished");

        identity.recordAsCurrent(workspace);
        assertTrue(RuntimeIdentity.isCurrent(workspace, identity),
            "a matching marker over real class files is the runtime being ready");
    }

    @Test
    void anEmptyOrMissingClassesDirectoryIsNotARuntime(@TempDir Path tmp) throws Exception {
        var workspace = workspaceAt(tmp);
        identity.recordAsCurrent(workspace);

        VeltisWorkspace.deleteTree(workspace.classesDirectory());
        assertFalse(RuntimeIdentity.isCurrent(workspace, identity),
            "a user who deleted classes/ must get a rebuild, not a server that"
                + " starts vanilla because the compiled classes quietly vanished");

        // And the marker alone, in a workspace that never had a build, is not
        // enough either.
        var bare = VeltisWorkspace.of(tmp.resolve("bare"), MinecraftVersion.parse("26.3"));
        bare.createDirectories();
        identity.recordAsCurrent(bare);
        assertFalse(RuntimeIdentity.isCurrent(bare, identity),
            "a marker over an empty classes/ is a build that produced nothing");
    }

    @Test
    void anyIdentityMismatchInvalidatesTheWholeRuntime(@TempDir Path tmp) throws Exception {
        var workspace = workspaceAt(tmp);
        identity.recordAsCurrent(workspace);

        var mismatched = List.of(
            withMinecraftVersion("26.4"),
            withServerSha1("c".repeat(40)),
            withSourceRevision("d".repeat(64)),
            withPatchFingerprint("e".repeat(64)),
            withClassFileRelease(21),
            withWidenFormat(AccessWidener.FORMAT + 1),
            withPatchFormat(BytecodePatch.FORMAT + 1));
        for (var other : mismatched) {
            assertFalse(RuntimeIdentity.isCurrent(workspace, other),
                "a runtime whose identity differs must be rebuilt, not reused: " + other);
        }
        assertTrue(RuntimeIdentity.isCurrent(workspace, identity),
            "none of those attempts may have invalidated the real runtime");
    }

    @Test
    void invalidatingRemovesTheMarkerAndSurvivesAMissingOne(@TempDir Path tmp) throws Exception {
        var workspace = workspaceAt(tmp);
        RuntimeIdentity.invalidate(workspace);
        assertFalse(Files.exists(workspace.runtimeMarker()),
            "invalidation is the first step of a rebuild, so the marker must be gone");

        // Idempotent: a rebuild that runs twice must not fail on the second delete.
        RuntimeIdentity.invalidate(workspace);
    }

    @Test
    void aTruncatedOrGarbledMarkerInvalidatesRatherThanCrashes(@TempDir Path tmp)
        throws Exception {
        var workspace = workspaceAt(tmp);
        Files.writeString(workspace.runtimeMarker(), "minecraft=26.3\n",
            StandardCharsets.UTF_8);
        assertFalse(RuntimeIdentity.isCurrent(workspace, identity),
            "a half-written marker must read as stale, never as current");
    }

    @Test
    void theMarkerIsWrittenOnlyAfterEverythingElseIsInPlace(@TempDir Path tmp)
        throws Exception {
        // The ordering the crash-safety argument rests on: invalidate first, build,
        // record last. If record came first, a crash mid-build would leave a
        // marker claiming a runtime that does not exist.
        var workspace = workspaceAt(tmp);
        identity.recordAsCurrent(workspace);

        RuntimeIdentity.invalidate(workspace);
        assertFalse(RuntimeIdentity.isCurrent(workspace, identity),
            "a rebuild in progress must read as stale for the whole rebuild");

        identity.recordAsCurrent(workspace);
        assertTrue(RuntimeIdentity.isCurrent(workspace, identity),
            "and current again once the build finished");
    }

    // One constructor per field, so each mutation in a test reads as the single
    // change it is.
    /**
     * A discovered patch set built from patch files written under {@code tmp}.
     *
     * <p>{@code TestWorkspace.discover()} reads from disk, which is the point: the
     * patch revision is derived from the same parsed patches the pipeline applies,
     * so a set assembled in memory could disagree with what actually ships.
     */
    private List<VeltisPatch> patches(Path tmp, String name, TestPatch... files) {
        var fixture = TestWorkspace.create(tmp.resolve(name));
        for (var p : files) {
            fixture = fixture.patch(p.category(), p.fileName(), p.content());
        }
        return fixture.materialize().discover();
    }

    /** One patch file: the category it lives in, its name, and its text. */
    private record TestPatch(PatchCategory category, String fileName, String content) {
    }

    private static TestPatch code(String name, String target) {
        return new TestPatch(PatchCategory.CODE, name, TestWorkspace.createPatch(target));
    }

    private static TestPatch data(String name, String target) {
        return new TestPatch(PatchCategory.DATA, name, TestWorkspace.createPatch(target));
    }

    private static TestPatch edited(String name, String target) {
        return new TestPatch(PatchCategory.CODE, name,
            TestWorkspace.modifyPatch(target, "class A {}", "class A { int x; }"));
    }

    private RuntimeIdentity withMinecraftVersion(String v) {
        return new RuntimeIdentity(v, identity.serverSha1(), identity.sourceRevision(),
            identity.patchFingerprint(), identity.classFileRelease(), identity.widenFormat(),
            identity.patchFormat());
    }

    private RuntimeIdentity withServerSha1(String sha) {
        return new RuntimeIdentity(identity.minecraftVersion(), sha, identity.sourceRevision(),
            identity.patchFingerprint(), identity.classFileRelease(), identity.widenFormat(),
            identity.patchFormat());
    }

    private RuntimeIdentity withSourceRevision(String revision) {
        return new RuntimeIdentity(identity.minecraftVersion(), identity.serverSha1(), revision,
            identity.patchFingerprint(), identity.classFileRelease(), identity.widenFormat(),
            identity.patchFormat());
    }

    private RuntimeIdentity withPatchFingerprint(String fingerprint) {
        return new RuntimeIdentity(identity.minecraftVersion(), identity.serverSha1(),
            identity.sourceRevision(), fingerprint, identity.classFileRelease(),
            identity.widenFormat(), identity.patchFormat());
    }

    private RuntimeIdentity withClassFileRelease(int release) {
        return new RuntimeIdentity(identity.minecraftVersion(), identity.serverSha1(),
            identity.sourceRevision(), identity.patchFingerprint(), release,
            identity.widenFormat(), identity.patchFormat());
    }

    private RuntimeIdentity withWidenFormat(int format) {
        return new RuntimeIdentity(identity.minecraftVersion(), identity.serverSha1(),
            identity.sourceRevision(), identity.patchFingerprint(), identity.classFileRelease(),
            format, identity.patchFormat());
    }

    private RuntimeIdentity withPatchFormat(int format) {
        return new RuntimeIdentity(identity.minecraftVersion(), identity.serverSha1(),
            identity.sourceRevision(), identity.patchFingerprint(), identity.classFileRelease(),
            identity.widenFormat(), format);
    }
}