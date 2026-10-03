package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classpath the server runs on and the guard that reads it back.
 *
 * <p>Ordering a classpath is not proof that it was honoured, which is why
 * {@link VeltisRuntime#verifyPatchedClasses} exists. These tests cover both
 * halves of that argument: that the classpath contains exactly what it claims
 * to, and that the guard fails — loudly, with the class named — when what is
 * served is not what was compiled.
 *
 * <p>Everything here is built from real compiled classes and a real jar. A guard
 * tested against stubs proves only that the stub agrees with the guard, so every
 * fixture compiles a class, packages it through the same method the build uses,
 * and then loads it.
 */
class VeltisRuntimeTest {

    private static final String SERVER = "net/minecraft/server/MinecraftServer.java";
    private static final String SERVER_CLASS = "net/minecraft/server/MinecraftServer.class";

    /**
     * The patch set revision these fixtures record. Not derived from a real patch
     * set, because the jars under test are built directly rather than by a
     * pipeline run; what is asserted is that a recorded revision is honoured and
     * a different one is refused, not how it is computed (that is
     * {@code RuntimeIdentityTest}'s job).
     */
    private static final String FIXTURE_REVISION = "fixture-revision";

    /**
     * A compilable class in package {@code net.minecraft.server}.
     *
     * <p>Package placement is not incidental: the guard resolves the compiled class
     * by name against a loader that also has a vanilla class of the same name, and
     * a test in the default package would not exercise the shadowing at all.
     */
    private static String source(String className, String body) {
        return "package net.minecraft.server;\n"
            + "public class " + className + " {\n"
            + "    public static String marker() { return \"" + body + "\"; }\n"
            + "}\n";
    }

    /**
     * A workspace whose single patched class has been compiled and packaged into
     * {@code veltis-server.jar}, exactly as a build does it.
     *
     * @param vanillaMarker  the marker compiled into the vanilla baseline
     * @param patchedMarker  the marker compiled into the patched class
     * @param packageRuntime whether to write the jar; tests about an absent jar
     *                       need it not to be there
     */
    private VeltisRuntime fixture(Path tmp, String vanillaMarker, String patchedMarker,
                                  boolean packageRuntime) throws Exception {
        var workspace = VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION);
        workspace.createDirectories();

        // The patcher records the target manifest the packaging step reads its
        // class list from, so it is written here exactly as the patcher writes it.
        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(workspace.patchTargetsFile(), "code\t" + SERVER + "\n");

        var patchedSource = workspace.patchedDirectory().resolve(SERVER);
        Files.createDirectories(patchedSource.getParent());
        Files.writeString(patchedSource, source("MinecraftServer", patchedMarker),
            StandardCharsets.UTF_8);

        var vanilla = compileInto(workspace, "vanilla-src", "MinecraftServer", vanillaMarker);
        Files.createDirectories(workspace.vanillaDirectory());
        writeJarWith(workspace.widenedServerJar(), SERVER_CLASS, vanilla);
        // The canary needs a vanilla classes jar to put first; a build produces it
        // when it lifts the bundler's inner jar out.
        writeJarWith(workspace.vanillaClassesJar(), SERVER_CLASS, vanilla);
        writeArtifact(workspace);

        new PatchedSourceCompiler(Runtime.version().feature())
            .compile(workspace, List.of(patchedSource));

        var runtime = runtimeFor(workspace);
        if (packageRuntime) {
            packageFixture(runtime);
        }
        return runtime;
    }

    /**
     * Generates the fixture's bytecode patch set from its compiled classes and
     * applies it — the same two calls {@link VeltisRuntime#prepare()} makes for a
     * checkout, with the inputs supplied by the fixture instead of by the
     * pipeline.
     *
     * <p>Deliberately not an imitation. Packaging the jar some other way would
     * mean every assertion here is about a jar that no build ever produced; going
     * through the generator and the applier is what makes the fixture's jar the
     * same artifact a server operator would receive.
     */
    private static void packageFixture(VeltisRuntime runtime) {
        var workspace = runtime.workspace();
        String serverSha1;
        BytecodePatch patch;
        try {
            serverSha1 = MojangMetadata.sha1(workspace.vanillaServerJar());
            patch = BytecodePatch.read(BytecodePatchGenerator
                .generate(workspace, TestWorkspace.VERSION, serverSha1,
                    Runtime.version().feature())
                .file());
        } catch (IOException e) {
            throw new IllegalStateException(
                "the fixture must write " + workspace.vanillaServerJar() + " first", e);
        }
        runtime.applyBytecodePatch(patch,
            runtime.identityFor(patch.metadata(), FIXTURE_REVISION));
    }

    private VeltisRuntime fixture(Path tmp) throws Exception {
        return fixture(tmp, "vanilla", "patched", true);
    }

    /**
     * The two things a warm start hashes and finds: Mojang's artifact and at
     * least one library. Both are written as ordinary files, because validation
     * has to compare against something true rather than against a hard-coded
     * digest the fixture also hard-coded.
     */
    private static void writeArtifact(VeltisWorkspace workspace) throws IOException {
        Files.createDirectories(workspace.vanillaServerJar().getParent());
        Files.writeString(workspace.vanillaServerJar(), "the bytes Mojang published\n",
            StandardCharsets.UTF_8);
        Files.createDirectories(workspace.librariesDirectory());
        // A real, if empty, jar. The compile classpath is the library tree, and
        // javac opens every entry on it — a file that merely exists satisfies a
        // presence check and then fails compilation with a complaint about zip
        // headers, which says nothing about what the test is actually about.
        var library = workspace.librariesDirectory().resolve("fixture-library.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(library))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * The identity the fixture's artifact records: built by the runtime itself,
     * from the patch set the fixture just generated, so a test can never drift
     * from what the applier wrote.
     */
    private static RuntimeIdentity identityFor(VeltisRuntime runtime) {
        return runtime.identityFor(fixturePatch(runtime).metadata(), FIXTURE_REVISION);
    }

    /** The patch set the fixture packaged, re-read as a validation would. */
    private static BytecodePatch fixturePatch(VeltisRuntime runtime) {
        var file = runtime.workspace().bytecodePatchFile();
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("the fixture must generate " + file + " first");
        }
        return BytecodePatch.read(file);
    }

    private static VeltisRuntime runtimeFor(VeltisWorkspace workspace) {
        return VeltisRuntime.fromDirectory(workspace.projectDirectory(),
            TestWorkspace.VERSION, workspace.patchesDirectory(), 1,
            Runtime.version().feature());
    }

    /**
     * Compiles one source outside the workspace's own trees and returns its bytes.
     *
     * <p>Deliberately outside: the vanilla class has to be produced by a separate
     * compilation from the patched one, or "identical to vanilla" could not be
     * asserted at all — the fixture would be comparing a class file with itself.
     */
    private static byte[] compileInto(VeltisWorkspace workspace, String directory,
                                      String className, String marker) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        var scratch = workspace.vanillaDirectory().resolve(directory);
        var packageDir = scratch.resolve("net/minecraft/server");
        Files.createDirectories(packageDir);
        var sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source(className, marker), StandardCharsets.UTF_8);

        var output = scratch.resolve("out");
        var code = compiler.run(null, null, null,
            "-proc:none", "-nowarn",
            "--release", Integer.toString(Runtime.version().feature()),
            "-d", output.toString(), sourceFile.toString());
        assertEquals(0, code, "the test fixture source must compile");
        return Files.readAllBytes(output.resolve("net/minecraft/server/" + className + ".class"));
    }

    /**
     * Compiles one source file into {@code outDir}, leaving the class files there
     * for a test to jar up however it wants.
     */
    private static void compileSource(Path sourceDir, Path outDir, String fileName,
                                      String source) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK");
        Files.createDirectories(sourceDir);
        Files.createDirectories(outDir);
        var sourceFile = sourceDir.resolve(fileName);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        var code = compiler.run(null, null, null,
            "-proc:none", "-nowarn",
            "--release", Integer.toString(Runtime.version().feature()),
            "-d", outDir.toString(), sourceFile.toString());
        assertEquals(0, code, "the test fixture source must compile");
    }

    /** A jar holding every class file under {@code outDir}, in their own packages. */
    private static void writeJarFromTree(Path jar, Path outDir) throws IOException {
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var walk = Files.walk(outDir)) {
                for (var path : walk.filter(Files::isRegularFile).toList()) {
                    var name = outDir.relativize(path).toString().replace('\\', '/');
                    out.putNextEntry(new JarEntry(name));
                    out.write(Files.readAllBytes(path));
                    out.closeEntry();
                }
            }
        }
    }

    private static void writeJarWith(Path jar, String entryName, byte[] content)
        throws IOException {
        Files.createDirectories(jar.getParent());
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content);
            out.closeEntry();
        }
    }

    // ------------------------------------------------------------------
    // The classpath
    // ------------------------------------------------------------------

    @Test
    void theRuntimeArtifactIsTheOnlyMinecraftClassPathEntry(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var classpath = runtime.classpath(null);

        assertEquals(runtime.workspace().veltisServerJar(), classpath.get(0),
            "the artifact is the server's Minecraft classes; nothing may come before it, or"
                + " something else would decide what net.minecraft.server.MinecraftServer is");

        assertFalse(classpath.contains(runtime.workspace().classesDirectory()),
            "loose compiled classes must not be a classpath entry: they are build state, and"
                + " a directory the guard can see is a directory a stale one can hide in");
        assertFalse(classpath.contains(runtime.workspace().resourcesDirectory()),
            "nor the resource delta: the packaged jar already carries the patched resources");
        assertFalse(classpath.contains(runtime.workspace().widenedServerJar()),
            "nor the widened jar: two jars holding the same class names is exactly the"
                + " arrangement the guard exists to refuse");

        assertTrue(classpath.size() > 1, "the fixture libraries must be on it");
        for (var i = 1; i < classpath.size(); i++) {
            assertTrue(classpath.get(i).toString().contains("libraries"),
                "everything behind the artifact is one of Mojang's libraries: " + classpath);
        }
    }

    @Test
    void applicationCodeIsLastSoItCannotDisplaceAMinecraftClass(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var application = tmp.resolve("veltismc.jar");
        Files.writeString(application, "not really a jar", StandardCharsets.UTF_8);

        var classpath = runtime.classpath(application);
        assertEquals(application, classpath.get(classpath.size() - 1),
            "VeltisMC's own code must be the last resort, or a module could shadow a"
                + " Minecraft class by accident");
    }

    @Test
    void everyClasspathEntryMustExistBeforeTheServerStarts(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var missing = runtime.workspace().vanillaDirectory().resolve("not-downloaded.jar");

        var failure = assertThrows(PatchEngineException.class,
            () -> runtime.classpathUrls(missing),
            "a missing entry must be reported here, not as a NoClassDefFoundError from"
                + " whatever Minecraft class happened to touch it first");
        assertTrue(failure.getMessage().contains("not-downloaded.jar"),
            "the failure must name the entry: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("veltis-server.jar"),
            "and say how to recover: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // The guard, agreeing
    // ------------------------------------------------------------------

    @Test
    void theGuardPassesAndNamesWhereTheClassesCameFrom(@TempDir Path tmp) throws Exception {
        var runtime = fixture(tmp);
        try (var loader = runtime.newClassLoader(null)) {
            var report = runtime.verifyPatchedClasses(loader);
            assertTrue(report.contains("Verified 1 patched class"), report);
            assertTrue(report.contains(runtime.workspace().veltisServerJar().toString()),
                "the report must name the jar the classes were loaded from, so a"
                    + " successful start is evidence about a specific artifact: " + report);
        }
    }

    // ------------------------------------------------------------------
    // The guard, disagreeing — the cases that matter
    // ------------------------------------------------------------------

    @Test
    void theGuardFailsWhenTheLoaderPrefersVanilla(@TempDir Path tmp) throws Exception {
        var runtime = fixture(tmp);
        try (var canary = runtime.vanillaFirstClassLoader(null)) {
            var failure = assertThrows(PatchEngineException.class,
                () -> runtime.verifyPatchedClasses(canary),
                "a loader that resolves vanilla first must fail the guard; a guard that"
                    + " cannot fail is not a guard");

            var message = failure.getMessage();
            assertTrue(message.contains("[VeltisGuard]"), message);
            assertTrue(message.contains("MinecraftServer.class"),
                "the failure must name the class: " + message);
            assertTrue(message.contains("was not started"),
                "and must say the server did not start, so nobody reads a failed guard"
                    + " as a warning: " + message);
        }
    }

    @Test
    void theGuardFailsWhenAPatchedClassIsIdenticalToVanilla(@TempDir Path tmp)
        throws Exception {
        // The case classpath order cannot catch: a patch that applied nothing
        // leaves the compiled class byte-for-byte identical to the one it
        // replaced, and a server runs it looking perfectly healthy. The record
        // written when the jar was packaged carries both hashes, so this is
        // checkable at launch with no vanilla jar on the classpath at all.
        //
        // Assembled by hand rather than through the generator, because a
        // generator that finds nothing to change leaves the class out of the
        // patch set entirely — and the build refuses that through
        // verifyCompiledOutput before it ever reaches here. What is under test
        // is the second line of that defence, the jar's own record, which has to
        // hold even against a patch set nobody generated.
        var runtime = fixture(tmp, "vanilla", "vanilla", false);
        var workspace = runtime.workspace();

        var identical = readEntry(workspace.vanillaClassesJar(), SERVER_CLASS);
        var patchFile = workspace.bytecodePatchFile();
        BytecodePatch.write(patchFile, TestWorkspace.VERSION_ID,
            MojangMetadata.sha1(workspace.vanillaServerJar()),
            MojangMetadata.sha1(workspace.vanillaClassesJar()),
            Runtime.version().feature(),
            List.of(new BytecodePatch.ProducedEntry(SERVER_CLASS, identical,
                BytecodePatch.sha1Hex(identical))));
        var patch = BytecodePatch.read(patchFile);
        runtime.applyBytecodePatch(patch,
            runtime.identityFor(patch.metadata(), FIXTURE_REVISION));

        var guard = VeltisRuntime.readGuard(workspace.veltisServerJar());
        assertEquals(1, guard.size(), "one guarded class in the fixture");
        assertEquals(guard.get(SERVER_CLASS).compiledSha1(),
            guard.get(SERVER_CLASS).baselineSha1(),
            "the fixture must really produce a patched class identical to vanilla,"
                + " or the guard is not being tested at all");

        try (var loader = runtime.newClassLoader(null)) {
            var failure = assertThrows(PatchEngineException.class,
                () -> runtime.verifyPatchedClasses(loader),
                "a class identical to vanilla means a patch silently did nothing");
            assertTrue(failure.getMessage().contains("byte-for-byte identical"),
                failure.getMessage());
        }
    }

    @Test
    void aPatchTargetThatChangesNothingIsRefusedBeforeAnyPatchSetIsCut(@TempDir Path tmp)
        throws Exception {
        // The other line of the same defence, and the only one that can reach
        // this case: a class identical to vanilla is never carried by the patch
        // set, so nothing recorded in the jar could report it. This runs before
        // the patch set exists, which is also why it can stop the build rather
        // than the launch.
        var runtime = fixture(tmp, "vanilla", "vanilla", false);

        var failure = assertThrows(PatchEngineException.class,
            () -> runtime.verifyCompiledOutput(FIXTURE_REVISION),
            "a patch target whose compiled class is identical to vanilla means the patch"
                + " matched nothing, and reporting success would be the failure this"
                + " pipeline exists to make impossible");
        assertTrue(failure.getMessage().contains("byte-for-byte identical"),
            failure.getMessage());
        assertTrue(failure.getMessage().contains(SERVER),
            "the refusal must name the file, or the developer has to guess which of"
                + " their patches did nothing: " + failure.getMessage());
        assertFalse(Files.exists(runtime.workspace().veltisServerJar()),
            "and nothing may have been packaged yet: the refusal happens before a jar"
                + " exists that anyone could mistake for a finished build");
    }

    @Test
    void theGuardFailsWhenThePackagedBytesWereReplacedAfterTheBuild(@TempDir Path tmp)
        throws Exception {
        // What the jar says it contains and what it does contain can drift — an
        // interrupted copy, a hand-edited jar, a tampered installation. The record
        // was written from the bytes that were verified, so a later change shows up
        // as a mismatch even though the class loads and the code source is right.
        var runtime = fixture(tmp);
        var classFile = runtime.workspace().classesDirectory().resolve(SERVER_CLASS);
        assertArrayEquals(Files.readAllBytes(classFile),
            readEntry(runtime.workspace().veltisServerJar(), SERVER_CLASS),
            "the fixture must start with a jar that matches its compiled class");

        replaceEntry(runtime.workspace().veltisServerJar(), SERVER_CLASS,
            source("MinecraftServer", "not what was compiled").getBytes(StandardCharsets.UTF_8));

        try (var loader = runtime.newClassLoader(null)) {
            var failure = assertThrows(PatchEngineException.class,
                () -> runtime.verifyPatchedClasses(loader),
                "a jar whose guarded class no longer hashes to the recorded value must"
                    + " not start");
            assertTrue(failure.getMessage().contains("MinecraftServer.class"),
                failure.getMessage());
        }
    }

    /**
     * §34's "invalid original class hash": the index records what the vanilla
     * baseline held when the patch was cut, so a baseline that hashes to
     * something else is not the jar this patch was written against — a Mojang
     * re-publish, an interrupted re-extract, a file somebody replaced.
     *
     * <p>The second half is the half that matters. Refusing after replacing the
     * artifact would be a slower version of the corruption being stopped, so the
     * jar the installation already has must be byte for byte as it was and no
     * staging file may survive.
     */
    @Test
    void aBaselineThatDoesNotMatchTheIndexIsRefusedAndTheJarIsUntouched(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var jar = runtime.workspace().veltisServerJar();
        var before = Files.readAllBytes(jar);

        var good = BytecodePatch.read(runtime.workspace().bytecodePatchFile());
        var wrong = "0".repeat(40);
        var entries = new ArrayList<BytecodePatch.ProducedEntry>();
        for (var entry : good.entries()) {
            entries.add(new BytecodePatch.ProducedEntry(entry.name(), good.payload(entry.name()),
                SERVER_CLASS.equals(entry.name()) ? wrong : entry.originalSha1()));
        }
        // Rewritten rather than edited in place, so the container stays
        // internally consistent — fingerprint, entry count and index all agree.
        // It is a well-formed patch set; it is simply cut from another build.
        var elsewhere = tmp.resolve("cut-against-another-build.zip");
        BytecodePatch.write(elsewhere, good.metadata().minecraftVersion(),
            good.metadata().serverSha1(), good.metadata().classesSha1(),
            good.metadata().classFileRelease(), entries);
        var bad = BytecodePatch.read(elsewhere);

        var failure = assertThrows(PatchEngineException.class,
            () -> runtime.applyBytecodePatch(bad,
                runtime.identityFor(bad.metadata(), FIXTURE_REVISION)),
            "a patch set and a baseline that disagree must not be applied at all");
        assertTrue(failure.getMessage().contains(SERVER_CLASS), failure.getMessage());
        assertTrue(failure.getMessage().contains("Expected SHA-1: " + wrong),
            "both hashes have to be in the message, or the operator cannot tell what"
                + " the jar actually is: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("Actual SHA-1:"), failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(jar),
            "the artifact the installation already has must be left exactly as it was");
        assertFalse(Files.exists(jar.resolveSibling(jar.getFileName() + ".writing")),
            "and no half-written jar may be left behind for the next run to find");
    }

    /**
     * §34's "corrupted patch": the index promises a payload hash and the bytes
     * in the container are not those bytes.
     *
     * <p>What is checked at read time is the index against its own fingerprint —
     * a statement about the patch set having been edited or truncated. The bytes
     * the index describes are checked here, by the one piece of code that also
     * has the artifact in front of it, and before that artifact is touched.
     */
    @Test
    void aPayloadThatIsNotWhatTheIndexPromisedIsRefusedAndTheJarIsUntouched(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var jar = runtime.workspace().veltisServerJar();
        var before = Files.readAllBytes(jar);

        // Only the payload moves; index.txt and patch.properties stay exactly as
        // written, so the container still reads and the fingerprint still
        // matches. Nothing short of re-hashing the payload can see this.
        var patchFile = runtime.workspace().bytecodePatchFile();
        replaceEntry(patchFile, BytecodePatch.PAYLOAD_PREFIX + SERVER_CLASS,
            source("MinecraftServer", "not what the index promised")
                .getBytes(StandardCharsets.UTF_8));
        var corrupt = BytecodePatch.read(patchFile);

        var failure = assertThrows(PatchEngineException.class,
            () -> runtime.applyBytecodePatch(corrupt,
                runtime.identityFor(corrupt.metadata(), FIXTURE_REVISION)),
            "a payload the index does not describe must not be written into a jar");
        assertTrue(failure.getMessage().contains("is corrupt"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SERVER_CLASS), failure.getMessage());
        assertTrue(failure.getMessage().contains("Expected SHA-1:"),
            "the expected hash is what tells the operator which build was intended: "
                + failure.getMessage());
        assertTrue(failure.getMessage().contains("Actual SHA-1:"), failure.getMessage());
        assertArrayEquals(before, Files.readAllBytes(jar),
            "the artifact the installation already has must be left exactly as it was");
        assertFalse(Files.exists(jar.resolveSibling(jar.getFileName() + ".writing")),
            "and no half-written jar may be left behind for the next run to find");
    }

    /**
     * Every class file a patched source produces goes into the jar, not only the
     * one the patch set names.
     *
     * <p>The patch set names <em>files</em>. javac emits <em>classes</em>: one for
     * the file and one for each nested type, anonymous class and the like. Packing
     * only the first puts our version of the outer class beside vanilla's version
     * of the classes it constructs and inner-references. Both halves resolve, the
     * build is green, and the disagreement surfaces later as a LinkageError three
     * frames into vanilla code — or, worse, does not surface at all because the
     * inner class's only effect is to behave the way it did before the patch.
     */
    @Test
    void everyClassThePatchedSourceCompilesToGoesIntoTheJar(@TempDir Path tmp)
        throws Exception {
        var workspace = VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION);
        workspace.createDirectories();

        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(workspace.patchTargetsFile(), "code\t" + SERVER + "\n");

        var patchedSource = workspace.patchedDirectory().resolve(SERVER);
        Files.createDirectories(patchedSource.getParent());
        Files.writeString(patchedSource, nestedSource("patched"), StandardCharsets.UTF_8);

        // Vanilla's baseline really does contain the nested class, with different
        // bytes, or the test would pass for the wrong reason: it would only be
        // proving that the jar has an entry nobody else wrote.
        var vanillaOut = workspace.vanillaDirectory().resolve("vanilla-src").resolve("out");
        compileSource(workspace.vanillaDirectory().resolve("vanilla-src"),
            vanillaOut, "MinecraftServer.java", nestedSource("vanilla"));
        writeJarFromTree(workspace.widenedServerJar(), vanillaOut);
        writeJarFromTree(workspace.vanillaClassesJar(), vanillaOut);
        writeArtifact(workspace);

        new PatchedSourceCompiler(Runtime.version().feature())
            .compile(workspace, List.of(patchedSource));

        var runtime = runtimeFor(workspace);
        packageFixture(runtime);

        var jar = workspace.veltisServerJar();
        var nested = "net/minecraft/server/MinecraftServer$Inner.class";
        var compiled = workspace.classesDirectory();

        assertArrayEquals(Files.readAllBytes(compiled.resolve(nested)),
            readEntry(jar, nested),
            "the nested class in the jar must be the one javac just compiled, not"
                + " vanilla's copy of a class the patch set never mentioned");
        assertNotEquals(
            Files.readAllBytes(vanillaOut.resolve("net/minecraft/server/MinecraftServer$Inner.class")),
            readEntry(jar, nested),
            "the fixture must really differ, or the previous assertion proves nothing");

        var guard = VeltisRuntime.readGuard(jar);
        assertTrue(guard.containsKey(nested),
            "the guard must cover it too: proving the class is in the jar says nothing"
                + " about which jar the loader will actually answer from");
        assertEquals(BytecodePatch.sha1Hex(Files.readAllBytes(
                vanillaOut.resolve("net/minecraft/server/MinecraftServer$Inner.class"))),
            guard.get(nested).baselineSha1(),
            "and it carries the real baseline hash: vanilla's copy genuinely is the class"
                + " this one replaces, so it is diffed against like any other entry"
                + " rather than waved through because it happens to be a nested class");
        assertNotEquals("-", guard.get(SERVER_CLASS).baselineSha1(),
            "while the file the patch set actually names keeps its real baseline, so"
                + " the check for a patch that silently matched nothing still has"
                + " something to compare against");

        try (var loader = runtime.newClassLoader(null)) {
            runtime.verifyPatchedClasses(loader);
        }
    }

    private static String nestedSource(String marker) {
        return "package net.minecraft.server;\n"
            + "public class MinecraftServer {\n"
            + "    public static String marker() { return \"" + marker + "\"; }\n"
            + "    static class Inner {\n"
            + "        String marker() { return \"" + marker + "-inner\"; }\n"
            + "    }\n"
            + "}\n";
    }

    @Test
    void theGuardRefusesToGuessWhenNothingWasRecordedAsPatched(@TempDir Path tmp) {
        var workspace = VeltisWorkspace.of(tmp, TestWorkspace.VERSION);
        workspace.createDirectories();

        var failure = assertThrows(PatchEngineException.class,
            () -> runtimeFor(workspace).verifyPatchedClasses(
                new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())),
            "with no recorded patch targets there is nothing to verify, and claiming"
                + " success would be the worst possible answer");
        assertTrue(failure.getMessage().contains("[VeltisGuard]"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // The artifact's own record
    // ------------------------------------------------------------------

    @Test
    void theJarCarriesTheIdentityThatAReusedJarIsCheckedAgainst(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var jar = runtime.workspace().veltisServerJar();
        var recorded = RuntimeIdentity.readFromJar(jar);

        assertTrue(recorded.isPresent(), "a packaged runtime must record what produced it");
        assertEquals(MojangMetadata.sha1(runtime.workspace().vanillaServerJar()),
            recorded.get().serverSha1(),
            "the record has to name the exact artifact, not merely the version id: Mojang"
                + " re-publishes artifacts for the same id, and a patch authored against"
                + " the old bytes must not reuse the new jar");
        assertEquals(FIXTURE_REVISION, recorded.get().sourceRevision(),
            "and the exact source patch set, or an edited patch would reuse yesterday's jar");
        assertEquals(fixturePatch(runtime).metadata().fingerprint(),
            recorded.get().patchFingerprint(),
            "and the bytecode patch set the jar was actually cut from — the field a cache"
                + " is keyed on, so a rebuilt patch set is a rebuilt artifact");
        assertEquals(BytecodePatch.FORMAT, recorded.get().patchFormat(),
            "and the patch format, so a change to what the container means cannot be read"
                + " as a patch set this jar was built from");
        assertEquals(Runtime.version().feature(), recorded.get().classFileRelease(),
            "and the bytecode release, so a JDK upgrade rebuilds rather than loading"
                + " classes the current one cannot verify");
        assertEquals(AccessWidener.FORMAT, recorded.get().widenFormat(),
            "and the access-widener format, so a change to what widened means cannot"
                + " silently reuse a jar built against the old rules");

        // What a warm start does with it: rebuild the expectation the same way and
        // compare. The record is not documentation, it is the input to that check.
        var workspace = runtime.workspace();
        recorded.get().recordAsCurrent(workspace);
        assertTrue(RuntimeIdentity.isCurrent(workspace, recorded.get()),
            "a freshly packaged artifact must be recorded as a finished build");

        Files.writeString(workspace.vanillaServerJar(), "re-published bytes\n",
            StandardCharsets.UTF_8);
        var differentArtifact = new RuntimeIdentity(
            TestWorkspace.VERSION_ID,
            MojangMetadata.sha1(workspace.vanillaServerJar()),
            recorded.get().sourceRevision(),
            recorded.get().patchFingerprint(),
            recorded.get().classFileRelease(),
            recorded.get().widenFormat(),
            recorded.get().patchFormat());
        assertNotEquals(recorded.get().serverSha1(), differentArtifact.serverSha1(),
            "the fixture must really change the artifact's hash");
        assertFalse(differentArtifact.renderArtifact("1.0")
                .equals(recorded.get().renderArtifact("1.0")),
            "so no two artifacts produce the same record, and a reused jar can only"
                + " have been validated against the artifact it was built from");
    }

    @Test
    void aRecordInAnUnknownFormatIsNotAPossibleReuse(@TempDir Path tmp) throws Exception {
        // The artifact format says which layout a jar has. A jar from a build that
        // wrote a different one is not a stale runtime, it is an unreadable one —
        // and reading it as if it were current would be the failure mode the format
        // exists to prevent.
        var jar = tmp.resolve("veltis-server.jar");
        writeJarWith(jar, RuntimeIdentity.JAR_IDENTITY_ENTRY,
            ("minecraft=26.3\nserverSha1=" + "a".repeat(40)
                + "\nsourceRevision=fixture-revision\npatchFingerprint=" + "f".repeat(64)
                + "\nclassFileRelease=26\nwidenFormat=3\npatchFormat=1\n"
                + "artifactFormat=0\nveltisVersion=0.0\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(RuntimeIdentity.readFromJar(jar).isEmpty(),
            "a jar written in a format this build does not understand must be rebuilt,"
                + " never interpreted");

        writeJarWith(jar, RuntimeIdentity.JAR_IDENTITY_ENTRY,
            ("minecraft=26.3\nserverSha1=" + "a".repeat(40)
                + "\nsourceRevision=fixture-revision\npatchFingerprint=" + "f".repeat(64)
                + "\nclassFileRelease=26\nwidenFormat=3\npatchFormat=1\n"
                + "artifactFormat=" + RuntimeIdentity.ARTIFACT_FORMAT
                + "\nveltisVersion=0.0\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(RuntimeIdentity.readFromJar(jar).isPresent(),
            "and the format it does understand must be read");
    }

    @Test
    void validationAgreesWithTheJarItWouldReuse(@TempDir Path tmp) throws Exception {
        var runtime = fixture(tmp);
        var workspace = runtime.workspace();
        var patch = fixturePatch(runtime);

        // A checkout keeps classes/ and the marker too; a warm start means the
        // whole build is present, not merely the jar.
        identityFor(runtime).recordAsCurrent(workspace);

        assertTrue(runtime.validateArtifact(patch, FIXTURE_REVISION).isPresent(),
            "a packaged, recorded and complete runtime must be reusable");

        assertFalse(runtime.validateArtifact(patch, "c".repeat(64)).isPresent(),
            "a different source patch set must not reuse the jar, whatever else matches");

        var otherFile = tmp.resolve("other.zip");
        BytecodePatch.write(otherFile, TestWorkspace.VERSION_ID,
            patch.metadata().serverSha1(), patch.metadata().classesSha1(),
            patch.metadata().classFileRelease(),
            List.of(new BytecodePatch.ProducedEntry("nothing.txt", new byte[] {9},
                BytecodePatch.ABSENT)));
        var otherPatch = BytecodePatch.read(otherFile);
        assertFalse(runtime.validateArtifact(otherPatch, FIXTURE_REVISION).isPresent(),
            "and neither must a patch set cut from different payloads: the fingerprint is"
                + " the cache key, so two patch sets are either the same build or not"
                + " interchangeable");

        Files.writeString(workspace.vanillaServerJar(), "something else entirely\n",
            StandardCharsets.UTF_8);
        assertFalse(runtime.validateArtifact(patch, FIXTURE_REVISION).isPresent(),
            "a vanilla artifact that no longer hashes to the recorded one must not be"
                + " reused either: Mojang can re-publish under the same version id");

        writeArtifact(workspace);
        identityFor(runtime).recordAsCurrent(workspace);
        Files.delete(workspace.veltisServerJar());
        assertFalse(runtime.validateArtifact(patch, FIXTURE_REVISION).isPresent(),
            "no jar means nothing to reuse, even with classes and marker on disk");
    }

    // ------------------------------------------------------------------
    // The server-install layout
    // ------------------------------------------------------------------

    @Test
    void aServerInstallKeepsItsArtifactsUnderTheServerAndItsWorkTreeElsewhere(@TempDir Path tmp) {
        var home = tmp.resolve("server");
        var workspace = VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION);

        assertTrue(workspace.isServerInstall(), "the launcher path is a server installation");
        assertEquals(home.resolve("Vanilla/26.3/vanilla-server.jar"),
            workspace.vanillaServerJar(),
            "the verified Mojang artifact is one of the two things a launch needs that"
                + " is not inside the runtime jar");
        assertEquals(home.resolve("libraries"), workspace.librariesDirectory(),
            "one library root at the server root, not inside Vanilla/<version>: a"
                + " version upgrade must re-verify and reuse the jars the two versions"
                + " share rather than download them all again");
        assertFalse(workspace.librariesDirectory()
                .startsWith(workspace.vanillaInstallDirectory()),
            "and specifically not nested under Vanilla/, which is per-version by"
                + " construction: " + workspace.librariesDirectory());
        assertEquals(home.resolve("Veltis/26.3/veltis-server.jar"),
            workspace.veltisServerJar());

        assertFalse(workspace.root().startsWith(home),
            "the work tree must not be under the server directory: a run that fails"
                + " half-way must still leave a clean installation, and the only way to"
                + " guarantee that is to not work there — " + workspace.root());
        assertTrue(workspace.root().startsWith(
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),
            "and it must be a predictable location, not a random one: " + workspace.root());

        // Deterministic: two installations in the same directory get the same work
        // root, so a rerun resumes rather than colliding, and two different servers
        // on one machine can never share one.
        assertEquals(workspace.root(),
            VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION).root());
        assertNotEquals(workspace.root(),
            VeltisWorkspace.serverInstall(tmp.resolve("other"), TestWorkspace.VERSION).root());
    }

    @Test
    void discardingTheWorkTreeLeavesTheInstallationUntouched(@TempDir Path tmp)
        throws Exception {
        var home = tmp.resolve("server");
        var workspace = VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION)
            .createDirectories();
        Files.writeString(workspace.sourceDirectory().resolve("Some.java"), "class Some {}");

        assertTrue(workspace.discardWorkTree() > 0,
            "a successful build removes its work tree, and the count is what the log reports");
        assertFalse(Files.exists(workspace.root()));
        try (var remaining = Files.list(home)) {
            var names = remaining.map(p -> p.getFileName().toString())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
            assertEquals(List.of("libraries", "Vanilla"), names,
                "the installation keeps exactly the two persistent roots the build"
                    + " wrote — the vanilla artifact and the shared library root. No work"
                    + " tree, no marker, no hidden state: the server directory is the same"
                    + " before and after, apart from what a launch is supposed to produce");
        }

        // A checkout never discards: keeping the tree is the whole point of it.
        var dev = VeltisWorkspace.of(tmp.resolve("project"), TestWorkspace.VERSION)
            .createDirectories();
        Files.writeString(dev.sourceDirectory().resolve("Some.java"), "class Some {}");
        assertEquals(0, dev.discardWorkTree(), "a contributor's workspace is never removed");
        assertTrue(Files.exists(dev.sourceDirectory()));
    }

    @Test
    void mirroringForAServerBuildTouchesOnlyTheFilesThePatchSetAddresses(@TempDir Path tmp)
        throws Exception {
        var source = tmp.resolve("source");
        var patched = tmp.resolve("patched");
        Files.createDirectories(source.resolve("net/minecraft/server"));
        Files.writeString(source.resolve("net/minecraft/server/MinecraftServer.java"),
            "class MinecraftServer {}\n");
        Files.createDirectories(source.resolve("net/minecraft/commands"));
        Files.writeString(source.resolve("net/minecraft/commands/Commands.java"),
            "class Commands {}\n");

        var mirrored = VeltisPatcher.mirrorPristineTargets(source, patched,
            List.of("net/minecraft/server/MinecraftServer.java"));

        assertEquals(1, mirrored, "only the addressed file is mirrored");
        assertTrue(Files.exists(patched.resolve("net/minecraft/server/MinecraftServer.java")));
        assertFalse(Files.exists(patched.resolve("net/minecraft/commands/Commands.java")),
            "a server build reads nothing else, which is where the tens of seconds a"
                + " 'patch step' used to cost actually went: it was never the diffing");
    }

    private static byte[] readEntry(Path jar, String entry) throws IOException {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var found = zip.getEntry(entry);
            assertNotNull(found, jar + " must contain " + entry);
            try (var in = zip.getInputStream(found)) {
                return in.readAllBytes();
            }
        }
    }

    /** Replaces one entry, leaving everything else in the jar as it was. */
    private static void replaceEntry(Path jar, String entry, byte[] content)
        throws IOException {
        var staging = jar.resolveSibling(jar.getFileName() + ".rebuild");
        try (var in = new java.util.zip.ZipFile(jar.toFile());
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(staging))) {
            for (var entries = in.entries(); entries.hasMoreElements(); ) {
                var existing = entries.nextElement();
                if (existing.isDirectory() || existing.getName().equals(entry)) {
                    continue;
                }
                var copy = new java.util.zip.ZipEntry(existing.getName());
                copy.setTime(0L);
                out.putNextEntry(copy);
                try (var stream = in.getInputStream(existing)) {
                    out.write(stream.readAllBytes());
                }
                out.closeEntry();
            }
            var replaced = new java.util.zip.ZipEntry(entry);
            replaced.setTime(0L);
            out.putNextEntry(replaced);
            out.write(content);
            out.closeEntry();
        }
        Files.move(staging, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------
    // The patch phase, run twice
    // ------------------------------------------------------------------

    @Test
    void rePatchingAnAlreadyPatchedTreeProducesTheSameResult(@TempDir Path tmp)
        throws Exception {
        // The failure this pins down is loud but misleading. A tree that already
        // carries a previous run's patches still contains the hunk's context --
        // with the earlier edit sitting between two of its lines -- so the patcher
        // reports "context mismatch" on a file that is perfectly correct, and the
        // message points at the patch rather than at the stale tree.
        //
        // It is not hypothetical: a start that failed after patching, retried, is
        // exactly this state. So the phase has to rebuild `patched/` from the
        // pristine decompile every time, and be a fixed point of itself.
        var project = tmp.resolve("project");
        var workspace = VeltisWorkspace.of(project, TestWorkspace.VERSION);
        workspace.createDirectories();
        Files.createDirectories(workspace.buildDirectory());

        var sourceFile = workspace.sourceDirectory().resolve(SERVER);
        Files.createDirectories(sourceFile.getParent());
        var body = "package net.minecraft.server;\n"
            + "public class MinecraftServer {\n"
            + "    public void performCommand(String s) {\n"
            + "        int x = 1;\n"
            + "    }\n"
            + "}\n";
        Files.writeString(sourceFile, body, StandardCharsets.UTF_8);

        var patchFile = workspace.patchesDirectory()
            .resolve("code").resolve("001-Server.patch");
        Files.createDirectories(patchFile.getParent());
        Files.writeString(patchFile, """
            --- a/net/minecraft/server/MinecraftServer.java
            +++ b/net/minecraft/server/MinecraftServer.java
            @@ -1,5 +1,6 @@
             public class MinecraftServer {
                 public void performCommand(String s) {
                     int x = 1;
            +        int y = 2;
                 }
             }
            """, StandardCharsets.UTF_8);

        var runtime = VeltisRuntime.fromDirectory(project, TestWorkspace.VERSION,
            workspace.patchesDirectory(), 1, Runtime.version().feature());
        var patches = PatchSet.fromDirectory(workspace.patchesDirectory(),
            TestWorkspace.VERSION_ID, new PatchStats());

        runtime.applyPatches(patches);
        var first = Files.readString(workspace.patchedDirectory().resolve(SERVER),
            StandardCharsets.UTF_8);
        assertTrue(first.contains("int y = 2;"), "the patch must be applied: " + first);

        // Second run over the tree the first run left behind.
        runtime.applyPatches(patches);
        var second = Files.readString(workspace.patchedDirectory().resolve(SERVER),
            StandardCharsets.UTF_8);
        assertEquals(first, second,
            "the patch phase must be a fixed point: applying it to an already-patched"
                + " tree has to produce the same tree, not a double application and not"
                + " a context-mismatch failure");

        // And the pristine baseline is untouched, which is what makes the third run
        // possible and what keeps rebuildVeltisPatches honest.
        assertEquals(body, Files.readString(sourceFile, StandardCharsets.UTF_8),
            "mirroring must not write through to the pristine decompile: if it did,"
                + " rebuildVeltisPatches would diff the file against itself and report"
                + " no changes, losing hand-written patches silently");
    }

    // ------------------------------------------------------------------
    // Patch targets
    // ------------------------------------------------------------------

    @Test
    void patchTargetsAreReadFromTheManifestThePatcherWrote(@TempDir Path tmp)
        throws Exception {
        var project = tmp.resolve("project");
        var workspace = VeltisWorkspace.of(project, TestWorkspace.VERSION);
        workspace.createDirectories();
        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(workspace.patchTargetsFile(), """
            code	net/minecraft/server/MinecraftServer.java
            code	net/minecraft/server/players/PlayerList.java
            data	data/veltis/config.json
            modules	org/veltismc/module.txt
            code	net/minecraft/commands/Commands.java
            code	net/minecraft/server/dedicated/DedicatedServer.java
            code	net/minecraft/server/dedicated/DedicatedServer.java
            """, StandardCharsets.UTF_8);

        var runtime = runtimeFor(workspace);
        assertEquals(List.of(
                "net/minecraft/server/MinecraftServer.java",
                "net/minecraft/server/players/PlayerList.java",
                "net/minecraft/commands/Commands.java",
                "net/minecraft/server/dedicated/DedicatedServer.java"),
            runtime.patchTargets(PatchCategory.CODE, ".java"),
            "only code targets, in manifest order, and a file two patches address is"
                + " one file: the manifest records patch-to-file relationships while"
                + " this answers which files are patched, and duplicating an entry"
                + " writes the same class twice into a jar that refuses duplicates");
        assertEquals(List.of("data/veltis/config.json"),
            runtime.patchTargets(PatchCategory.DATA, null));
        assertEquals(List.of("org/veltismc/module.txt"),
            runtime.patchTargets(PatchCategory.MODULES, null));
    }

    @Test
    void aMissingManifestIsNotAnErrorUntilSomethingAsksForIt(@TempDir Path tmp) {
        var workspace = VeltisWorkspace.of(tmp, TestWorkspace.VERSION);
        workspace.createDirectories();

        assertEquals(List.of(), runtimeFor(workspace).patchTargets(PatchCategory.CODE, ".java"),
            "a workspace that has not been patched yet has no targets, and reading them"
                + " must not fail — the launcher reads them after patching");
        assertFalse(Files.exists(workspace.patchTargetsFile()),
            "and reading must not create the manifest as a side effect");
    }

    @Test
    void aServerInstallLaunchesFromTheJarWithoutTheBuildTree(@TempDir Path tmp)
        throws Exception {
        // The launch path a server operator takes: the work tree is gone, there is
        // no patch-target manifest, no classes/ and no marker — and the guard still
        // has everything it needs, because it reads the jar.
        var home = tmp.resolve("server");
        var workspace = VeltisWorkspace.serverInstall(home, TestWorkspace.VERSION)
            .createDirectories();

        Files.createDirectories(workspace.buildDirectory());
        Files.writeString(workspace.patchTargetsFile(), "code\t" + SERVER + "\n");
        var patchedSource = workspace.patchedDirectory().resolve(SERVER);
        Files.createDirectories(patchedSource.getParent());
        Files.writeString(patchedSource, source("MinecraftServer", "patched"),
            StandardCharsets.UTF_8);
        var vanilla = compileInto(workspace, "vanilla-src", "MinecraftServer", "vanilla");
        writeJarWith(workspace.widenedServerJar(), SERVER_CLASS, vanilla);
        writeJarWith(workspace.vanillaClassesJar(), SERVER_CLASS, vanilla);
        writeArtifact(workspace);
        new PatchedSourceCompiler(Runtime.version().feature())
            .compile(workspace, List.of(patchedSource));

        var runtime = VeltisRuntime.fromPackagedPatches(home, TestWorkspace.VERSION,
            VeltisRuntimeTest.class.getClassLoader(), 1, Runtime.version().feature());
        packageFixture(runtime);
        runtime.workspace().discardWorkTree();
        assertFalse(Files.exists(workspace.root()), "the work tree is removed after packaging");

        try (var loader = runtime.newClassLoader(null)) {
            var report = runtime.verifyPatchedClasses(loader);
            assertTrue(report.contains(home.resolve("Veltis/26.3/veltis-server.jar").toString()),
                "the guard must prove the classes came from the artifact: " + report);
        }
        assertFalse(Files.exists(home.resolve(".vlt")),
            "a server directory never gets a hidden state directory back");
    }

    /**
     * The guard must fail when a build tree, rather than the artifact, is what
     * the loader answers from — even though the bytes are the compiled bytes.
     *
     * <p>This is the case a classpath-ordering rule alone cannot catch: the loose
     * directory holds <em>exactly</em> the classes the guard wants, so every hash
     * matches, and the only difference is that they came from a place the jar
     * says it replaced. A check that stopped at hashes would call that fine and
     * the server would run from a tree nobody validates, rebuilt at any time by
     * the next {@code ./gradlew build} the operator happens to run.
     */
    @Test
    void aLooseClassDirectoryIsNotAnAcceptableSubstituteForTheArtifact(@TempDir Path tmp)
        throws Exception {
        var runtime = fixture(tmp);
        var workspace = runtime.workspace();

        var urls = new java.util.ArrayList<java.net.URL>();
        urls.add(workspace.classesDirectory().toUri().toURL());
        for (var url : runtime.classpathUrls(null)) {
            urls.add(url);
        }

        try (var loader = new URLClassLoader(urls.toArray(java.net.URL[]::new),
            ClassLoader.getPlatformClassLoader())) {
            var failure = assertThrows(PatchEngineException.class,
                () -> runtime.verifyPatchedClasses(loader),
                "a build tree ahead of the artifact must fail even though its bytes are"
                    + " the compiled bytes: the whole point is that the server runs from"
                    + " the jar, and nothing else");
            assertTrue(failure.getMessage().contains("code source"), failure.getMessage());
            assertFalse(failure.getMessage().contains("to bytes whose SHA-1"),
                "the bytes must have matched first: it is the origin that is wrong, and"
                    + " a test where the hashes also differed would pass for the wrong"
                    + " reason");
        }
    }

    // ------------------------------------------------------------------
    // The prebuilt-runtime contract
    // ------------------------------------------------------------------

    @Test
    void aRecordedIdentityIsWhatALaunchDecidesToSkipWorkOn(@TempDir Path tmp)
        throws Exception {
        var project = tmp.resolve("project");
        var workspace = VeltisWorkspace.of(project, TestWorkspace.VERSION);
        workspace.createDirectories();
        Files.createDirectories(workspace.classesDirectory()
            .resolve("net/minecraft/server"));
        Files.write(workspace.classesDirectory()
            .resolve("net/minecraft/server/MinecraftServer.class"), new byte[] {1, 2, 3});

        var patch = workspace.patchesDirectory().resolve("code").resolve("001-A.patch");
        Files.createDirectories(patch.getParent());
        Files.writeString(patch, TestWorkspace.createPatch(SERVER), StandardCharsets.UTF_8);

        var runtime = VeltisRuntime.fromDirectory(project, TestWorkspace.VERSION,
            workspace.patchesDirectory(), 4, Runtime.version().feature());
        var identity = new RuntimeIdentity(
            TestWorkspace.VERSION_ID,
            "a".repeat(40),
            runtime.sourcePatchRevision(),
            "f".repeat(64),
            Runtime.version().feature(),
            AccessWidener.FORMAT,
            BytecodePatch.FORMAT);

        assertFalse(RuntimeIdentity.isCurrent(workspace, identity),
            "a workspace with classes but no recorded identity is not a finished build");

        identity.recordAsCurrent(workspace);
        assertTrue(RuntimeIdentity.isCurrent(workspace, identity),
            "recording the identity is the last thing a build does, and it is what a"
                + " warm start checks before deciding to do no pipeline work at all");

        // The worker count is deliberately not in the identity: 4 here, and the
        // runtime must stay valid regardless.
        assertTrue(RuntimeIdentity.isCurrent(workspace, identity),
            "the identity a build records must not depend on how many workers built it");
    }
}
