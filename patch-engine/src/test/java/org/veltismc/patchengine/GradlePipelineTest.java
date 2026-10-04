package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build scripts are part of the pipeline, so they are checked like code.
 *
 * <p>These assertions exist because the failures they catch are silent: a stale
 * path in a Gradle file does not error at configuration time, it quietly compiles
 * the wrong files or produces an IDE that cannot resolve anything. Reading the
 * scripts is the cheapest way to catch that without a full Gradle run, and it is
 * the same thing a reviewer would check by eye.
 */
class GradlePipelineTest {

    /**
     * The pipeline's entry point, in the module that owns the pipeline.
     *
     * <p>It lives in {@code :patch-engine} because that is where the code it
     * calls lives and every caller of it already depended on that module; it used
     * to sit in a {@code :build-tools} module that existed for this one class.
     */
    private static final String PIPELINE_RUNNER =
        "patch-engine/src/main/java/org/veltismc/patchengine/PipelineRunner.java";

    private static final Path REPO = repositoryRoot();

    private static Path repositoryRoot() {
        // Tests run with the module directory as the working directory.
        var candidate = Path.of("").toAbsolutePath().normalize();
        for (var i = 0; i < 4 && candidate != null; i++) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the VeltisMC repository root");
    }

    private static String read(String relativePath) {
        try {
            return Files.readString(REPO.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + relativePath, e);
        }
    }

