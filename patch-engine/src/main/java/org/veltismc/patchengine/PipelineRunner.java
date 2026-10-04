package org.veltismc.patchengine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Gradle-facing entry point for the VeltisMC build pipeline.
 *
 * <p>Deliberately thin, and deliberately the <em>only</em> place Gradle can reach
 * the pipeline. Gradle needs a {@code main} to call, and this is it — every step
 * here wires an engine class to a workspace and reports what happened. No
 * behaviour lives in this file, which is what lets the launcher build the very
 * same runtime in-process without any risk of the two paths drifting apart:
 * {@link #prepareVeltisRuntime} calls the exact code {@code java -jar server.jar}
 * calls.
 *
 * <p>It lives in the engine rather than in a module of its own because that is
 * what it is: a front end on this package. A separate module existed to keep the
 * Gradle entry point from becoming a dependency of the launcher, but the launcher
 * never depended on it — it depends on {@link VeltisRuntime} directly — so the
 * boundary was guarding nothing and cost a module, a jar and a line in
 * {@code settings.gradle.kts} to maintain.
 *
 * <p>Usage: {@code PipelineRunner <step> <minecraftVersion> [projectDir] [workers] [release]}.
 * Steps that touch the network resolve everything through {@link MojangMetadata},
 * so there are no artifact URLs in this file and none to go stale.
 */
public final class PipelineRunner {

    private static final Logger LOG = LogManager.getLogger(PipelineRunner.class);

    private PipelineRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("""
                Usage: PipelineRunner <step> <minecraftVersion> [projectDir] [workers] [release]

                Steps:
                  downloadMinecraft      resolve the version and fetch the verified server jar
                  downloadLibraries      fetch and verify Mojang's declared libraries
                  widenServerJarAccess   widen access in the verified classes jar
                  decompileMinecraft     decompile the widened jar into the pristine tree
                  applyVeltisPatches     mirror source/ and apply Shulker/{code,data,modules}
                  prepareVeltisRuntime   run the whole chain and record the runtime marker
                  verifyVeltisRuntime    load the runtime classpath and prove it is the patched one
                  rebuildVeltisPatches   regenerate the patch set from patched/
                  cleanVeltisPatches     discard patched/ and classes/
                """);
            System.exit(2);
            return;
        }
        var step = args[0];
        var version = MinecraftVersion.parse(args[1]);
        var projectDir = Path.of(args.length > 2 ? args[2] : ".").toAbsolutePath().normalize();
        var workers = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        var release = args.length > 4 ? Integer.parseInt(args[4])
            : Runtime.version().feature();
        var workspace = VeltisWorkspace.of(projectDir, version);

        try {
            switch (step) {
                case "downloadMinecraft" -> downloadMinecraft(workspace, version);
                case "downloadLibraries" -> downloadLibraries(workspace, version);
                case "widenServerJarAccess" -> widenServerJarAccess(workspace);
                case "decompileMinecraft" -> decompileMinecraft(workspace, version);
                case "applyVeltisPatches" -> applyVeltisPatches(workspace, version, workers);
                case "prepareVeltisRuntime" -> prepareVeltisRuntime(workspace, version,
                    VeltisRuntime.fromDirectory(projectDir, version, workspace.shulkerDirectory(),
                        workers, release));
                case "verifyVeltisRuntime" -> verifyVeltisRuntime(
                    VeltisRuntime.fromDirectory(projectDir, version, workspace.shulkerDirectory(),
                        workers, release));
                case "rebuildVeltisPatches" -> rebuildVeltisPatches(workspace, version);
                case "cleanVeltisPatches" -> cleanVeltisPatches(workspace);
                default -> {
                    System.err.println("Unknown pipeline step: " + step);
                    System.exit(2);
                }
            }
        } catch (RuntimeException e) {
            // The engine's messages are already complete, developer-facing
            // reports. A stack trace on top of them only buries the cause, so it
            // is kept for the debugger unless one is asked for explicitly.
            LOG.error(e.getMessage());
            if (Boolean.getBoolean("veltismc.debug")) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    /**
     * Builds the runtime: everything from validating the existing artifact to
     * writing the jar that records what produced it.
     *
     * <p>This is the prebuild path a distribution or a CI job uses, and it is the
     * same {@link VeltisRuntime#prepare()} the launcher runs on a cold start. A
     * jar built this way starts without the server doing any pipeline work,
     * because the record inside it already matches.
     *
     * <p>Then it records the library coordinate list, which is this task's to
     * write and not the runtime's: the Gradle build declares the whole workspace
     * as this task's output, {@code :launcher:writeBootstrapLibraries} reads that
     * list afterwards, and the {@code downloadMinecraft} step that writes it for
     * the IDE path is deliberately not in this build's graph. The list derives
     * from the version metadata {@link VeltisRuntime#prepare()} just resolved, so
     * this is a cache read rather than network work — and no server install ever
     * runs this method, which is why an install's directory holds no coordinate
     * file by construction.
     */
    private static void prepareVeltisRuntime(VeltisWorkspace workspace, MinecraftVersion version,
                                             VeltisRuntime runtime) {
        runtime.prepare();
        var metadata = new MojangMetadata().resolve(workspace, version);
        MojangMetadata.cacheLibraryCoordinates(workspace, metadata.libraries());
    }

    /**
     * Proves the runtime would actually load its patched classes, by building the
     * real classloader and asking it.
     *
     * <p>This replaced a build-time check that unpacked a server jar and compared
     * bytes. That check was worth keeping but it could only answer "is the
     * patched class in the archive?", and the failure it exists to catch is a
     * classpath decision, not an archive one: vanilla winning because an entry
     * was ordered wrongly, or because a shadowing library appeared. Asking the
     * loader that will really be used answers the question directly, and
     * refuses to run the server when the answer is no.
     *
     * <p>{@code -PveltisGuardCanary} puts vanilla's classes ahead of the runtime
     * artifact so this guard can be proven to fail rather than merely claimed to.
     */
    private static void verifyVeltisRuntime(VeltisRuntime runtime) {
        var canary = Boolean.getBoolean("veltisGuardCanary")
            || System.getenv("VELTIS_GUARD_CANARY") != null;
        var loader = canary
            ? runtime.vanillaFirstClassLoader(runtime.workspace().root())
            : runtime.newClassLoader(runtime.workspace().root());
        if (canary) {
            LOG.warn("[VeltisGuard] -PveltisGuardCanary: vanilla classes placed ahead of the"
                + " runtime artifact on purpose; this run is expected to fail");
        }
        LOG.info("[VeltisGuard] {}", runtime.verifyPatchedClasses(loader));
    }

    /**
     * Resolves the version against Mojang, fetches the server jar and records the
     * library coordinate list Gradle builds its classpath from. Every artifact is
     * SHA-1 verified before it is used.
     */
    private static void downloadMinecraft(VeltisWorkspace workspace, MinecraftVersion version) {
        workspace.createDirectories();
        var started = System.nanoTime();
        var metadata = new MojangMetadata().resolve(workspace, version);
        var server = new MinecraftDownloader().downloadServer(metadata, workspace);
        MojangMetadata.cacheLibraryCoordinates(workspace, metadata.libraries());

        LOG.info("[VeltisMinecraft] Server jar for {} {} ({} bytes, {} declared libraries) in {}",
            version,
            describe(server.outcome().name().toLowerCase(java.util.Locale.ROOT)),
            server.bytes(),
            metadata.libraries().size(),
            VeltisConsole.formatDuration(System.nanoTime() - started));
    }

    /**
     * Fetches the library jars Mojang declares. Separate from the server download
     * because it is a hundred-odd small files against one 60 MB artifact, and
     * because they fail for entirely different reasons: a library that 404s is a
     * different message than a server jar whose SHA-1 does not match.
     */
    private static void downloadLibraries(VeltisWorkspace workspace, MinecraftVersion version) {
        workspace.createDirectories();
        var started = System.nanoTime();
        var metadata = new MojangMetadata().resolve(workspace, version);
        var fetched = new MinecraftDownloader().downloadLibraries(metadata, workspace);

        LOG.info("[VeltisMinecraft] Libraries for {}: {} declared, {} fetched in {}",
            version, metadata.libraries().size(), fetched,
            VeltisConsole.formatDuration(System.nanoTime() - started));
    }

    /**
     * Widens class, field and method access in the verified classes jar.
     *
     * <p>Runs before the decompile, and that ordering is the point: the
     * decompiled source is compiled against the widened jar, so a method Mojang
     * declared {@code protected} is {@code public} in both. Decompiling the
     * un-widened jar instead produces sources that cannot be compiled over the
     * widened classes at all.
     */
    private static void widenServerJarAccess(VeltisWorkspace workspace) {
        var classes = workspace.vanillaClassesJar();
        String sha1;
        try {
            sha1 = MojangMetadata.sha1(classes);
        } catch (java.io.IOException e) {
            throw new PatchEngineException(
                "[VeltisMC] Cannot widen " + classes + ": it is missing."
                    + "\n  Reason: run the downloadMinecraft step first;"
                    + " widening operates on verified Minecraft classes", e);
        }
        AccessWidener.widen(classes, workspace.widenedServerJar(), sha1);
    }

    /** Decompiles the verified server jar into the workspace's pristine source tree. */
    private static void decompileMinecraft(VeltisWorkspace workspace, MinecraftVersion version) {
        var started = System.nanoTime();
        var metadata = new MojangMetadata().resolve(workspace, version);
        // The decompiler resolves external types against these, so they are
        // ensured here rather than assumed from the task graph. Everything is
        // already verified, so this costs one hash pass and no network.
        new MinecraftDownloader().downloadLibraries(metadata, workspace);
        var outcome = new MinecraftDecompiler().decompile(workspace, metadata);
        if (outcome == MinecraftDecompiler.Outcome.CACHED) {
            LOG.info("[VeltisMinecraft] Decompiled source for {} was already current ({})",
                version, VeltisConsole.formatDuration(System.nanoTime() - started));
        }
    }

    /**
     * Mirrors the pristine source into the patched workspace and applies the
     * whole patch set to it. Mirroring first is what makes the step idempotent:
     * a failed run leaves nothing behind that the next run would inherit.
     *
     * <p>Does not compile. Compilation is {@link #prepareVeltisRuntime}'s job, so
     * the build and the launcher compile through one implementation.
     */
    private static void applyVeltisPatches(VeltisWorkspace workspace, MinecraftVersion version,
                                           int workers) {
        long mirrorStarted = System.nanoTime();
        var stats = new PatchStats();
        var patches = PatchDiscovery.discover(workspace.shulkerDirectory(), version.toString(),
            stats);
        if (patches.isEmpty()) {
            LOG.warn("[Veltis] No patches found in {}; the build will be unpatched",
                workspace.shulkerDirectory());
        }

        var mirrored = VeltisPatcher.mirrorPristineSource(
            workspace.sourceDirectory(), workspace.patchedDirectory());
        LOG.info("[Veltis] Mirrored {} pristine source file{} into {} in {}",
            mirrored, mirrored == 1 ? "" : "s", workspace.patchedDirectory(),
            VeltisConsole.formatDuration(System.nanoTime() - mirrorStarted));

        // The apply phase reports one line on success, from VeltisPatcher itself:
        // the kidnapping message. No second summary is printed here.
        new VeltisPatcher(workers, version.toString()).apply(workspace, patches);
    }

    /** Regenerates the patch set from the current contents of the patched tree. */
    private static void rebuildVeltisPatches(VeltisWorkspace workspace, MinecraftVersion version) {
        var started = System.nanoTime();
        var result = new PatchRebuilder().rebuild(workspace, workspace.shulkerDirectory(),
            version.toString());
        LOG.info("[Veltis] Patch set rebuilt: {} regenerated, {} created, {} removed,"
                + " {} file{} covered ({})",
            result.regenerated(), result.created(), result.removed(), result.targets(),
            result.targets() == 1 ? "" : "s",
            VeltisConsole.formatDuration(System.nanoTime() - started));
    }

    /**
     * Discards the patched workspace, its compiled output and its resources,
     * leaving the pristine decompile and the verified downloads untouched. The
     * runtime marker and the packaged artifact go too, so a server start
     * rebuilds rather than loading classes whose sources no longer exist.
     */
    private static void cleanVeltisPatches(VeltisWorkspace workspace) {
        VeltisWorkspace.deleteTree(workspace.patchedDirectory());
        VeltisWorkspace.deleteTree(workspace.classesDirectory());
        VeltisWorkspace.deleteTree(workspace.resourcesDirectory());
        RuntimeIdentity.invalidate(workspace);
        try {
            Files.deleteIfExists(workspace.veltisServerJar());
        } catch (java.io.IOException e) {
            throw new PatchEngineException(
                "[Veltis] Failed to remove " + workspace.veltisServerJar(), e);
        }
        LOG.info("[Veltis] Cleared the patched workspace and compiled classes in {}",
            workspace.root());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String describe(String outcome) {
        return switch (outcome) {
            case "cached" -> "already downloaded";
            case "recovered" -> "recovered from a corrupt cache";
            default -> "downloaded";
        };
    }
}