    /**
     * The entry names inside the packaged distributable, or a failure explaining
     * that it was not built.
     *
     * <p>Reading the real jar is the only way to check packaging. The scripts say
     * what was asked for; the jar says what happened, and the two have differed
     * more than once — a {@code from(task)} that contributed nothing, and a
     * {@code from(directory)} that put the patch files one directory above where
     * the index said they were. Both builds were green.
     */
    private static List<String> distributableEntries() {
        var jar = REPO.resolve("build/distributions/veltismc.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                "the distributable has not been built; run ./gradlew packageVeltisMC"
                    + " (GradlePipelineTest is wired to depend on it, so this only happens"
                    + " if the jar was deleted mid-build)");
        }
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.stream().map(java.util.zip.ZipEntry::getName).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + jar, e);
        }
    }

    private static String distributableText(String entry) {
        var jar = REPO.resolve("build/distributions/veltismc.jar");
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var found = zip.getEntry(entry);
            if (found == null) {
                throw new IllegalStateException(
                    "the distributable has no " + entry + "; it has "
                        + distributableEntries().size() + " entries");
            }
            try (var in = zip.getInputStream(found)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + entry + " from " + jar, e);
        }
    }

    /** A build script with its {@code //} comments stripped, so prose is never read as code. */
    private static String withoutComments(String script) {
        var code = new StringBuilder();
        for (var line : script.split("\n", -1)) {
            var comment = line.indexOf("//");
            var kept = (comment < 0 ? line : line.substring(0, comment)).strip();
            if (!kept.isEmpty()) {
                code.append(kept).append('\n');
            }
        }
        return code.toString();
    }

    // ------------------------------------------------------------------
    // The root build declares the whole pipeline
    // ------------------------------------------------------------------

    /**
     * Every stage the pipeline exposes as a task.
     *
     * <p>Two of these are the runtime path — {@code prepareVeltisRuntime} builds
     * the runtime through the engine, {@code verifyVeltisRuntime} proves the
     * loader will hand out the patched classes. The rest are the per-stage tasks
     * an IDE and a developer use to work on one piece at a time. Both exist, and
     * both are required: a build that only had the aggregate task could not
     * narrow a failure to a stage, and a build that only had the per-stage tasks
     * would be a second implementation of the chain the launcher runs.
     *
     * <p>{@code packageVeltisServer} was here before. It packed a jar containing
     * the patched classes for the launcher to load, and the runtime bootstrap
     * removed the reason it existed: the launcher now assembles the same
     * classpath itself and verifies it. Its packaging guard moved to
     * {@code verifyVeltisRuntime}, which asks the loader rather than inspecting
     * an archive.
     */
    private static final List<String> REQUIRED_TASKS = List.of(
        "prepareMinecraft",
        "downloadMinecraft",
        "downloadLibraries",
        "decompileMinecraft",
        "applyVeltisPatches",
        "rebuildVeltisPatches",
        "cleanVeltisPatches",
        "compileMinecraftJava",
        "processMinecraftResources",
        "widenServerJarAccess",
        "prepareVeltisRuntime",
        "verifyVeltisRuntime",
        "publishBytecodePatch",
        "packageVeltisMC",
        "buildVeltisMC");

    @Test
    void everyPipelineTaskIsDeclaredInTheRootBuild() {
        var root = read("build.gradle.kts");
        var missing = new ArrayList<String>();
        for (var task : REQUIRED_TASKS) {
            if (!declaresTask(root, task)) {
                missing.add(task);
            }
        }
        assertEquals(List.of(), missing,
            "every pipeline step must be a real Gradle task with real inputs and outputs");
    }

    /**
     * Whether {@code build.gradle.kts} registers a task by this name.
     *
     * <p>Two forms are in use: a name passed as a string, to {@code pipelineStep} or
     * to {@code tasks.register}, and a {@code val} whose delegated call derives the
     * task name from the property. Requiring one of those call shapes rather than a
     * bare occurrence keeps a name that only survives in a comment from passing.
     */
    private static boolean declaresTask(String root, String task) {
        return root.contains("\"" + task + "\"")
            || root.contains("val " + task + " by tasks.");
    }

    @Test
    void thePipelineStepsRunTheEngineRatherThanWrappingMain() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("mainClass = \"org.veltismc.patchengine.PipelineRunner\""),
            "the steps must call the pipeline, not the server");
        assertFalse(root.contains("dependsOn(\"main\")"),
            "no pipeline task may depend on the application plugin's main");
        assertFalse(root.contains("JavaExec::main"),
            "a build step must never shell into the server's main()");
    }

    @Test
    void pipelineStepsDeclareInputsAndOutputsSoGradleCanSkipThem() {
        var root = read("build.gradle.kts");
        // One inputs/outputs declaration per registered step.
        assertTrue(count(root, "inputs.") >= 8,
            "each step must declare what it reads");
        assertTrue(count(root, "outputs.") >= 6,
            "each step must declare what it writes");
        assertTrue(root.contains("inputs.files(pipelineClasspath)"),
            "a fix to the pipeline code must invalidate the pipeline's outputs");
    }

    @Test
    void cleanVeltisPatchesCanNeverBeConsideredUpToDate() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("outputs.upToDateWhen { false }"),
            "a clean task that reports UP-TO-DATE would silently do nothing");
    }

    @Test
    void theRebuildDoesNotReapplyFirstAndLoseTheEditsItCaptures() {
        var root = read("build.gradle.kts");
        var rebuild = withoutComments(between(root, "val rebuildVeltisPatches",
            "val cleanVeltisPatches"));
        assertFalse(rebuild.contains("dependsOn(applyVeltisPatches)"),
            "applyVeltisPatches re-mirrors source/ over patched/ with REPLACE_EXISTING,"
                + " so depending on it discards the developer edits the rebuild exists"
                + " to capture, and the rebuild then diffs the tree it just overwrote");
        assertTrue(rebuild.contains("dependsOn(decompileMinecraft)"),
            "the rebuild diffs against the pristine decompile, so it needs that tree");
        assertTrue(rebuild.contains("outputs.upToDateWhen { false }"),
            "patched/ is hand-edited between runs, so Gradle cannot track it as an"
                + " input; the task has to read it live rather than be cached");
    }

    @Test
    void theLibraryFetchIsNeverSkipped() {
        // Observed failure: on a fresh clone Gradle reported downloadLibraries
        // UP-TO-DATE and the build carried on with an empty library tree, because
        // an earlier step recreates libraries/ as an empty directory before the
        // up-to-date check compares it with its recorded snapshot.
        var root = read("build.gradle.kts");
        var step = withoutComments(between(root, "val downloadLibraries",
            "val widenServerJarAccess"));
        assertTrue(step.contains("outputs.upToDateWhen { false }"),
            "the library tree must be re-verified on every build; the step is"
                + " idempotent and costs ~400ms warm, which is the right price for"
                + " not shipping an empty classpath");
    }

    @Test
    void theLauncherJarActuallyContainsTheVeltisModulesAndThePatchSet() {
        // Observed failure: `from(project(":server").tasks.named("jar"))` added
        // nothing, so the distributable shipped with 0 org/veltismc entries and no
        // Main, no VeltisBootstrap and no world engine. Gradle still called the
        // task UP-TO-DATE, so nothing failed loudly.
        //
        // This used to assert about packageVeltisServer, the jar that carried the
        // compiled patched classes for the launcher to load. The runtime
        // bootstrap replaced that jar with a classpath the launcher builds from
        // the workspace, so the assertion moved here: one jar, and it has to
        // carry everything the server needs except the Minecraft code it must
        // fetch from Mojang itself.
        var launcher = withoutComments(read("launcher/build.gradle.kts"));
        var uberJar = between(launcher, "tasks.register(\"uberJar\"", "duplicatesStrategy");

        for (var module : List.of(":server")) {
            assertTrue(uberJar.contains(module),
                "the launcher jar must include " + module
                    + ": it is the only artifact a server operator receives");
        }
        assertTrue(count(uberJar, "zipTree(") >= 3,
            "a module jar has to be expanded through zipTree; handing `from` the task"
                + " silently contributes nothing");

        // The patch set has to travel with the jar, or the bootstrap has nothing
        // to apply and no build-time artifact to fall back on.
        assertTrue(uberJar.contains("\":publishBytecodePatch\""),
            "the launcher jar must depend on the task that publishes the bytecode patch"
                + " set, or java -jar veltismc.jar would have nothing to apply");
        assertTrue(uberJar.contains("include(\"META-INF/veltis/**\")"),
            "the patch set and the packaged version travel under META-INF/veltis/"
                + " so a jar scan cannot mistake them for anything else");
        assertFalse(uberJar.contains("dir(\"Shulker\")"),
            "the development source patches must not ship: a server has no decompiler to"
                + " apply them with and no compiler to run the result through, so they"
                + " would be a second patch representation nothing could use");
        assertFalse(uberJar.contains("include(\"**/*.patch\")"),
            "same rule at the include level: one representation ships, and it is the"
                + " bytecode one");
        assertTrue(uberJar.contains("\":writePackagedVersion\""),
            "the jar must depend on the task that records the version it was built for,"
                + " so an unflagged start has a default that cannot drift from the build");
    }

    @Test
    void theJarShipsTheBytecodePatchSetAndNoSourcePatchBesideIt() {
        // The one packaging assertion that reads the artifact instead of the
        // script. Source patches used to ship here, listed in patches/index.txt;
        // that representation is gone, because a server has no decompiler to
        // apply a text diff with and no compiler to run the result through. The
        // only thing that can turn Mojang's jar into a VeltisMC server is the
        // bytecode patch set, so reading the jar is the only way to know which
        // one actually arrived.
        var entries = distributableEntries();
        // The file carries a comment header, and the launcher reads it the same
        // way: the version is the first line that is neither blank nor a '#'.
        var version = distributableText("META-INF/veltis/minecraft-version.txt").lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "the packaged version file records no Minecraft version at all"));
        var resource = BytecodePatch.RESOURCE_PREFIX + version + ".zip";

        assertTrue(entries.contains(resource),
            "the jar must carry the bytecode patch set for the version it records ("
                + version + "): java -jar cannot fetch one, and a jar without it can"
                + " only ever run vanilla");
        assertTrue(entries.contains("META-INF/veltis/minecraft-version.txt"),
            "the jar must record the version it was built for");

        var sourcePatches = entries.stream().filter(e -> e.startsWith("Shulker/")).toList();
        assertEquals(List.of(), sourcePatches,
            "no development source patch may ship: they are how a contributor writes a"
                + " change, and a running server has nothing to apply them with ("
                + sourcePatches.size() + " found)");
        assertFalse(entries.contains("patches/index.txt"),
            "the source-patch index went with the source patches");

        // And the thing that shipped really is a patch set, checked the way the
        // failure this replaced was checked: the index must only name payloads the
        // container actually holds, or the applier fails on a stranger's machine
        // with "no payload for X" and no way to get one.
        var container = distributableEntry(resource);
        assertTrue(container.length > 2 && container[0] == 'P' && container[1] == 'K',
            "the packaged patch set must be a zip container");
        var contents = zipContents(container, resource);
        assertTrue(contents.containsKey(BytecodePatch.METADATA_ENTRY),
            "a patch set without its metadata has no version, no artifact hash and no"
                + " fingerprint, so it cannot be validated by anything");
        var index = contents.get(BytecodePatch.INDEX_ENTRY);
        assertTrue(index != null && index.length > 0,
            "and no index means no list of what to change: " + resource);

        var checked = 0;
        for (var line : new String(index, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\t", -1);
            if (!"ENTRY".equals(fields[0])) {
                continue;
            }
            assertTrue(contents.containsKey(BytecodePatch.PAYLOAD_PREFIX + fields[1]),
                "the index names " + fields[1] + " so the container must hold"
                    + " payloads/" + fields[1] + " -- the applier looks it up under"
                    + " that one path and a missing one stops the start");
            checked++;
        }
        assertTrue(checked > 0,
            "an index with nothing to apply would mean the jar runs vanilla while"
                + " looking like a VeltisMC distribution");

        assertTrue(entries.stream().anyMatch(e -> e.startsWith("org/veltismc/")),
            "the jar must carry VeltisMC's own classes; it is the only artifact an"
                + " operator receives");
        assertTrue(entries.contains("org/veltismc/launcher/VeltisLauncher.class"),
            "and the launcher entry point, or `java -jar` has no Main-Class to find");
    }

    private static byte[] distributableEntry(String entry) {
        var jar = REPO.resolve("build/distributions/veltismc.jar");
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var found = zip.getEntry(entry);
            if (found == null) {
                throw new IllegalStateException("the distributable has no " + entry);
            }
            try (var in = zip.getInputStream(found)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + entry + " from " + jar, e);
        }
    }

    private static java.util.Map<String, byte[]> zipContents(byte[] container, String what) {
        var contents = new java.util.LinkedHashMap<String, byte[]>();
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(container))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) {
                    contents.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + what, e);
        }
        return contents;
    }

    @Test
    void theDistributableShipsNoMinecraftBytes() {
        // The same check the script-level test above makes, against the jar. The
        // filter is a claim about content; only the content can confirm it, and
        // shipping Mojang's bytes in a jar VeltisMC distributes is the one
        // packaging mistake the user cannot undo.
        var entries = distributableEntries();
        for (var owned : List.of("net/minecraft/", "com/mojang/", "org/bukkit/",
                "org/spigotmc/", "assets/minecraft/")) {
            var shipped = entries.stream().filter(e -> e.startsWith(owned)).toList();
            assertEquals(List.of(), shipped,
                "the distributable must fetch Minecraft from Mojang at runtime, so it"
                    + " must not carry " + owned + " (" + shipped.size() + " entries)");
        }
        // And the widening rules still apply to the launcher jar itself: a signed
        // dependency in an uber jar fails the same way a signed Minecraft jar
        // would, on the first class read.
        for (var entry : entries) {
            var upper = entry.toUpperCase(java.util.Locale.ROOT);
            assertFalse(upper.startsWith("META-INF/SIG-") || upper.endsWith(".SF")
                    || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                    || upper.endsWith(".EC"),
                entry + " is a signature block in the distributable; the uber jar merges"
                    + " signed dependencies whose digests no longer hold, and the JVM"
                    + " verifies every class it reads from a signed jar");
        }
    }

    @Test
    void theRuntimeIsBuiltThroughTheSameCodeTheLauncherRuns() {
        // The whole point of the bootstrap: there is one implementation of the
        // chain. A build that assembled its own version would drift from what a
        // server does, and the drift would surface as a green build producing a
        // jar that starts vanilla.
        var code = withoutComments(read("build.gradle.kts"));
        var prepare = between(code, "val prepareVeltisRuntime", "val verifyVeltisRuntime");
        assertTrue(prepare.contains("\"prepareVeltisRuntime\""),
            "the task must name the step that calls VeltisRuntime.prepare()");
        assertFalse(withoutComments(read(PIPELINE_RUNNER))
                .contains("Downloader.downloadServer"),
            "PipelineRunner must delegate to VeltisRuntime rather than re-assembling"
                + " the chain the launcher runs");

        // And nothing may reintroduce a pre-packaged server jar for the launcher
        // to load: that path is what the bootstrap replaced.
        assertFalse(code.contains("packageVeltisServer"),
            "a pre-packaged jar of patched classes is the pre-bootstrap design; the"
                + " launcher builds its classpath from the workspace now");
        assertFalse(code.contains("writeServerClasspath"),
            "the recorded library classpath existed so the launcher would not have to"
                + " fetch anything; the launcher resolves libraries itself now");
    }

    @Test
    void theBuildStillProvesThePatchedClassesWinBeforeAnyoneRunsTheJar() {
        // Replaces the packaging guard that compared bytes inside the
        // pre-packaged server jar. Three checks, each catching a different mistake:
        // the class exists, it differs from vanilla (a patch that applied nothing
        // compiles to the vanilla class), and the loader serves those exact bytes.
        var code = withoutComments(read("build.gradle.kts"));
        assertTrue(code.contains("\"verifyVeltisRuntime\""),
            "the build must run the guard step; verifying only at server start"
                + " would mean a broken build ships and the failure reaches a user");
        var guard = between(code, "val verifyVeltisRuntime", "val publishBytecodePatch");
        assertTrue(guard.contains("dependsOn(prepareVeltisRuntime)"),
            "the guard has nothing to check until the runtime exists");

        var runner = read(PIPELINE_RUNNER);
        assertTrue(runner.contains("verifyPatchedClasses"),
            "the guard must ask the classloader, not inspect an archive");
        assertTrue(runner.contains("veltisGuardCanary"),
            "a guard that cannot be proven to fail is not a guard; the canary reverses"
                + " the classpath so the guard's failure can be demonstrated");
        assertTrue(guard.contains("gradleProperty(\"veltisGuardCanary\")")
                && guard.contains("systemProperty(\"veltisGuardCanary\""),
            "and the property has to be forwarded into the JVM. A Gradle property is"
                + " not a system property, so without this the canary run reverses"
                + " nothing, the guard passes, and the demonstration silently proves"
                + " the opposite of what it was run to prove");
    }

    @Test
    void startingTheServerRequiresNothingButTheJar() {
        // The requirement in one assertion: `java -jar veltismc.jar --nogui` must
        // work on a machine that has never run Gradle. The build producing that jar
        // is allowed to be expensive -- it is what `buildVeltisMC` is for -- but
        // nothing about the jar's start-up may depend on build output.
        var launcher = withoutComments(read("launcher/src/main/java/org/veltismc/launcher/"
                + "VeltisLauncher.java"));
        assertTrue(launcher.contains("runtime.prepare()"),
            "the launcher must build the runtime it needs; nothing else may");
        assertFalse(launcher.contains("SERVER_JAR"),
            "locating a prebuilt server jar is the pre-bootstrap design");
        assertFalse(launcher.contains("SERVER_CLASSPATH"),
            "reading a recorded library classpath is the pre-bootstrap design; the"
                + " launcher resolves Mojang's libraries itself now");
        assertFalse(launcher.contains("classpath.txt") || launcher.contains(".classpath"),
            "the launcher must not read a classpath file: it builds one from the"
                + " workspace it downloaded into, or it has not downloaded it yet");

        // And the classpath it builds must come from the runtime, which is the one
        // place that knows the workspace layout.
        assertTrue(launcher.contains("runtime.classpath") || launcher.contains("newClassLoader"),
            "the launcher must ask the runtime for its classpath rather than assembling"
                + " one itself, or the two would drift and the guard would be checking"
                + " a classpath the server never uses");

        // A green `build` verifies the runtime and produces the jar, so the
        // expensive path is only ever paid deliberately and the guard runs before
        // anything ships. This block is the last thing in the script, so its
        // dependencies are everything after the anchor.
        var code = withoutComments(read("build.gradle.kts"));
        var build = code.substring(code.indexOf("tasks.named(\"build\")"));
        assertTrue(build.contains("verifyVeltisRuntime"),
            "`build` must run the classloader guard, so a green build means a jar that"
                + " will actually start patched rather than a jar that might");
        assertTrue(build.contains("packageVeltisMC"),
            "`build` must produce the distributable jar, not just VeltisMC's classes");
        assertFalse(build.contains("prepareMinecraft"),
            "the IDE chain and the runtime chain are two callers of one engine; wiring"
                + " `build` to the IDE chain would make it a second definition of the"
                + " same work");
    }

    @Test
    void theDistributableRefusesASourcePatchDirectoryRatherThanIgnoringIt() {
        // The shipped jar carries the patch set as compiled bytecode and ships no
        // pipeline to produce one: no decompiler, no javac, no source patch engine,
        // because none of them belongs in something an operator downloads. A
        // `Shulker/` directory beside the jar therefore means someone expects a
        // feature this jar cannot have.
        //
        // The refusal has to exist, and be checked, because the alternative is
        // worse than a failure: carrying on with the packaged patch set would
        // start a server that quietly ignores the very patches it was pointed at.
        // That is the sort of wrongness an hour of debugging does not reveal.
        var launcher = withoutComments(read("launcher/src/main/java/org/veltismc/launcher/"
                + "VeltisLauncher.java"));
        assertFalse(launcher.contains("VeltisRuntime.fromDirectory"),
            "the launcher must not run the development pipeline; it ships without a"
                + " decompiler and a compiler, so the call could only fail part-way"
                + " through a start with a NoClassDefFoundError naming neither the"
                + " step nor the fix");
        assertTrue(launcher.contains("fromPackagedPatches"),
            "it must start from the patch set packaged in the jar, which is the only"
                + " one an operator has");
        assertTrue(launcher.contains("manifest.patchesRoot()"),
            "but it still has to notice the directory, or there is nothing to refuse"
                + " and the edits are ignored in silence");
        assertTrue(launcher.contains("System.exit(1)"),
            "and refusing must actually stop the start, not log a warning and carry"
                + " on to a server running different patches than the ones on disk");
    }

    // ------------------------------------------------------------------
    // Compile scope and decompile input
    // ------------------------------------------------------------------

    @Test
    void theMinecraftCompileIsScopedToThePatchTargetsNotTheWholeTree() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("setSource(patchTargets(\"code\"))"),
            "source() adds to the source set's own srcDirs, which would hand javac"
                + " every decompiled file; the task must replace the source set");
        assertFalse(withoutComments(root).contains("source(patchTargets("),
            "the additive form silently compiles the whole decompiled tree");
    }

    @Test
    void theDecompileReadsTheSameJarThePatchSetIsCompiledAgainst() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("inputs.files(widenedServerJar, libraryCoordinatesFile)"),
            "the decompile input must be the widened jar");
        assertTrue(root.contains("dependsOn(downloadLibraries, widenServerJarAccess)"),
            "decompiling before widening produces sources whose access modifiers"
                + " cannot be compiled over the widened classes");
    }

    // ------------------------------------------------------------------
    // One workspace, no temporary directories
    // ------------------------------------------------------------------

    @Test
    void theWholeBuildScriptTalksAboutOneWorkspaceLocation() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains(
                "val workspaceDir = layout.projectDirectory.dir(\"build/minecraft/$minecraftVersion\")"),
            "the workspace layout must be declared once, at the top, and it must be the"
                + " development layout under build/ — a checkout's intermediate state is"
                + " build output and belongs where a build's output belongs");
        // The retired layout must not appear anywhere.
        for (var retired : List.of("\"ver\"", "File.separator + \"ver\"",
                "patched-source-baseline", "<home>/vanilla")) {
            assertFalse(root.contains(retired), "the retired path is still referenced: " + retired);
        }
        // And nothing may invent a scratch directory.
        assertFalse(root.contains("createTempDirectory"),
            "the build must not create a temporary directory");
        assertFalse(root.contains("System.getenv(\"TEMP\")")
                || root.contains("java.io.tmpdir"),
            "the build must not depend on the OS temp directory");
    }

    @Test
    void noBuildScriptReferencesTheRetiredLayout() {
        for (var module : List.of("server", "launcher", "patch-engine")) {
            var script = read(module + "/build.gradle.kts");
            assertFalse(script.contains("File.separator + \"ver\" + File.separator"),
                module + "/build.gradle.kts still filters on the retired ver/ layout");
            assertFalse(script.contains(".resolve(\"Shulker\")"),
                module + "/build.gradle.kts must not build its own Shulker/ path; only"
                    + " the launcher packages the patch set, and it does so from the"
                    + " one directory Gradle declares at the top level");
            assertFalse(script.contains(".vlt"),
                module + "/build.gradle.kts names the retired hidden state directory:"
                    + " a server installation must never grow one, so no script may"
                    + " know how to build one");
        }
    }

    @Test
    void theLauncherJarCannotShipMinecraftCode() {
        // The distributable is deliberately code-free: it fetches Minecraft from
        // Mojang at runtime, verified, which is also what makes it legal to
        // distribute. Two independent filters, because either one alone has a
        // blind spot: the path filter misses an artifact resolved from outside the
        // workspace, and the content filter cannot tell a Mojang class from a
        // VeltisMC one that happens to share a package prefix.
        var code = withoutComments(read("launcher/build.gradle.kts"));
        assertTrue(code.contains("notMinecraftArtifact"),
            "artifacts resolved from the pipeline workspace must be filtered out");
        for (var owned : List.of("net/minecraft/", "com/mojang/", "org/bukkit/",
                "org/spigotmc/")) {
            assertTrue(code.contains("\"" + owned + "\""),
                "the jar must exclude " + owned + " by content as well as by path");
        }
    }

    // ------------------------------------------------------------------
    // IDE integration
    // ------------------------------------------------------------------

    @Test
    void theMinecraftSourceSetIsDeclaredSoIntellijResolvesItWithoutMarkingRoots() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("val minecraft by sourceSets.creating"),
            "the patched sources must live in a real source set, not in an"
                + " idea{} module hack that goes stale when the version changes");
        assertTrue(root.contains("java.setSrcDirs(listOf(patchedDir.asFile))"),
            "the source set's root is the patched workspace");
        assertFalse(root.contains("sourceDirs("),
            "the idea{} sourceDirs workaround is what required Mark Sources Root");
        assertFalse(root.contains("isDirectory) idea"),
            "the conditional idea{} hook is what made the IDE depend on build state");
    }

    @Test
    void theIdeExcludesNothingThatIsASourceRoot() {
        // Two failures, one cause: `excludeDirs += .vlt` told IntelliJ to ignore the
        // very directory the `minecraft` source set points at, and
        // `compileClasspath = files()` gave that source set nothing to resolve
        // against. Marking the directory by hand fixed the first and not the
        // second, so autocomplete and Ctrl+Click still did not work.
        var code = withoutComments(read("build.gradle.kts"));
        var match = Pattern.compile("excludeDirs\\s*=\\s*excludeDirs\\s*\\+\\s*setOf\\(([^)]*)\\)")
            .matcher(code);
        var excluded = match.find() ? match.group(1) : "";
        assertFalse(excluded.contains(".vlt"),
            "excluding the workspace from the IDE excludes the source root the"
                + " `minecraft` source set declares, which is what made IntelliJ"
                + " require a manual Mark Sources Root in every checkout");
        assertFalse(excluded.contains("patched"),
            "patched/ is the tree a contributor actually opens and edits");
        // .gradle and build/ are still excluded: they are caches and outputs.
        assertTrue(excluded.contains("\"build\"") || excluded.contains(".gradle"),
            "caches and outputs should still be excluded from indexing");

        assertTrue(code.contains("compileClasspath = minecraftSourceSetClasspath"),
            "the source set must resolve against a real classpath; an empty one makes"
                + " every cross-package reference unresolvable in the IDE");
        assertFalse(code.contains("compileClasspath = files()"),
            "an empty compile classpath is what left autocomplete and navigation"
                + " broken even after the source root was marked");
    }

    @Test
    void thePatchTargetsComeFromTheEngineRatherThanBeingReParsedInGradle() {
        var root = read("build.gradle.kts");
        assertTrue(root.contains("patch-targets.txt"),
            "Gradle must read the manifest the patcher wrote, not re-parse patch text");
        assertFalse(root.contains("Regex("),
            "no patch text may be parsed in a Gradle script");
        assertTrue(root.contains("inputs.files(patchTargetsFile)")
                || root.contains("patchTargetsFile.asFile"),
            "the manifest must be a tracked input/output of the steps that produce it");
    }

    // ------------------------------------------------------------------
    // Deterministic configuration
    // ------------------------------------------------------------------

    @Test
    void theMinecraftVersionAndWorkerCountAreBuildProperties() {
        var properties = read("gradle.properties");
        assertTrue(Pattern.compile("(?m)^minecraftVersion=\\d+\\.\\d+")
                .matcher(properties).find(),
            "minecraftVersion must be a single documented property, so a version bump"
                + " is one line and no URL is ever hardcoded");
        assertTrue(Pattern.compile("(?m)^patchWorkers=\\d+$").matcher(properties).find(),
            "patchWorkers must be an explicit property, not derived from the machine");

        var root = read("build.gradle.kts");
        assertFalse(root.contains("ForkJoinPool"),
            "the worker pool must never be the shared common pool");
        assertTrue(root.contains("providers.gradleProperty(\"patchWorkers\")"),
            "the worker count must be read from a build property");
        assertTrue(root.contains(
                ".orElse(maxOf(1, Runtime.getRuntime().availableProcessors() - 1))"),
            "the fallback must be a documented one, with gradle.properties setting it");
    }

    @Test
    void noModuleDefinesItsOwnViewOfTheMinecraftWorkspace() {
        for (var module : List.of("server", "launcher", "patch-engine")) {
            var code = withoutComments(read(module + "/build.gradle.kts"));
            if (module.equals("launcher")) {
                // The one permitted mention, and it is a subtraction rather than a
                // second source of truth: Minecraft's artifacts have to be filtered
                // OUT of the code-free launcher jar, which needs to know where they are.
                assertTrue(code.contains("notMinecraftArtifact"),
                    "launcher/build.gradle.kts must filter Minecraft artifacts out of the uber jar");
                continue;
            }
            assertFalse(code.contains(".vlt"),
                module + "/build.gradle.kts must not reach into the workspace: the root"
                    + " build contributes the classpath, so there is one definition of it");
            assertFalse(code.contains("patchedDir"),
                module + "/build.gradle.kts duplicates the workspace layout");
        }
    }

    // ------------------------------------------------------------------
    // Structure: the module list and the retired state directory
    // ------------------------------------------------------------------

    /**
     * Three modules, and the list is the decision.
     *
     * <p>Pinned because it is the easiest thing in a build to grow back. Each
     * name here answers to something real — an operator runs {@code :launcher},
     * the pipeline is {@code :patch-engine}, everything that needs Minecraft
     * compiles in {@code :server} — and the moment a fourth appears someone has
     * to say which of those it is not.
     */
    @Test
    void thereAreExactlyThreeModulesAndEachIsARealBoundary() {
        var settings = read("settings.gradle.kts");
        var included = new ArrayList<String>();
        var matcher = Pattern.compile("\":([A-Za-z0-9_-]+)\"").matcher(settings);
        while (matcher.find()) {
            included.add(":" + matcher.group(1));
        }
        assertEquals(List.of(":launcher", ":patch-engine", ":server"), included,
            "settings.gradle.kts is the module list; everything else follows from it");
        assertFalse(settings.contains(":build-tools"),
            "build-tools held one class whose only callers already depended on the"
                + " module that class calls into");
        assertFalse(settings.contains(":runtime") || settings.contains(":world"),
            "runtime and world were separate projects that were never depended on from"
                + " outside the repository and were always packaged into one jar in a"
                + " fixed order by a script; three modules for one deployable unit is a"
                + " boundary maintained for its own sake");
    }

    /**
     * The retired state directory must be gone from every file that builds or
     * runs the thing, not merely unused.
     *
     * `.vlt` was how a checkout remembered itself: a hidden directory whose
     * presence implied readiness, which a server installation would inherit as
     * litter and a half-finished run could leave behind looking complete. The
     * installation's record now lives inside the jar it describes.
     *
     * <p>Prose that explains all that is allowed to name it; code that would
     * build such a path is not. The test looks for the literal, because that is
     * what a path constant is.
     */
    @Test
    void nothingThatBuildsOrRunsTheServerMentionsTheRetiredStateDirectory() {
        var roots = List.of(
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "launcher/build.gradle.kts", "server/build.gradle.kts",
            "patch-engine/build.gradle.kts");
        for (var script : roots) {
            assertFalse(read(script).contains(".vlt"),
                script + " still knows about the retired state directory");
        }
        var sources = new ArrayList<String>();
        sources.addAll(javaFiles("launcher/src/main/java"));
        sources.addAll(javaFiles("server/src/main/java"));
        sources.addAll(javaFiles("patch-engine/src/main/java"));
        for (var source : sources) {
            assertFalse(read(source).contains("\".vlt"),
                source + " still builds a path under the retired state directory");
        }
    }

    /**
     * The source root an IDE has to resolve lives under {@code build/}, so the
     * blanket exclusion Gradle's Idea plugin applies to a root project has to be
     * opened up exactly there and nowhere else.
     *
     * <p>The failure this guards is invisible in every terminal: the build goes
     * green, Ctrl+Click on {@code MinecraftServer} does nothing, and the remedy
     * anyone reaches for is the manual "Mark Directory as Sources Root" that the
     * source set exists to make unnecessary.
     */
    @Test
    void theIdeExclusionLeavesTheWorkspaceVisible() {
        var code = withoutComments(read("build.gradle.kts"));
        assertTrue(code.contains("val workspace = layout.projectDirectory.dir(\"build/minecraft\").asFile"),
            "the workspace must be named where the exclusion is decided, so it can be"
                + " held back from it");
        assertTrue(code.contains(".filter { it != workspace }"),
            "every other child of build/ is still excluded, but the workspace is not:"
                + " excluding a directory that contains a source root makes the IDE"
                + " lose the source root, and the build still reports success");
        assertTrue(code.contains("val rootBuild = layout.projectDirectory.dir(\"build\").asFile"),
            "and build/ has to be un-excluded as a whole before it can be re-excluded"
                + " one child at a time, or the child exclusions are shadowed");
        assertTrue(code.contains("sourceDirs = sourceDirs + minecraft.allSource.sourceDirectories.files"),
            "`gradlew idea` writes .iml files itself and Gradle's Idea plugin fills them"
                + " from the main and test source sets only, so without this the root"
                + " module it emits has no source roots at all — a legacy import would"
                + " be blind to MinecraftServer while the Gradle model was correct."
                + " Derived from the source set rather than spelled out, so there is"
                + " still one declaration of where the patched tree lives");
    }

    private static List<String> javaFiles(String relativePath) {
        var root = REPO.resolve(relativePath);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(root + " does not exist");
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                .map(root::relativize)
                .map(Object::toString)
                .map(p -> relativePath + "/" + p)
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + root, e);
        }
    }

    private static int count(String haystack, String needle) {
        var found = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            found++;
        }
        return found;
    }

    /**
     * The text between two declarations, so a test can assert about one task's own
     * block instead of the whole script -- where the same call legitimately appears
     * for a different task.
     */
    private static String between(String script, String from, String to) {
        var start = script.indexOf(from);
        assertTrue(start >= 0, "no '" + from + "' in the build script");
        var end = script.indexOf(to, start);
        assertTrue(end > start, "no '" + to + "' after '" + from + "' in the build script");
        return script.substring(start, end);
    }
}
