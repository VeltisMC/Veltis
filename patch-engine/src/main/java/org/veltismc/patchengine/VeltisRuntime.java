package org.veltismc.patchengine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/**
 * Builds, validates and loads the VeltisMC runtime, and is the only place that
 * knows the order the pieces go together in.
 *
 * <p>There used to be two of those places: a Gradle pipeline that ran at build
 * time and produced a pre-packaged server jar, and a launcher that refused to
 * start without it. That split is gone. {@code java -jar server.jar} calls
 * {@link #prepare()} and gets the same result {@code ./gradlew buildVeltisMC}
 * gets, against the same code, because the pipeline tasks and the launcher both
 * end up here.
 *
 * <h2>The chain</h2>
 *
 * <p>Resolve the version metadata, fetch and SHA-1 verify the server artifact,
 * fetch and verify the libraries, apply the bytecode patch set, verify the
 * result, and only then hand back a classpath. Which half of that a caller runs
 * depends on what it already has:
 *
 * <ol>
 *   <li><b>Validate first.</b> Before anything is fetched, the existing
 *       {@code veltis-server.jar} is asked what it is and the vanilla artifact
 *       beside it is hashed. When they agree with what this build would
 *       produce, nothing else happens. A warm start therefore does no network
 *       work, no decompilation and no compilation — and, in a server
 *       installation, no source patching of any kind, because there is no source
 *       there to patch.</li>
 *   <li><b>Metadata.</b> {@link MojangMetadata} reads the cached
 *       {@code version.json} when it names the requested version, so a machine
 *       with no network and a warm cache resolves offline.</li>
 *   <li><b>Server jar.</b> Mojang publishes a bundler jar; its SHA-1 is checked
 *       before the inner {@code server-<version>.jar} is lifted out of it.</li>
 *   <li><b>Libraries.</b> Each is verified against its own published SHA-1.</li>
 *   <li><b>The patch set.</b> {@link BytecodePatch}, loaded and validated in
 *       full — format version, target version, artifact SHA-1, baseline SHA-1,
 *       fingerprint — before a single byte of the result is written.</li>
 *   <li><b>Apply.</b> A checkout runs its own source pipeline first (widen,
 *       decompile, patch, compile) and then <em>generates</em> a patch set from
 *       it; a server installation has no source pipeline and uses the patch set
 *       its jar was built with. Both then take the same route: copy the vanilla
 *       classes jar, replace exactly the entries the index names, verify every
 *       original hash before writing and every result hash after, and write the
 *       record of what produced it last.</li>
 *   <li><b>Verify.</b> The guard runs against the class files that are about to
 *       be published, so a runtime that would not actually serve patched classes
 *       never becomes an artifact.</li>
 * </ol>
 *
 * <p>The development source pipeline is deliberately confined to a checkout. A
 * server installation never widens, decompiles or compiles anything: it has no
 * {@code server/Shulker/}, no decompiled source and no javac, and adding those
 * to an operator's machine would be re-introducing the build step this design
 * exists to remove.
 *
 * <h2>Why a runtime is either complete or absent</h2>
 *
 * <p>The record of a build lives inside the jar it describes and nowhere else,
 * and it is written last: staging file, verify, atomic move. A crash between the
 * last class file and the move leaves a {@code .writing} sibling the next run
 * deletes rather than a jar the next run would trust.
 *
 * <h2>Why the classes are the patched ones</h2>
 *
 * <p>Ordering a classpath is not proof that it was honoured, so it is not left
 * to ordering alone. {@link #verifyPatchedClasses} asks the live loader where it
 * resolved each patched class from and compares the bytes it will hand out with
 * the bytes the patch set says they must be, and fails the launch if either
 * disagrees. A silent fallback to vanilla is the failure mode this whole
 * pipeline exists to prevent, so it is checked rather than assumed.
 */
public final class VeltisRuntime {

    private static final org.apache.logging.log4j.Logger LOG =
        org.apache.logging.log4j.LogManager.getLogger(VeltisRuntime.class);

    /**
     * One line of {@link RuntimeIdentity#JAR_GUARD_ENTRY}.
     *
     * @param compiledSha256 SHA-256 of the class bytes as packaged — the merged
     *                       class a class delta produced, or the whole payload
     *                       for an entry the baseline lacked
     * @param baselineSha256 SHA-256 of the vanilla class those bytes replaced,
     *                       or {@link BytecodePatch#ABSENT} for an added entry
     */
    public record GuardedClass(String compiledSha256, String baselineSha256) {
    }

    private final VeltisWorkspace workspace;
    private final MinecraftVersion version;
    /**
     * The development source patch set, or {@code null} in a server installation.
     *
     * <p>{@code null} rather than an empty list, because "there is no source
     * patch set here" and "the source patch set is empty" fail for opposite
     * reasons and must not be reported the same way: the first is the normal
     * state of an installation, the second is a checkout that would build a
     * server running vanilla.
     */
    private final Supplier<List<VeltisPatch>> patchSet;
    /** How to reach the packaged bytecode patch set, or {@code null} in a checkout. */
    private final ClassLoader packagedPatch;
    private final int workers;
    private final int classFileRelease;

    private RuntimeIdentity identity;

    private VeltisRuntime(VeltisWorkspace workspace, MinecraftVersion version,
                          Supplier<List<VeltisPatch>> patchSet, ClassLoader packagedPatch,
                          int workers, int classFileRelease) {
        this.workspace = workspace;
        this.version = version;
        this.patchSet = patchSet;
        this.packagedPatch = packagedPatch;
        this.workers = Math.max(1, workers);
        this.classFileRelease = classFileRelease;
    }

    /**
     * A runtime built from a patch set in the project tree — the Gradle path,
     * and the layout an IDE reads.
     *
     * <p>This is the only layout that runs the development source pipeline, and
     * the only one that generates a bytecode patch set: it has
     * {@code server/Shulker/}, a decompiled tree to apply them to, and a
     * compiler to compile the result with.
     *
     * @param workspaceBase the project root; the workspace it creates lives in
     *                      {@code minecraft/workspace/<version>}, with the
     *                      pristine decompile beside it at
     *                      {@code minecraft/<version>}
     * @param patchesDirectory {@code server/Shulker/} in the project
     */
    public static VeltisRuntime fromDirectory(Path workspaceBase, MinecraftVersion version,
                                              Path patchesDirectory, int workers,
                                              int classFileRelease) {
        var root = Objects.requireNonNull(patchesDirectory, "patchesDirectory cannot be null");
        return new VeltisRuntime(VeltisWorkspace.of(workspaceBase, version), version,
            () -> PatchDiscovery.discover(root, version.toString(), new PatchStats()),
            null, workers, classFileRelease);
    }

    /**
     * A runtime whose patch set is packaged inside the launcher jar — the path a
     * server operator takes, where the only file they have is a jar.
     *
     * <p>The workspace it creates is a server installation: {@code Vanilla/} and
     * {@code Veltis/} beside the server, and a private work directory that does
     * not survive the build. There is no source patch set here and none is
     * looked for: everything that makes the server VeltisMC rather than vanilla
     * is in the jar, as compiled classes with both hashes on every entry.
     *
     * @param classLoader the loader carrying
     *                    {@code META-INF/veltis/patches/<version>.zip}
     */
    public static VeltisRuntime fromPackagedPatches(Path workspaceBase, MinecraftVersion version,
                                                    ClassLoader classLoader, int workers,
                                                    int classFileRelease) {
        var loader = Objects.requireNonNull(classLoader, "classLoader cannot be null");
        return new VeltisRuntime(VeltisWorkspace.serverInstall(workspaceBase, version), version,
            null, loader, workers, classFileRelease);
    }

    public VeltisWorkspace workspace() {
        return workspace;
    }

    /** The identity of the runtime that is currently built, or was just built. */
    public RuntimeIdentity identity() {
        return identity;
    }

    /**
     * The revision of this runtime's development source patch set.
     *
     * <p>Separate from {@link #identity()} because the two answer different
     * questions. The identity is "what is on disk right now" and is only set once
     * a build finishes; this is "what would a build use" and is answerable
     * immediately. A caller comparing against a recorded marker needs the latter.
     *
     * <p>{@link RuntimeIdentity#NO_SOURCE_PATCHES} in a server installation,
     * which has no source patches and never will.
     *
     * @throws PatchEngineException when a checkout's patch set is missing or empty
     */
    public String sourcePatchRevision() {
        if (patchSet == null) {
            return RuntimeIdentity.NO_SOURCE_PATCHES;
        }
        return RuntimeIdentity.sourceRevisionOf(requirePatches());
    }

    private List<VeltisPatch> requirePatches() {
        if (patchSet == null) {
            throw new PatchEngineException(
                "[VeltisPatch] This runtime has no development source patch set"
                    + "\n  Reason: it was created from a packaged patch set, which ships"
                    + " compiled classes rather than patches to apply"
                    + "\n  Fix: this is a programming error; a packaged runtime must not be"
                    + " asked for source patches");
        }
        var discovered = patchSet.get();
        if (discovered.isEmpty()) {
            throw new PatchEngineException(
                "[VeltisPatch] The Veltis patch set is empty"
                    + "\n  Reason: an empty patch set produces a server that starts, runs"
                    + " vanilla and gives no sign that no patch was applied"
                    + "\n  Fix: put at least one patch under server/Shulker/code, or check that"
                    + " this jar was built with a patch set");
        }
        return discovered;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Ensures the runtime artifact matches this patch set and this Minecraft
     * build, building whatever is missing or stale.
     *
     * @return whether anything was rebuilt
     * @throws PatchEngineException with a complete report on any failure; the
     *                              record of a successful build is not written,
     *                              so the next attempt starts from a known state
     */
    /**
     * Ensures the runtime artifact matches this patch set and this Minecraft
     * build, building whatever is missing or stale.
     *
     * <p>The two layouts reach the same artifact by different roads, and that is
     * deliberate. A checkout runs the development pipeline — widen, decompile,
     * apply the source patch set, compile — and then turns what it produced into
     * a bytecode patch set and applies <em>that</em> to vanilla. A server
     * installation has no source to widen, decompile or compile, so it runs none
     * of it and applies the patch set its own jar was built with. Both roads end
     * in the same {@link #applyBytecodePatch} call, so every build exercises the
     * distribution path rather than merely resembling it.
     *
     * @return whether anything was rebuilt
     * @throws PatchEngineException with a complete report on any failure; the
     *                              record of a successful build is not written,
     *                              so the next attempt starts from a known state
     */
    public boolean prepare() {
        LOG.debug("Minecraft version: {}", version.toString());
        long started = System.nanoTime();
        var checkout = !workspace.isServerInstall();

        // ----------------------------------------------------------------
        // What this build would use. Everything in this block is local — a
        // warm start is an inspection, and an inspection that needs the network
        // is not one.
        // ----------------------------------------------------------------
        String sourceRevision = RuntimeIdentity.NO_SOURCE_PATCHES;
        List<VeltisPatch> sourcePatches = List.of();
        if (checkout) {
            long discoveryStarted = System.nanoTime();
            sourcePatches = requirePatches();
            sourceRevision = RuntimeIdentity.sourceRevisionOf(sourcePatches);
            int addressed = sourcePatches.stream().mapToInt(p -> p.targets().size()).sum();
            LOG.debug("Source patch set: {} patch{} addressing {} file{} (discovered in"
                    + " {})",
                sourcePatches.size(), sourcePatches.size() == 1 ? "" : "es",
                addressed, addressed == 1 ? "" : "s",
                VeltisConsole.formatDuration(System.nanoTime() - discoveryStarted));
        }

        var patch = loadBytecodePatch();
        if (patch.isPresent()) {
            requireVersionMatches(patch.get().metadata());
            LOG.debug("Bytecode patch set: {} entries ({} classes, {} resources),"
                    + " format {}, fingerprint {}",
                patch.get().metadata().entryCount(), patch.get().metadata().classCount(),
                patch.get().metadata().resourceCount(),
                patch.get().metadata().formatVersion(),
                shorten(patch.get().metadata().fingerprint()));
        }

        var current = validateArtifact(patch.orElse(null), sourceRevision);
        if (current.isPresent()) {
            identity = current.get();
            LOG.debug("Runtime is current; not rebuilding it."
                    + " Validated {} in {}.",
                workspace.veltisServerJar().getFileName(),
                VeltisConsole.formatDuration(System.nanoTime() - started));
            return false;
        }

        workspace.createDirectories();

        long phase = System.nanoTime();
        var metadata = new MojangMetadata().resolve(workspace, version);
        if (!metadata.id().equals(version.toString())) {
            // Mojang resolving a different id than the one asked for would mean
            // every patch is authored against classes that will never exist.
            throw new PatchEngineException(
                "[VeltisMinecraft] Mojang resolved version '" + version.toString()
                    + "' to '" + metadata.id() + "'"
                    + "\n  Reason: the requested version is not the version Mojang publishes"
                    + "\n  Fix: check the minecraftVersion setting and that this version still exists");
        }
        LOG.debug("Resolved Minecraft {} against Mojang in {} (server SHA-1 {})",
            metadata.id(), VeltisConsole.formatDuration(System.nanoTime() - phase),
            metadata.serverArtifact().sha1());

        // Invalidate first. Everything after this point leaves no record of
        // success, so a run that fails here has to be retried from the top
        // rather than resumed from a claim of completeness.
        RuntimeIdentity.invalidate(workspace);
        LOG.debug("Runtime is not built for this version and patch set; building it.");

        if (patch.isPresent()) {
            requireArtifactMatches(patch.get().metadata(), metadata);
        }

        phase = System.nanoTime();
        fetchArtifacts(metadata);
        report("Fetched and verified Mojang's artifacts", phase);

        if (patch.isPresent()) {
            requireBaselineMatches(patch.get().metadata());
        }

        if (checkout) {
            phase = System.nanoTime();
            widenAccess(metadata);
            report("Widened access in the server jar", phase);

            phase = System.nanoTime();
            decompile(metadata);
            report("Decompiled the Minecraft sources", phase);

            applyPatches(sourcePatches);

            phase = System.nanoTime();
            compileSources();
            report("Compiled the patched sources", phase);

            // Verification before the patch set is cut: the guard runs against
            // the class files that are about to become payloads, so a runtime
            // that would not actually serve patched classes never reaches a jar.
            phase = System.nanoTime();
            verifyCompiledOutput(sourceRevision);
            report("Verified the compiled output", phase);
            // The success line is deliberately NOT printed here. A checkout
            // build finishes with the launcher's own runtime guard, and that
            // guard prints it — once, after the classloader has proven the
            // runtime serves patched classes, and only on the runs that
            // prepare() reports as rebuilt. Printing it here too would put the
            // line twice into a first start, and printing it on every start
            // would claim work a warm start did not do.

            phase = System.nanoTime();
            var generated = BytecodePatchGenerator.generate(workspace, version,
                metadata.serverArtifact().sha1(), classFileRelease, sourceRevision,
                sourcePatches.size(), workers);
            report("Generated the bytecode patch set", phase);
            // Read back what was written. This is a round trip rather than a
            // re-use of the in-memory metadata on purpose: the applier reads
            // this file, so a file this build cannot read is a build that would
            // fail on an operator's machine and not on this one.
            patch = Optional.of(BytecodePatch.read(generated.file()));
            requireVersionMatches(patch.get().metadata());
            requireArtifactMatches(patch.get().metadata(), metadata);
            requireBaselineMatches(patch.get().metadata());
        }

        var finalPatch = patch.orElseThrow(() -> new PatchEngineException(
            "No bytecode patch set for Minecraft " + version
                + "\n  Reason: " + (checkout
                    ? "the patch set was just generated and could not be read back"
                    : "this jar carries none for this version")
                + "\n  Fix: rebuild the jar for " + version));
        var expected = identityFor(finalPatch.metadata(), sourceRevision);

        if (!checkout) {
            // A checkout has already printed it from VeltisPatcher.apply, where
            // the source patches went on; an installation applies the bytecode
            // patch set as its one patching step, so this is its "Applying
            // Patches" moment and the only one.
            VeltisConsole.bootstrap("Applying Patches");
        }
        var applied = applyBytecodePatch(finalPatch, expected);
        long transformation = finalPatch.timing().payloadNanos() + applied.replacementNanos();
        long materialization = applied.copyNanos() + applied.jarWriteNanos();
        long validation = applied.vanillaLoadNanos() + applied.verificationNanos();
        long complete = finalPatch.timing().metadataNanos() + transformation + materialization
            + validation;
        LOG.debug("Bytecode patch complete in {} (patch metadata load {}, bytecode"
                + " transformation {}, runtime JAR materialization {}, verification {})",
            VeltisConsole.formatDuration(complete),
            VeltisConsole.formatDuration(finalPatch.timing().metadataNanos()),
            VeltisConsole.formatDuration(transformation),
            VeltisConsole.formatDuration(materialization),
            VeltisConsole.formatDuration(validation));

        identity = expected;
        if (checkout) {
            // Written last and only here: the marker claims that classes/ and
            // the patch set belong to each other, and neither claim is true
            // until the artifact built from them has been verified.
            expected.recordAsCurrent(workspace);
        }
        var discarded = workspace.discardWorkTree();
        LOG.debug("Runtime ready in {}.{}",
            VeltisConsole.formatDuration(System.nanoTime() - started),
            discarded > 0
                ? " Build work tree removed; the installation now holds only"
                    + " Vanilla/ and Veltis/."
                : "");
        return true;
    }

    /**
     * The bytecode patch set this runtime applies, or empty in a checkout that
     * has not generated one yet.
     *
     * <p>An installation's patch set is always present — it ships inside the jar
     * — and an absent one there is a broken jar, which
     * {@link BytecodePatch#readPackaged} reports as exactly that rather than
     * quietly becoming "no patch set". A checkout's absence is normal on a first
     * build and only means there is nothing to validate against yet.
     */
    private Optional<BytecodePatch> loadBytecodePatch() {
        if (packagedPatch != null) {
            // The jar's own patch set is not derived at runtime: if it cannot be
            // read, the jar is broken and there is nothing to rebuild it from.
            return Optional.of(BytecodePatch.readPackaged(packagedPatch, version.toString()));
        }
        var file = workspace.bytecodePatchFile();
        if (Files.isRegularFile(file)) {
            try {
                return Optional.of(BytecodePatch.read(file));
            } catch (PatchEngineException e) {
                // A workspace patch set is derived: this run regenerates it from
                // the sources when it does not validate, so an unreadable one —
                // an older format after an engine upgrade, a write interrupted
                // by a killed build — means "rebuild", not "refuse". Nothing
                // from this file is ever applied: at most it was consulted to
                // decide whether the existing runtime could be reused, and an
                // empty optional sends that decision down the rebuilding path.
                // The freshly generated file is read back and validated before
                // anything is applied, so the fail-closed chain is unchanged.
                LOG.warn("Discarding the bytecode patch set at {}: it cannot be read"
                    + "\n  Reason: {}", file, MojangMetadata.rootMessage(e));
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** §9: a patch set for another Minecraft version is refused, not adapted. */
    private void requireVersionMatches(BytecodePatch.Metadata patch) {
        if (version.toString().equals(patch.minecraftVersion())) {
            return;
        }
        throw new PatchEngineException(
            "Bytecode patch set targets Minecraft " + patch.minecraftVersion()
                + " but this runtime is for " + version
                + "\n  Expected Minecraft version: " + version
                + "\n  Actual Minecraft version:   " + patch.minecraftVersion()
                + "\n  Reason: patch payloads are whole classes for one specific version, and"
                + " applying them to another would produce a server that starts and then"
                + " fails somewhere unrelated"
                + "\n  Fix: run this jar with a version it was built for, or rebuild it for "
                + version);
    }

    /** §10: the artifact Mojang resolves has to be the one the patch was cut from. */
    private void requireArtifactMatches(BytecodePatch.Metadata patch,
                                        MojangMetadata.VersionMetadata metadata) {
        var resolved = metadata.serverArtifact().sha1().toLowerCase(Locale.ROOT);
        if (patch.serverSha1().equalsIgnoreCase(resolved)) {
            return;
        }
        throw new PatchEngineException(
            "Mojang's server artifact is not the one this patch set was cut from"
                + "\n  Minecraft version: " + metadata.id()
                + "\n  Expected SHA-1: " + patch.serverSha1()
                + "\n  Actual SHA-1:   " + resolved
                + "\n  Reason: Mojang may have re-published the artifact under the same version"
                + " id; applying a patch cut from different bytecode would produce classes"
                + " that do not match the jar they claim to come from"
                + "\n  Fix: rebuild this jar against the artifact Mojang now publishes");
    }

    /** §10: the baseline the payloads were diffed against is checked by hash. */
    private void requireBaselineMatches(BytecodePatch.Metadata patch) {
        var classes = workspace.vanillaClassesJar();
        if (!Files.isRegularFile(classes)) {
            throw new PatchEngineException(
                "The verified Minecraft classes jar is missing: " + classes
                    + "\n  Reason: the download step reported success but produced no classes"
                    + " jar, and there is nothing to apply a patch set to");
        }
        String actual;
        try {
            actual = MojangMetadata.sha1(classes).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to hash the verified Minecraft classes jar " + classes
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        if (patch.classesSha1().equalsIgnoreCase(actual)) {
            return;
        }
        throw new PatchEngineException(
                "The Minecraft classes jar is not the baseline this patch set was cut from"
                    + "\n  Minecraft version: " + patch.minecraftVersion()
                    + "\n  Expected SHA-1: " + patch.classesSha1()
                    + "\n  Actual SHA-1:   " + actual
                    + "\n  Reason: the patch records the hash of the exact bytes it diffs against;"
                    + " anything else would be patched blind"
                    + "\n  Fix: delete " + classes + " and start again to re-extract it");
    }

    RuntimeIdentity identityFor(BytecodePatch.Metadata patch, String sourceRevision) {
        return new RuntimeIdentity(
            version.toString(),
            patch.serverSha1().toLowerCase(Locale.ROOT),
            sourceRevision,
            patch.fingerprint(),
            classFileRelease,
            AccessWidener.FORMAT,
            BytecodePatch.FORMAT);
    }

    private static void report(String what, long since) {
        LOG.debug("{} in {}", what, VeltisConsole.formatDuration(System.nanoTime() - since));
    }

    /**
     * Asks whether the artifact that is already on disk is the one this build
     * would produce, without contacting anything.
     *
     * <p>Five questions, and all five have to be answered with evidence rather
     * than with presence: the patch set must describe this exact Minecraft
     * artifact; the jar must record an identity that matches the patch
     * fingerprint, the source patch revision, the compiler release, the widener
     * format and the patch format this build uses; the vanilla artifact beside it
     * must hash to the SHA-1 that identity names; Mojang's libraries must be
     * there; and every class the jar claims to guard must actually be in it. A
     * jar that answers none of them is not a slower start, it is a rebuild.
     *
     * @param patch          the patch set this build would apply, or {@code null}
     *                       in a checkout that has not generated one yet
     * @param sourceRevision the development source patch revision, or
     *                       {@link RuntimeIdentity#NO_SOURCE_PATCHES}
     */
    Optional<RuntimeIdentity> validateArtifact(BytecodePatch patch, String sourceRevision) {
        if (patch == null) {
            LOG.debug("There is no bytecode patch set to validate an artifact against");
            return Optional.empty();
        }
        var jar = workspace.veltisServerJar();
        var recorded = RuntimeIdentity.readFromJar(jar);
        if (recorded.isEmpty()) {
            LOG.debug("{} carries no usable runtime record", jar);
            return Optional.empty();
        }
        var vanilla = workspace.vanillaServerJar();
        if (!Files.isRegularFile(vanilla)) {
            LOG.debug("{} is missing, so the runtime has to be rebuilt", vanilla);
            return Optional.empty();
        }
        String actualSha1;
        try {
            actualSha1 = MojangMetadata.sha1(vanilla).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            LOG.debug("{} could not be hashed, so it cannot be trusted: {}",
                vanilla, MojangMetadata.rootMessage(e));
            return Optional.empty();
        }
        if (!actualSha1.equalsIgnoreCase(patch.metadata().serverSha1())) {
            LOG.debug("{} is not the artifact this patch set was cut from"
                    + " (patch expects {}, it is {}); rebuilding it",
                vanilla.getFileName(), shorten(patch.metadata().serverSha1()),
                shorten(actualSha1));
            return Optional.empty();
        }
        var expected = identityFor(patch.metadata(), sourceRevision);
        if (!expected.equals(recorded.get())) {
            LOG.debug("{} describes a different runtime (patch fingerprint {}, Minecraft"
                    + " {}, artifact {}); rebuilding it",
                jar.getFileName(), shorten(recorded.get().patchFingerprint()),
                recorded.get().minecraftVersion(), shorten(recorded.get().serverSha1()));
            return Optional.empty();
        }
        if (MinecraftDownloader.libraryJars(workspace).isEmpty()) {
            LOG.debug("No Minecraft libraries beside {}, so the runtime has to be rebuilt",
                vanilla);
            return Optional.empty();
        }
        var guard = readGuard(jar);
        if (guard.isEmpty()) {
            LOG.debug("{} records no guarded classes, so it cannot be trusted",
                jar.getFileName());
            return Optional.empty();
        }
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entry : guard.keySet()) {
                if (zip.getEntry(entry) == null) {
                    LOG.debug("{} is missing {}; rebuilding it",
                        jar.getFileName(), entry);
                    return Optional.empty();
                }
            }
        } catch (IOException e) {
            LOG.debug("{} is not a readable jar: {}", jar.getFileName(),
                MojangMetadata.rootMessage(e));
            return Optional.empty();
        }
        if (!workspace.isServerInstall()
                && !RuntimeIdentity.isCurrent(workspace, expected)) {
            // A checkout also keeps classes/ and the marker, and both are read
            // by the IDE path. Their absence means the checkout is half-built
            // even though the jar is fine.
            LOG.debug("The compiled classes or their marker are missing from {}",
                workspace.root());
            return Optional.empty();
        }
        return Optional.of(recorded.get());
    }

    private static String shorten(String value) {
        return value == null ? "<none>"
            : value.length() <= 12 ? value : value.substring(0, 12) + "...";
    }

    // ------------------------------------------------------------------
    // Individual phases
    // ------------------------------------------------------------------

    private void fetchArtifacts(MojangMetadata.VersionMetadata metadata) {
        var downloader = new MinecraftDownloader();
        var server = downloader.downloadServer(metadata, workspace);
        if (server.outcome() != MinecraftDownloader.Outcome.CACHED) {
            VeltisConsole.bootstrap(
                "Downloading vanilla " + metadata.id() + " server jar");
            LOG.debug("Server jar URL: {}", metadata.serverArtifact().url());
        }
        int fetched = downloader.downloadLibraries(metadata, workspace);
        LOG.debug("Server artifact {} ({} bytes); {} librar{} present, {} fetched",
            server.outcome() == MinecraftDownloader.Outcome.CACHED
                ? "already verified" : "downloaded and verified",
            server.bytes(),
            MinecraftDownloader.libraryJars(workspace).size(),
            MinecraftDownloader.libraryJars(workspace).size() == 1 ? "y" : "ies",
            fetched);
    }

    private void widenAccess(MojangMetadata.VersionMetadata metadata) {
        var classes = workspace.vanillaClassesJar();
        if (!Files.isRegularFile(classes)) {
            throw new PatchEngineException(
                "The verified Minecraft classes jar is missing: " + classes
                    + "\n  Reason: the download step reported success but produced no classes jar");
        }
        String sha1;
        try {
            sha1 = MojangMetadata.sha1(classes);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to hash the verified Minecraft classes jar " + classes, e);
        }
        AccessWidener.widen(classes, workspace.widenedServerJar(), sha1);
    }

    private void decompile(MojangMetadata.VersionMetadata metadata) {
        var outcome = new MinecraftDecompiler().decompile(workspace, metadata);
        if (outcome == MinecraftDecompiler.Outcome.DECOMPILED) {
            reportUnsupportedFiles(workspace);
        }
    }

    /**
     * Reports how much of the decompile is still not valid Java.
     *
     * <p>Not a gate — the decompile has to produce the whole tree either way,
     * because {@code source/} is the baseline every patch applies to and a
     * partial tree would make patching depend on which files happen to fail. It
     * is reported because the number is the real limit on the patch set: a target
     * inside it cannot be patched, and finding that out from a javac error inside
     * Vineflower's {@code <unrepresentable>} placeholder is a bad afternoon.
     */
    private void reportUnsupportedFiles(VeltisWorkspace workspace) {
        int unsupported = 0;
        var source = workspace.sourceDirectory();
        try (var walk = Files.walk(source)) {
            for (var path : (Iterable<Path>) walk.filter(VeltisRuntime::isJava)::iterator) {
                try {
                    if (Files.readString(path, StandardCharsets.UTF_8).contains("<unrepresentable>")) {
                        unsupported++;
                    }
                } catch (IOException e) {
                    LOG.debug("Could not read {} while counting placeholders", path, e);
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not scan for decompiler placeholders", e);
            return;
        }
        int total = 0;
        try (var walk = Files.walk(source)) {
            total = (int) walk.filter(VeltisRuntime::isJava).count();
        } catch (IOException e) {
            LOG.debug("Could not count decompiled sources", e);
        }
        LOG.debug("{} of {} decompiled files still contain a decompiler placeholder;"
                + " those cannot be patch targets yet", unsupported, total);
    }

    /**
     * Mirrors the pristine decompile into {@code patched/} and applies the patch
     * set to it, timing the two separately.
     *
     * <p>The mirror is not optional and not the patcher's job. {@code patched/} is
     * a derived tree, but a derived tree is only reproducible if it is rebuilt
     * from its baseline every time: applying a patch to a tree that already
     * carries a previous run's patches does not fail cleanly, it fails with
     * "context mismatch" on a file that is perfectly correct — the context is
     * there, with the previous run's edit sitting between two of its lines. A
     * half-finished run, or a second start after a failed one, is enough to get
     * there.
     *
     * <p>They are timed separately because they were not, and the single number
     * that resulted made every measurement of "how long patching takes" wrong by
     * two orders of magnitude. A checkout mirrors the whole decompile, because
     * its {@code patched/} is the IDE source root; a server installation mirrors
     * only the files the patch set names, because it has no source root and reads
     * nothing else.
     *
     * <p>Hand-editing {@code patched/} in an IDE stays the supported way to author
     * a change; {@code rebuildVeltisPatches} is what turns those edits into patch
     * files. It is re-running the runtime that has to throw them away.
     */
    void applyPatches(List<VeltisPatch> patches) {
        long mirrorStarted = System.nanoTime();
        int mirrored;
        if (workspace.isServerInstall()) {
            var targets = new ArrayList<String>();
            for (var patch : patches) {
                targets.addAll(patch.targets());
            }
            mirrored = VeltisPatcher.mirrorPristineTargets(workspace.sourceDirectory(),
                workspace.patchedDirectory(), targets);
            LOG.debug("Mirrored {} patch target{} into {} in {}"
                    + " (a server build reads only the files the patch set addresses)",
                mirrored, mirrored == 1 ? "" : "s", workspace.patchedDirectory(),
                VeltisConsole.formatDuration(System.nanoTime() - mirrorStarted));
        } else {
            mirrored = VeltisPatcher.mirrorPristineSource(
                workspace.sourceDirectory(), workspace.patchedDirectory());
            LOG.debug("Mirrored {} pristine source file{} into {} in {}",
                mirrored, mirrored == 1 ? "" : "s", workspace.patchedDirectory(),
                VeltisConsole.formatDuration(System.nanoTime() - mirrorStarted));
        }

        // The apply phase reports one line on success, from VeltisPatcher
        // itself: "Applying Patches", as raw bootstrap output. No second
        // summary is printed here.
        new VeltisPatcher(workers, version.toString()).apply(workspace, patches);
        copyResourceDelta(patches);
    }

    /**
     * Copies the {@code data} and {@code modules} targets into
     * {@code resources/}.
     *
     * <p>Only the delta, not a copy of everything the decompile produced. The
     * runtime jar already carries Minecraft's own resources, so shipping them
     * twice would be a second set of identical bytes and would make a stale
     * resource win over a patched one purely on classpath order.
     */
    private void copyResourceDelta(List<VeltisPatch> patches) {
        var patched = workspace.patchedDirectory();
        var resources = workspace.resourcesDirectory();
        VeltisWorkspace.deleteTree(resources);
        int copied = 0;
        for (var patch : patches) {
            if (patch.category() == PatchCategory.CODE) {
                continue;
            }
            for (var target : patch.targets()) {
                var from = patched.resolve(target);
                if (!Files.isRegularFile(from)) {
                    continue;
                }
                var to = resources.resolve(target);
                try {
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                } catch (IOException e) {
                    throw new PatchEngineException(
                        "Failed to copy the patched resource " + target
                            + "\n  From: " + from
                            + "\n  To: " + to
                            + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
                }
            }
        }
        LOG.debug("Collected {} patched resource{} into {}.",
            copied, copied == 1 ? "" : "s", resources);
    }

    private void compileSources() {
        var targets = patchTargets(PatchCategory.CODE, ".java");
        if (targets.isEmpty()) {
            throw new PatchEngineException(
                "The patch set has no code targets, so there is nothing to compile"
                    + "\n  Reason: a VeltisMC build must change at least one Minecraft class;"
                    + " a server that is only vanilla has no reason to exist");
        }
        var sources = new ArrayList<Path>(targets.size());
        for (var target : targets) {
            sources.add(workspace.patchedDirectory().resolve(target));
        }
        new PatchedSourceCompiler(classFileRelease).compile(workspace, sources);
    }

    /**
     * Proves the compiled output really is the patched one, before it is turned
     * into a patch set.
     *
     * <p>Two checks, because they fail for different reasons. "The class file is
     * missing" means compilation did not do what it reported. "The class file is
     * identical to vanilla" means a patch silently matched nothing and the
     * server would run vanilla while believing otherwise — the failure this
     * pipeline exists to make impossible, and one no ordering rule can catch.
     *
     * <p>The baseline is the <em>unwidened</em> classes jar, not the widened
     * one. The widened jar differs from vanilla on about seven thousand entries
     * purely because of access flags, so comparing against it would let a class
     * whose only change is widening count as a real patch — and then ship a
     * patch set whose payloads say nothing the runtime could not already do.
     * Comparing against vanilla says the only thing that matters: did this
     * source patch change this class at all.
     *
     * <p>Package-private rather than private because this is the only check
     * that can catch a no-op patch: a class identical to vanilla never enters
     * the bytecode patch set at all, so nothing in the packaged jar could
     * report it.
     */
    void verifyCompiledOutput(String sourceRevision) {
        var classes = workspace.classesDirectory();
        int checked = 0;
        for (var target : patchTargets(PatchCategory.CODE, ".java")) {
            var classFile = classEntryFor(target);
            var compiled = classes.resolve(classFile);
            if (!Files.isRegularFile(compiled)) {
                throw new PatchEngineException(
                    "The patch set targets " + target
                        + " but compilation produced no " + classFile
                        + "\n  Reason: javac reported success without writing the class;"
                        + " the runtime is not usable and no patch set can be cut from it");
            }
            var baseline = readClassFromJar(workspace.vanillaClassesJar(), classFile);
            if (baseline != null && Arrays.equals(bytesOf(compiled), baseline)) {
                throw new PatchEngineException(
                    "Patched class " + classFile + " is byte-for-byte identical to"
                        + " the vanilla class it replaces"
                        + "\n  Target: " + target
                        + "\n  Source patch revision: " + sourceRevision
                        + "\n  Reason: at least one patch in this set had no effect on this file,"
                        + " so the server would run vanilla while reporting a successful patch");
            }
            checked++;
        }
        LOG.debug("{} patched class{} differ from vanilla.",
            checked, checked == 1 ? "" : "es");
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /**
     * How long each part of writing the artifact took, reported separately
     * because a single blended number cannot answer "is it the copying or the
     * patching that is slow".
     *
     * <p>Package-private rather than private for the same reason
     * {@link #validateArtifact} is: the tests apply a patch set through this
     * method rather than through a fixture that imitates it.
     *
     * @param vanillaLoadNanos  reading and hashing the baseline, parsing its
     *                          structure and opening it, which is the
     *                          version-safety check §10 requires before
     *                          anything is written
     * @param replacementNanos  reading the entries the patch names, checking
     *                          their original hashes and substituting payloads
     * @param copyNanos         writing the unchanged entries' bytes exactly as
     *                          the baseline holds them, which is the copy that
     *                          replaced a recompression of the whole jar
     * @param jarWriteNanos     writing the jar's structure and the entries that
     *                          change: local headers, deflated payloads, the
     *                          central directory and the end record
     * @param verificationNanos re-reading the finished jar and comparing it with
     *                          the patch set
     * @param patchedEntries    how many baseline entries were replaced or removed
     *                          by the index, plus the ones the baseline lacks
     * @param copiedEntries     how many baseline entries were carried over
     *                          untouched
     */
    record Applied(long vanillaLoadNanos, long replacementNanos, long copyNanos,
                   long jarWriteNanos, long verificationNanos, int patchedEntries,
                   int copiedEntries) {
        /**
         * The part §25 calls "patch application": replacement plus writing.
         *
         * <p>Both halves of writing are here on purpose. The unchanged copying
         * and the ZIP writing are one operation seen from two sides — this
         * method answers "what did applying the patch set cost", the stage log
         * answers "which side of it".
         */
        public long applicationNanos() {
            return replacementNanos + copyNanos + jarWriteNanos;
        }
    }

    /**
     * Writes the runtime artifact: Mojang's classes jar with every entry the
     * patch set names replaced or removed, the record of what produced it
     * written last.
     *
     * <p>Only the entries the index names are touched. Minecraft 26.3 ships
     * 17655 entries and this patch set changes nineteen of them, so the work is
     * a copy plus nineteen substitutions rather than a rebuild — no access
     * widening, no decompilation, no compilation, and no rewriting of a class
     * the patch has nothing to say about. That is what the format buys: the
     * applier copies bytes and checks hashes, and never interprets a class file
     * at all.
     *
     * <p>The result is a complete server jar rather than a delta, and that is a
     * deliberate choice about what "runs from the artifact" means. A delta jar
     * would need the vanilla jar beside it on the classpath, which reintroduces
     * exactly the arrangement the guard exists to police — two jars holding the
     * same class names, with the outcome decided by order. Here there is one jar
     * holding each class, so vanilla cannot win because it is not there.
     *
     * <p>Staging, verify, then an atomic move. A jar at
     * {@code Veltis/<version>/veltis-server.jar} is therefore either the
     * complete, checked artifact or absent, never a half-written one.
     *
     * @param patch     the patch set, already format-, version- and
     *                  artifact-validated by {@link #prepare()}
     * @param expected  the identity the artifact must record, already built from
     *                  this patch set
     * @return how the work was split, so the timings can be reported apart
     * @throws PatchEngineException when the baseline is not the one the index
     *                              describes, when a payload does not hash to the
     *                              result it claims, or when the finished jar
     *                              does not contain what the index says it does
     */
    Applied applyBytecodePatch(BytecodePatch patch, RuntimeIdentity expected) {
        var baselineJar = workspace.vanillaClassesJar();
        if (!Files.isRegularFile(baselineJar)) {
            throw new PatchEngineException(
                "Cannot apply the bytecode patch set: the verified Minecraft classes"
                    + " jar is missing: " + baselineJar
                    + "\n  Reason: there is nothing to apply it to");
        }
        var out = workspace.veltisServerJar();
        var staging = out.resolveSibling(out.getFileName() + ".writing");
        var guard = new LinkedHashMap<String, GuardedClass>();
        // Index order, which is sorted by name, so the entries the baseline
        // lacks are written in an order that does not depend on hash-map
        // iteration and two builds of one patch set produce one jar.
        var pending = new LinkedHashMap<String, BytecodePatch.IndexEntry>();
        for (var entry : patch.entries()) {
            if (JarFile.MANIFEST_NAME.equals(entry.name())
                    || RuntimeIdentity.JAR_IDENTITY_ENTRY.equals(entry.name())
                    || RuntimeIdentity.JAR_GUARD_ENTRY.equals(entry.name())) {
                throw new PatchEngineException(
                    "The bytecode patch set targets " + entry.name()
                        + "\n  Reason: the applier generates that entry from the identity of the"
                        + " artifact it is writing, so a patch for it would be overwritten by"
                        + " the very thing that applies it");
            }
            pending.put(entry.name(), entry);
        }

        long vanillaLoad = System.nanoTime();
        // The baseline is read once, here, and the same bytes serve all three
        // purposes they used to be re-read for: the SHA-1 that proves this is
        // Mojang's jar, the central directory the writer copies entries out
        // of, and the reference the finished artifact is verified against.
        byte[] baselineBytes;
        try {
            baselineBytes = Files.readAllBytes(baselineJar);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read the verified Minecraft classes jar " + baselineJar
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        var actualClassesSha1 = sha1Hex(baselineBytes).toLowerCase(Locale.ROOT);
        if (!actualClassesSha1.equalsIgnoreCase(patch.metadata().classesSha1())) {
            throw new PatchEngineException(
                "The Minecraft classes jar is not the baseline this patch was cut from"
                    + "\n  Expected SHA-1: " + patch.metadata().classesSha1()
                    + "\n  Actual SHA-1:   " + actualClassesSha1
                    + "\n  Reason: every original hash in the index is relative to these bytes,"
                    + " so a different baseline would turn each of those checks into a guess"
                    + "\n  Fix: delete " + baselineJar + " and start again to re-extract it");
        }
        // Structure is parsed before anything is written: an entry the writer
        // cannot copy faithfully is a bug or a tampered file, and finding that
        // out mid-copy would be later and less specific than finding it here.
        var baselineIndex = RawZipWriter.readIndex(baselineBytes, "The Minecraft classes jar");
        ZipFile baseline = null;
        try {
            baseline = new ZipFile(baselineJar.toFile());
        } catch (IOException e) {
            throw new PatchEngineException(
                "Cannot open the verified Minecraft classes jar " + baselineJar
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        vanillaLoad = System.nanoTime() - vanillaLoad;

        long replacement = 0L;
        long deltaNanos = 0L;
        long copyNanos = 0L;
        long writeNanos = 0L;
        int patchedEntries = 0;
        int copiedEntries = 0;

        try {
            Files.createDirectories(out.getParent());
            Files.deleteIfExists(staging);
            // The writer copies the compressed bytes of every entry the index
            // does not name and deflates only the ones it does. The previous
            // path re-deflated all ~17600 unchanged entries to produce
            // byte-different-but-equivalent output, and that recompression was
            // essentially the whole cost of applying the patch set. This path
            // writes the unchanged entries at copy speed; the verification
            // afterwards compares every copied region back to the baseline it
            // came from, so speed is bought with proof rather than trust.
            try (var writer = new RawZipWriter(staging, baselineBytes, baselineIndex)) {
                writer.put(JarFile.MANIFEST_NAME, manifestBytes(expected));

                for (var entries = baseline.entries(); entries.hasMoreElements(); ) {
                    var source = entries.nextElement();
                    if (source.isDirectory()) {
                        continue;
                    }
                    var name = source.getName();
                    var indexEntry = pending.remove(name);
                    if (JarFile.MANIFEST_NAME.equals(name)) {
                        continue;
                    }
                    if (indexEntry == null) {
                        // Carried over untouched: the patch set has nothing to
                        // say about this entry, so its bytes are copied as
                        // they stand — no inflate, no deflate. This is the
                        // overwhelming majority of the jar.
                        writer.carry(name);
                        copiedEntries++;
                        continue;
                    }
                    // Replaced, removed, or spliced from a class delta — the
                    // reason a patch set exists.
                    long change = System.nanoTime();
                    byte[] original;
                    try (var in = baseline.getInputStream(source)) {
                        original = in.readAllBytes();
                    }
                    var originalSha = BytecodePatch.sha256Hex(original);
                    if (!originalSha.equalsIgnoreCase(indexEntry.originalSha256())) {
                        throw new PatchEngineException(
                            "The Minecraft classes jar does not match this patch set at "
                                + name
                                + "\n  Expected SHA-256: " + indexEntry.originalSha256()
                                + "\n  Actual SHA-256:   " + originalSha
                                + "\n  Reason: the index records what the baseline held when the"
                                + " patch was cut, so a different hash means these are not the"
                                + " classes the patch was written against"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    if (indexEntry.kind() == BytecodePatch.Kind.DELETE) {
                        // A removal: the entry simply is not written, and the
                        // index rather than an applier rule is what says so.
                        replacement += System.nanoTime() - change;
                        patchedEntries++;
                        continue;
                    }
                    var payload = patch.payload(name);
                    if (payload == null) {
                        throw new PatchEngineException(
                            "The bytecode patch set has no payload for " + name
                                + "\n  Reason: the index promises one, so the patch set is"
                                + " incomplete and applying it would produce a jar the index"
                                + " does not describe");
                    }
                    var payloadSha = BytecodePatch.sha256Hex(payload);
                    if (!payloadSha.equalsIgnoreCase(indexEntry.payloadSha256())) {
                        throw new PatchEngineException(
                            "Bytecode patch payload for " + name + " is corrupt"
                                + "\n  Expected SHA-256: " + indexEntry.payloadSha256()
                                + "\n  Actual SHA-256:   " + payloadSha
                                + "\n  Reason: the index records what every stored payload hashes"
                                + " to, so a mismatch is a truncated or mixed-up patch set"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    byte[] served;
                    String resultSha;
                    if (indexEntry.kind() == BytecodePatch.Kind.CLASS) {
                        // A class delta: verified vanilla bytes in, verified
                        // merged class out — the delta re-checks the vanilla
                        // hash, its own result hash and its fingerprint against
                        // the values it carries, and the cross-check below ties
                        // those to this index line.
                        long deltaStarted = System.nanoTime();
                        var delta = ClassDelta.apply(original, payload);
                        deltaNanos += System.nanoTime() - deltaStarted;
                        crossCheckDelta(indexEntry, delta.metadata());
                        served = delta.bytes();
                        resultSha = BytecodePatch.sha256Hex(served);
                    } else {
                        served = payload;
                        resultSha = payloadSha;
                    }
                    if (!resultSha.equalsIgnoreCase(indexEntry.resultSha256())) {
                        throw new PatchEngineException(
                            "The bytecode patch set does not produce what it promises"
                                + " for " + name
                                + "\n  Expected SHA-256: " + indexEntry.resultSha256()
                                + "\n  Actual SHA-256:   " + resultSha
                                + "\n  Reason: the index records what every entry must hash to"
                                + " after the patch is applied, so a mismatch means the result"
                                + " is not the result that was verified when this patch set was"
                                + " cut"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    replacement += System.nanoTime() - change;
                    writer.put(name, served);
                    if (name.endsWith(".class")) {
                        guard.put(name, new GuardedClass(resultSha, originalSha));
                    }
                    patchedEntries++;
                }

                // Everything still pending is an entry the baseline does not
                // have. The index's claim about the baseline is checked in both
                // directions, because "vanilla does not have this file" is as
                // much a claim about vanilla as "vanilla has it and it hashes
                // like this".
                for (var indexEntry : pending.values()) {
                    long change = System.nanoTime();
                    if (!BytecodePatch.ABSENT.equals(indexEntry.originalSha256())) {
                        throw new PatchEngineException(
                            "The bytecode patch set expects " + indexEntry.name()
                                + " in the Minecraft classes jar, but it has no such entry"
                                + "\n  Expected original SHA-256: " + indexEntry.originalSha256()
                                + "\n  Reason: the index says this entry existed when the patch"
                                + " was cut and it does not exist now, so the patch set and the"
                                + " baseline disagree about the same jar"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    if (indexEntry.kind() == BytecodePatch.Kind.DELETE) {
                        throw new PatchEngineException(
                            "The bytecode patch set removes " + indexEntry.name()
                                + " but the Minecraft classes jar never had it"
                                + "\n  Reason: a removal with nothing to remove means the patch"
                                + " set was cut from a different jar than this one"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    if (indexEntry.kind() == BytecodePatch.Kind.CLASS) {
                        // A class delta can only be spliced onto a baseline
                        // class, and this entry has none — so the index row and
                        // the payload cannot have come from one generator.
                        throw new PatchEngineException(
                            "The bytecode patch set carries a class delta for "
                                + indexEntry.name()
                                + " but the Minecraft classes jar never had it"
                                + "\n  Reason: a delta splices onto verified vanilla bytes, so"
                                + " this entry names a baseline that does not exist"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    var payload = patch.payload(indexEntry.name());
                    if (payload == null) {
                        throw new PatchEngineException(
                            "The bytecode patch set has no payload for "
                                + indexEntry.name());
                    }
                    var payloadSha = BytecodePatch.sha256Hex(payload);
                    if (!payloadSha.equalsIgnoreCase(indexEntry.payloadSha256())) {
                        throw new PatchEngineException(
                            "Bytecode patch payload for " + indexEntry.name()
                                + " is corrupt"
                                + "\n  Expected SHA-256: " + indexEntry.payloadSha256()
                                + "\n  Actual SHA-256:   " + payloadSha
                                + "\n  Reason: the index records what every stored payload hashes"
                                + " to, so a mismatch is a truncated or mixed-up patch set"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    if (!payloadSha.equalsIgnoreCase(indexEntry.resultSha256())) {
                        throw new PatchEngineException(
                            "The bytecode patch set does not produce what it promises"
                                + " for " + indexEntry.name()
                                + "\n  Expected SHA-256: " + indexEntry.resultSha256()
                                + "\n  Actual SHA-256:   " + payloadSha
                                + "\n  Reason: an entry the baseline lacks is written as-is, so"
                                + " its payload and its promised result are the same bytes and"
                                + " must hash alike"
                                + "\n  Nothing has been written; the artifact is unchanged.");
                    }
                    replacement += System.nanoTime() - change;
                    writer.put(indexEntry.name(), payload);
                    if (indexEntry.name().endsWith(".class")) {
                        guard.put(indexEntry.name(),
                            new GuardedClass(payloadSha, BytecodePatch.ABSENT));
                    }
                    patchedEntries++;
                }

                writer.put(RuntimeIdentity.JAR_IDENTITY_ENTRY,
                    expected.renderArtifact(veltisVersion()).getBytes(StandardCharsets.UTF_8));
                writer.put(RuntimeIdentity.JAR_GUARD_ENTRY, renderGuard(guard));
                writer.finish();
                copyNanos = writer.copyNanos();
                writeNanos = writer.writeNanos();
            }

            long verification = System.nanoTime();
            verifyAppliedArtifact(staging, patch, expected, baselineBytes, baselineIndex);
            verification = System.nanoTime() - verification;

            try {
                Files.move(staging, out, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(staging, out, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.debug("Patch stages: baseline verification {}, patch payload loading {},"
                    + " changed-class replacement {} (of which class delta splicing {}),"
                    + " unchanged copying {}, ZIP writing {}, final verification {}"
                    + " (total {}; {} entr{}, {} copied unchanged)",
                VeltisConsole.formatDuration(vanillaLoad),
                VeltisConsole.formatDuration(patch.timing().payloadNanos()),
                VeltisConsole.formatDuration(replacement),
                VeltisConsole.formatDuration(deltaNanos),
                VeltisConsole.formatDuration(copyNanos),
                VeltisConsole.formatDuration(writeNanos),
                VeltisConsole.formatDuration(verification),
                VeltisConsole.formatDuration(
                    vanillaLoad + patch.timing().payloadNanos() + replacement + copyNanos
                        + writeNanos + verification),
                patchedEntries, patchedEntries == 1 ? "y" : "ies", copiedEntries);
            LOG.debug("Verified the packaged runtime {} ({} bytes)",
                out.getFileName(), Files.size(out));
            return new Applied(vanillaLoad, replacement, copyNanos, writeNanos, verification,
                patchedEntries, copiedEntries);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to create " + out + "\n  Reason: "
                + MojangMetadata.rootMessage(e), e);
        } finally {
            if (baseline != null) {
                try {
                    baseline.close();
                } catch (IOException ignored) {
                    // Already read to completion; closing cannot change any of
                    // the bytes that were hashed and compared.
                }
            }
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // A staging file left behind is deleted by the next run before
                // it writes anything, so it can never be mistaken for a jar.
            }
        }
    }

    /**
     * Ties a class delta's own metadata to the index row that carried it.
     *
     * <p>Both values were written by the same generator in the same run, and
     * {@link ClassDelta#apply} has already checked the delta against the vanilla
     * bytes it spliced onto. What it cannot see from inside is the index: a
     * delta whose recorded target, baseline hash or result hash disagrees with
     * its index line means the payload and the index come from different builds,
     * or one of them was edited after the fingerprint was taken. Either way the
     * pair is refused rather than trusted — one of the two is wrong, and the
     * applier has no basis for guessing which.
     *
     * @param entry the index line the payload arrived with
     * @param meta  the metadata the payload carries inside itself
     */
    private static void crossCheckDelta(BytecodePatch.IndexEntry entry, ClassDelta.Metadata meta) {
        var mismatches = new ArrayList<String>();
        if (!entry.name().equals(meta.target() + ".class")) {
            mismatches.add("  target:         index says " + entry.name() + ", delta says "
                + meta.target() + ".class");
        }
        if (!meta.vanillaSha256().equalsIgnoreCase(entry.originalSha256())) {
            mismatches.add("  vanilla SHA-256: index says " + entry.originalSha256()
                + ", delta says " + meta.vanillaSha256());
        }
        if (!meta.patchedSha256().equalsIgnoreCase(entry.resultSha256())) {
            mismatches.add("  result SHA-256:  index says " + entry.resultSha256()
                + ", delta says " + meta.patchedSha256());
        }
        if (!mismatches.isEmpty()) {
            throw new PatchEngineException(
                "The class delta for " + entry.name()
                    + " disagrees with its index line"
                    + "\n" + String.join("\n", mismatches)
                    + "\n  Reason: both were written by one generator in one run, so a"
                    + " disagreement means the payload and the index come from different"
                    + " patch sets or one of them was edited afterwards"
                    + "\n  Nothing has been written; the artifact is unchanged.");
        }
    }

    /**
     * Reads the jar that was just written and checks it against the patch set it
     * was written from.
     *
     * <p>This is the byte-for-byte half of the guard, and it can only be done
     * here: once the work tree is gone there is nothing left to compare the jar
     * against, so the comparison happens while both sides exist and its result
     * is recorded for every later launch to trust. The patch set survives that
     * work tree, so a server installation gets exactly the same check a build
     * does — which is the point of having one applier rather than two.
     *
     * <p>Every entry the index names is re-read from the finished jar and
     * hashed against its recorded result, and every entry the index removes is
     * confirmed absent. Checking only what was written would prove the write
     * succeeded; checking what is actually in the jar proves the jar is the one
     * the index describes.
     *
     * <p>The jar's structure is verified the same way. The file is re-parsed
     * from disk with the same fail-closed reader the writer used, the set of
     * names in it must be exactly the baseline plus what the index changes plus
     * what the applier generates — nothing missing, nothing extra — and every
     * unchanged entry's metadata and bytes are compared back against the
     * baseline region it was copied from. The copy path is fast because it
     * never interprets what it copies; this is where that speed is paid for.
     *
     * @param jar          the staging file just written
     * @param patch        the patch set it was written from
     * @param expected     the identity the jar must record
     * @param baseline     the baseline jar's bytes, still in memory
     * @param baselineIndex the baseline as the writer's reader parsed it
     * @throws PatchEngineException when the jar is not exactly what the patch
     *                              set and the baseline describe
     */
    private void verifyAppliedArtifact(Path jar, BytecodePatch patch, RuntimeIdentity expected,
            byte[] baseline, Map<String, RawZipWriter.Entry> baselineIndex) {
        try (var zip = new ZipFile(jar.toFile())) {
            var identity = zip.getEntry(RuntimeIdentity.JAR_IDENTITY_ENTRY);
            if (identity == null) {
                throw new PatchEngineException(
                    jar + " does not record what produced it");
            }
            String recorded;
            try (var in = zip.getInputStream(identity)) {
                recorded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!recorded.equals(expected.renderArtifact(veltisVersion()))) {
                throw new PatchEngineException(
                    jar + " records a different identity than the build"
                        + "\n  Expected: " + expected.renderArtifact(veltisVersion())
                        + "\n  Recorded: " + recorded);
            }
            if (zip.getEntry(JarFile.MANIFEST_NAME) == null) {
                throw new PatchEngineException(
                    jar + " has no " + JarFile.MANIFEST_NAME);
            }
            for (var entry : patch.entries()) {
                var zipEntry = zip.getEntry(entry.name());
                if (entry.kind() == BytecodePatch.Kind.DELETE) {
                    if (zipEntry != null) {
                        throw new PatchEngineException(
                            jar + " still contains " + entry.name()
                                + ", which the patch set removes"
                                + "\n  Reason: a removed entry left in the result means the"
                                + " index was not applied in full");
                    }
                    continue;
                }
                if (zipEntry == null) {
                    throw new PatchEngineException(
                        jar + " does not contain " + entry.name());
                }
                byte[] inJar;
                try (var in = zip.getInputStream(zipEntry)) {
                    inJar = in.readAllBytes();
                }
                var actual = BytecodePatch.sha256Hex(inJar);
                if (!actual.equalsIgnoreCase(entry.resultSha256())) {
                    throw new PatchEngineException(
                        jar + " contains a different " + entry.name()
                            + " than the patch set produces"
                            + "\n  Expected SHA-256: " + entry.resultSha256()
                            + "\n  Actual SHA-256:   " + actual
                            + "\n  Reason: the packaged bytes and the patch set disagree, so the"
                            + " jar would not run what it claims to");
                }
            }
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                if (JarSignatures.isSignatureEntry(entries.nextElement().getName())) {
                    throw new PatchEngineException(
                        jar + " still contains signature material;"
                            + " a rewritten jar whose signatures are left in place fails"
                            + " verification inside the JDK at first class load");
                }
            }

            // Structure, re-parsed from the bytes on disk rather than from the
            // writer's own bookkeeping: a file that cannot be parsed the way it
            // was written is not the file that was verified.
            byte[] written = Files.readAllBytes(jar);
            var writtenIndex =
                RawZipWriter.readIndex(written, "The packaged runtime artifact " + jar);

            // Exactly these names, no others: the baseline's non-directory
            // entries minus what the index removes plus what it adds, with the
            // three entries the applier generates itself. An extra entry is as
            // serious as a missing one, because a reader would have to guess
            // which copy of a class name wins.
            var expectedNames = new LinkedHashSet<String>();
            expectedNames.add(JarFile.MANIFEST_NAME);
            expectedNames.add(RuntimeIdentity.JAR_IDENTITY_ENTRY);
            expectedNames.add(RuntimeIdentity.JAR_GUARD_ENTRY);
            for (var entry : baselineIndex.values()) {
                if (!entry.directory() && !JarFile.MANIFEST_NAME.equals(entry.name())) {
                    expectedNames.add(entry.name());
                }
            }
            for (var entry : patch.entries()) {
                if (entry.kind() == BytecodePatch.Kind.DELETE) {
                    expectedNames.remove(entry.name());
                } else {
                    expectedNames.add(entry.name());
                }
            }
            if (!writtenIndex.keySet().equals(expectedNames)) {
                var missing = new TreeSet<>(expectedNames);
                missing.removeAll(writtenIndex.keySet());
                var unexpected = new TreeSet<>(writtenIndex.keySet());
                unexpected.removeAll(expectedNames);
                throw new PatchEngineException(
                    jar + " does not hold exactly the entries the patch set"
                        + " and the baseline name"
                        + "\n  Missing:    " + missing
                        + "\n  Unexpected: " + unexpected
                        + "\n  Reason: the jar is the baseline, minus what the index removes,"
                        + " plus what it adds, plus the entries the applier generates; any"
                        + " other difference means something else wrote to the file");
            }

            // The unchanged entries, compared back to the baseline they were
            // copied from: first their metadata, then the bytes of the whole
            // region — local header, compressed data, data descriptor — which
            // must be identical, because that is what "copied unchanged" means.
            var named = new TreeSet<String>();
            for (var entry : patch.entries()) {
                named.add(entry.name());
            }
            for (var entry : baselineIndex.values()) {
                if (entry.directory() || JarFile.MANIFEST_NAME.equals(entry.name())
                        || named.contains(entry.name())) {
                    // Directories are not written; the manifest is generated;
                    // index-named entries were checked against the patch set.
                    continue;
                }
                var copy = writtenIndex.get(entry.name());
                if (copy == null) {
                    throw new PatchEngineException(
                        jar + " lost the unchanged entry " + entry.name()
                            + "\n  Reason: the copy loop wrote it or the structure check above"
                            + " proved it present; neither can be true here");
                }
                if (copy.method() != entry.method() || copy.crc() != entry.crc()
                        || copy.compressedSize() != entry.compressedSize()
                        || copy.uncompressedSize() != entry.uncompressedSize()) {
                    throw new PatchEngineException(
                        jar + " carries " + entry.name()
                            + " with different metadata than the baseline holds"
                            + "\n  Baseline: method=" + entry.method() + " crc=" + entry.crc()
                            + " compressed=" + entry.compressedSize()
                            + " uncompressed=" + entry.uncompressedSize()
                            + "\n  Written:  method=" + copy.method() + " crc=" + copy.crc()
                            + " compressed=" + copy.compressedSize()
                            + " uncompressed=" + copy.uncompressedSize()
                            + "\n  Reason: an unchanged entry is copied with its baseline"
                            + " central directory record, so a different value means the copy"
                            + " did not come from the baseline it claims");
                }
                long baselineLength = entry.regionEnd() - entry.localOffset();
                long copyLength = copy.regionEnd() - copy.localOffset();
                if (baselineLength != copyLength
                        || Arrays.mismatch(baseline, (int) entry.localOffset(),
                            (int) entry.regionEnd(), written, (int) copy.localOffset(),
                            (int) copy.regionEnd()) >= 0) {
                    throw new PatchEngineException(
                        jar + " does not hold the baseline's own bytes for"
                            + " the unchanged entry " + entry.name()
                            + (baselineLength == copyLength
                                ? "\n  Reason: the region differs from the baseline byte for"
                                    + " byte, so the entry was recompressed or rewritten rather"
                                    + " than copied"
                                : "\n  Baseline region: " + baselineLength + " bytes"
                                    + "\n  Written region:  " + copyLength + " bytes"
                                    + "\n  Reason: the copied region must span exactly the"
                                    + " baseline's local header, data and descriptor"));
                }
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to re-read the packaged runtime " + jar
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    private byte[] manifestBytes(RuntimeIdentity expected) {
        var manifest = new Manifest();
        var main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Veltis-Artifact", "veltis-server");
        main.putValue("Veltis-Minecraft-Version", expected.minecraftVersion());
        main.putValue("Veltis-Artifact-Format",
            String.valueOf(RuntimeIdentity.ARTIFACT_FORMAT));
        main.putValue("Veltis-Source-Revision", expected.sourceRevision());
        main.putValue("Veltis-Patch-Fingerprint", expected.patchFingerprint());
        main.putValue("Veltis-Patch-Format", String.valueOf(expected.patchFormat()));
        var bytes = new java.io.ByteArrayOutputStream();
        try {
            manifest.write(bytes);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to render the jar manifest", e);
        }
        return bytes.toByteArray();
    }

    private static byte[] renderGuard(Map<String, GuardedClass> guard) {
        var text = new StringBuilder();
        for (var entry : guard.entrySet()) {
            text.append(entry.getKey()).append('\t')
                .append(entry.getValue().compiledSha256()).append('\t')
                .append(entry.getValue().baselineSha256()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every guarded class in a packaged runtime: entry name to the SHA-256 of the
     * packaged bytes and of the baseline they replaced.
     *
     * <p>Empty when the jar is absent or carries no record, which is what every
     * caller wants to know before it starts comparing anything.
     */
    public static Map<String, GuardedClass> readGuard(Path jar) {
        var guard = new LinkedHashMap<String, GuardedClass>();
        if (jar == null || !Files.isRegularFile(jar)) {
            return guard;
        }
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(RuntimeIdentity.JAR_GUARD_ENTRY);
            if (entry == null) {
                return guard;
            }
            try (var in = zip.getInputStream(entry)) {
                for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .split("\\R")) {
                    var parts = line.split("\t", -1);
                    if (parts.length == 3) {
                        guard.put(parts[0], new GuardedClass(parts[1], parts[2]));
                    }
                }
            }
        } catch (IOException e) {
            return guard;
        }
        return guard;
    }

    /**
     * The Veltis version recorded in a packaged runtime.
     *
     * <p>Read from the jar manifest rather than from a constant, so there is no
     * second place for the build number to drift; {@code unknown} is a value,
     * not a failure, because the version is diagnostic and invalidates nothing.
     */
    private static String veltisVersion() {
        try {
            var pkg = VeltisRuntime.class.getPackage();
            var version = pkg == null ? null : pkg.getImplementationVersion();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * The classpath the server runs on, in resolution order.
     *
     * <p>Three kinds of entry, and nothing else: the runtime artifact, Mojang's
     * libraries, and this process's own jar so that Minecraft's classes can reach
     * VeltisMC's. There is deliberately no directory of loose class files and no
     * separate vanilla jar — the artifact is complete, and keeping a second copy
     * of a class anywhere on this list is how "which one wins" becomes a question
     * that has to be answered at all.
     *
     * @param application the launcher's own location, appended last
     */
    public List<Path> classpath(Path application) {
        var entries = new ArrayList<Path>();
        entries.add(workspace.veltisServerJar());
        entries.addAll(MinecraftDownloader.libraryJars(workspace));
        if (application != null) {
            entries.add(application);
        }
        return List.copyOf(entries);
    }

    /**
     * A classloader over the runtime classpath, in exactly the order
     * {@link #classpath(Path)} gives it.
     *
     * <p>Both callers use this — the launcher, and the build-time guard that
     * proves the ordering before a jar is packaged. Building it in one place is
     * what makes that guard worth anything: a check against a hand-rolled
     * loader would be evidence about a classpath nobody runs.
     *
     * <p>The parent is the platform loader rather than the loader this class was
     * loaded by. Minecraft's classes and VeltisMC's modules are both absent from
     * it, so neither can shadow the other by accident, and a vanilla class
     * already loaded by an outer loader cannot be handed out in place of the
     * patched one.
     */
    public java.net.URLClassLoader newClassLoader(Path application) {
        return newClassLoader(classpath(application));
    }

    /**
     * The same loader with vanilla's classes ahead of the runtime artifact —
     * the exact mistake the guard exists to catch, built on purpose.
     *
     * <p>The classpath contains no vanilla jar, so this is the only way to
     * demonstrate the guard: hand it a loader that really would serve vanilla
     * and show that it refuses. Used by {@code -PveltisGuardCanary}.
     *
     * @throws PatchEngineException when there is no classes jar to put first,
     *                               because a canary that silently ran the normal
     *                               classpath would report success and prove
     *                               nothing
     */
    public java.net.URLClassLoader vanillaFirstClassLoader(Path application) {
        var vanilla = workspace.vanillaClassesJar();
        if (!Files.isRegularFile(vanilla)) {
            throw new PatchEngineException(
                "The guard canary needs " + vanilla
                    + "\n  Reason: without a vanilla classes jar there is no wrong classpath"
                    + " to build, and a canary that runs the right one proves nothing");
        }
        var entries = new ArrayList<Path>();
        entries.add(vanilla);
        entries.addAll(classpath(application));
        return newClassLoader(entries);
    }

    private java.net.URLClassLoader newClassLoader(List<Path> entries) {
        return new java.net.URLClassLoader(
            classpathUrls(entries).toArray(java.net.URL[]::new),
            ClassLoader.getPlatformClassLoader());
    }

    /**
     * The classpath as URLs, for the loader.
     *
     * <p>Every entry is checked here rather than at first use. A missing library
     * jar otherwise surfaces as a {@code NoClassDefFoundError} from whatever
     * Minecraft class happened to touch it first, which names a Minecraft type
     * and hides the fact that the installation is incomplete.
     */
    public List<java.net.URL> classpathUrls(Path application) {
        return classpathUrls(classpath(application));
    }

    private List<java.net.URL> classpathUrls(List<Path> entries) {
        var urls = new ArrayList<java.net.URL>();
        for (var entry : entries) {
            if (!Files.exists(entry)) {
                throw new PatchEngineException(
                    "The runtime classpath entry is missing: " + entry
                        + "\n  Reason: the installation is incomplete, so the server cannot"
                        + " start"
                        + "\n  Fix: delete " + workspace.veltisServerJar()
                        + " and start again to rebuild the runtime");
            }
            try {
                urls.add(entry.toUri().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new PatchEngineException(
                    "The runtime classpath entry cannot be used: " + entry, e);
            }
        }
        return List.copyOf(urls);
    }

    /**
     * Checks that the loader that is about to run the server resolves the patched
     * classes, and that they really are patched.
     *
     * <p>Three questions, asked of the live loader rather than of the files on
     * disk:
     *
     * <ol>
     *   <li>Does the loader report the runtime artifact as the code source of
     *       every patched class? {@code initialize=false} is used so this cannot
     *       run any static initialiser: this is an inspection, not a start-up.
     *       This is what makes a loose {@code classes/} directory, a stale build
     *       tree or a differently-ordered classpath a hard failure rather than a
     *       silent downgrade.</li>
     *   <li>Do the bytes the loader will hand out hash to the SHA-256 recorded
     *       for the class when it was packaged? The same bytes are then
     *       re-derived independently: the verified vanilla class plus the delta
     *       this runtime ships are spliced together again, and the result must
     *       hash to the same value — which catches a hand-edited jar without
     *       trusting anything the jar says about itself.</li>
     *   <li>Are those bytes still different from the baseline class they
     *       replaced? A patch that silently applied nothing produces a class
     *       identical to the one it replaced, and a server that runs it looks
     *       healthy.</li>
     * </ol>
     *
     * @return a one-line report naming the jar the classes came from
     * @throws PatchEngineException on the first disagreement; the server is not
     *                              started, because starting it would mean
     *                              running unverified classes
     */
    public String verifyPatchedClasses(ClassLoader loader) {
        var jar = workspace.veltisServerJar().toAbsolutePath().normalize();
        var guard = readGuard(jar);
        if (guard.isEmpty()) {
            throw new PatchEngineException(
                jar + " records no guarded classes"
                    + "\n  Reason: a runtime jar that cannot say which classes it was built"
                    + " from cannot be checked, and an unchecked runtime is not started");
        }
        // Re-derivation needs both inputs the applier had: the delta set (from
        // the workspace in a checkout) and the verified vanilla classes to
        // splice it onto. A server installation is not allowed to keep either —
        // its work tree is discarded on purpose — so there the guard checks
        // what the jar alone can prove: every guarded class resolves, matches
        // the record written when the jar was verified, loads from this jar and
        // links. In a checkout, where both inputs exist, the record is not
        // trusted: every class is re-derived from the baseline and the shipped
        // payload, and the record only has to agree with the result.
        var vanillaClasses = workspace.vanillaClassesJar();
        BytecodePatch patch = null;
        var rows = new LinkedHashMap<String, BytecodePatch.IndexEntry>();
        if (Files.isRegularFile(vanillaClasses)) {
            var loaded = loadBytecodePatch();
            if (loaded.isEmpty()) {
                throw new PatchEngineException(
                    jar + " cannot be re-verified: no bytecode patch set is"
                        + " available"
                        + "\n  Reason: the guard re-derives every guarded class from the patch set"
                        + " that produced it, and without the patch set the guard's hashes are only"
                        + " claims the jar makes about itself");
            }
            patch = loaded.get();
            for (var row : patch.entries()) {
                rows.put(row.name(), row);
            }
        }
        if (patch == null) {
            try {
                return checkGuardedClasses(loader, jar, guard, null, rows, null, null);
            } catch (IOException e) {
                throw new PatchEngineException(
                    "Failed while checking the guarded classes"
                        + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
            }
        }
        try (var vanillaZip = new ZipFile(vanillaClasses.toFile())) {
            return checkGuardedClasses(loader, jar, guard, patch, rows, vanillaZip,
                vanillaClasses);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read " + vanillaClasses
                    + " while re-deriving the guarded classes"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * The per-class half of {@link #verifyPatchedClasses}, run with the
     * resources the re-derivation needs already opened around it: every guarded
     * class is resolved by the live loader, hashed against the guard, loaded
     * from this jar and linked. When the re-derivation inputs are present —
     * they are, in a checkout — each class is additionally re-derived from the
     * verified vanilla class plus the payload the patch set ships for it.
     *
     * @param loader         the classloader that would serve the server
     * @param jar            the packaged runtime the guard was read from
     * @param guard          the guarded classes, in file order
     * @param patch          the bytecode patch set that produced them, or
     *                       {@code null} when the work tree that carried it was
     *                       discarded (a server installation)
     * @param rows           the patch set's index rows, keyed by entry name
     * @param vanilla        the open baseline jar to re-derive class deltas
     *                       from, or {@code null} when there is no baseline
     * @param vanillaClasses its path, for failure messages
     * @return the one-line report the caller returns
     */
    private String checkGuardedClasses(ClassLoader loader, Path jar,
            Map<String, GuardedClass> guard, BytecodePatch patch,
            Map<String, BytecodePatch.IndexEntry> rows, ZipFile vanilla,
            Path vanillaClasses) throws IOException {
        int verified = 0;
        for (var guarded : guard.entrySet()) {
            var resource = guarded.getKey();
            var record = guarded.getValue();
            var binaryName = resource.substring(0, resource.length() - ".class".length())
                .replace('/', '.').replace('\\', '.');

            byte[] served;
            try (var in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    throw guardFailure("the runtime classloader does not resolve " + resource
                        + " at all, so it would fall back to whatever is behind it",
                        resource, jar, null);
                }
                served = readAll(in);
            } catch (IOException e) {
                throw new PatchEngineException("Failed to read " + resource
                    + " through the runtime classloader", e);
            }

            var servedSha = BytecodePatch.sha256Hex(served);
            if (!servedSha.equals(record.compiledSha256())) {
                throw guardFailure("the runtime classloader resolves " + resource
                    + " to bytes whose SHA-256 is " + servedSha + " but the packaged class is "
                    + record.compiledSha256(), resource, jar, loaderUrlOf(loader, resource));
            }
            if (servedSha.equals(record.baselineSha256())) {
                throw guardFailure("the class the loader will use is byte-for-byte identical to"
                    + " the baseline " + binaryName + " it is supposed to replace",
                    resource, jar, loaderUrlOf(loader, resource));
            }

            // Re-derive rather than trust: verified vanilla bytes plus the delta
            // this runtime ships must produce exactly what the guard records —
            // an independent path to the same value, taken again on every
            // launch instead of remembered from the run that packaged the jar.
            // A server installation has neither input by design, so there the
            // record, the origin and the link above are the whole check.
            if (patch != null) {
                var row = rows.get(resource);
                if (row == null) {
                    throw guardFailure("the patch set does not name the class it guards, so the"
                        + " guard's record cannot be reproduced from the patch set that claims to"
                        + " have produced it", resource, jar, null);
                }
                if (row.kind() == BytecodePatch.Kind.DELETE) {
                    throw guardFailure("the patch set marks the class for removal while the guard"
                        + " records it as packaged", resource, jar, null);
                }
                var payload = patch.payload(resource);
                if (payload == null) {
                    throw guardFailure("the patch set carries no payload for the class",
                        resource, jar, null);
                }
                byte[] expected;
                if (row.kind() == BytecodePatch.Kind.CLASS) {
                    var vanillaEntry = vanilla.getEntry(resource);
                    if (vanillaEntry == null) {
                        throw guardFailure("the Minecraft classes jar no longer contains the"
                            + " baseline class the shipped delta is spliced onto",
                            resource, jar, vanillaClasses);
                    }
                    byte[] baselineBytes;
                    try (var in = vanilla.getInputStream(vanillaEntry)) {
                        baselineBytes = in.readAllBytes();
                    }
                    expected = ClassDelta.apply(baselineBytes, payload).bytes();
                } else {
                    expected = payload;
                }
                var expectedSha = BytecodePatch.sha256Hex(expected);
                if (!expectedSha.equals(record.compiledSha256())) {
                    throw guardFailure("re-applying the shipped payload to the verified vanilla"
                        + " class produces bytes whose SHA-256 is " + expectedSha
                        + " but the guard records " + record.compiledSha256(),
                        resource, jar, vanillaClasses);
                }
            }

            Class<?> loaded;
            try {
                loaded = Class.forName(binaryName, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                // Not a soft failure. Minecraft loads this class seconds later and
                // the same error would be far harder to read from there.
                throw new PatchEngineException(
                    "The runtime classloader could not load " + binaryName
                        + "\n  Class: " + resource
                        + "\n  Jar: " + jar
                        + "\n  Reason: " + MojangMetadata.rootMessage(e)
                        + "\n  The classloader resolves the class file but cannot link it;"
                        + " the runtime was not started", e);
            }
            var codeSource = loaded.getProtectionDomain().getCodeSource();
            var location = codeSource == null || codeSource.getLocation() == null
                ? null
                : locationPath(codeSource.getLocation());
            if (location == null || !location.equals(jar)) {
                throw guardFailure("loading " + binaryName + " reports its code source as "
                    + (location == null ? "<none>" : location) + ", not " + jar,
                    resource, jar, location);
            }
            verified++;
        }
        return "Verified " + verified + " patched class" + (verified == 1 ? "" : "es")
            + " loaded from " + jar
            + (patch == null ? " (record and origin; the work tree that carried the patch set"
                + " was discarded)" : "");
    }

    private PatchEngineException guardFailure(String detail, String resource,
                                              Path jar, Object actual) {
        return new PatchEngineException(
            "Patched class " + resource + " is not what the runtime will load"
                + "\n  Class: " + resource
                + "\n  Artifact: " + jar
                + "\n  Runtime classloader resolved: "
                + (actual == null ? "<see detail>" : actual)
                + "\n  Reason: " + detail
                + "\n  The server was not started. Starting it would have run a runtime"
                + " that is not the patched one.");
    }

    // ------------------------------------------------------------------
    // Patch targets
    // ------------------------------------------------------------------

    /**
     * The workspace-relative patch targets of one category, optionally filtered
     * by extension.
     *
     * <p>Read from {@code patch-targets.txt}, the manifest the patcher writes
     * before it applies anything. Using it rather than re-deriving the target list
     * from the patch text is what keeps "what will be compiled" and "what was
     * patched" the same answer at build time and at run time.
     *
     * <p>Build-time only. A server installation deletes the manifest with the
     * rest of its work tree, and the record of which classes were patched at
     * that point lives in the jar instead — see {@link #readGuard}.
     */
    public List<String> patchTargets(PatchCategory category, String extension) {
        var file = workspace.patchTargetsFile();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        var wanted = category.directoryName();
        // A set, in first-appearance order. Two patches may legitimately address
        // one file — a patch that extends an earlier one does exactly that, and
        // this patch set already does — and the answer to "which files does the
        // patch set address" is a list of files, not a list of patch-to-file
        // relationships. Duplicating one here would hand javac the same source
        // twice and make packaging write the same jar entry twice, which ZipOutputStream
        // refuses outright.
        var targets = new LinkedHashSet<String>();
        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                if (!line.substring(0, tab).equals(wanted)) {
                    continue;
                }
                var target = line.substring(tab + 1).trim();
                if (!target.isEmpty() && (extension == null || target.endsWith(extension))) {
                    targets.add(target);
                }
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read the patch target manifest " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "\n  Fix: the patch step writes this file; re-run it", e);
        }
        return List.copyOf(targets);
    }

    /** The jar entry a {@code .java} patch target compiles to. */
    static String classEntryFor(String target) {
        return target.endsWith(".java")
            ? target.substring(0, target.length() - ".java".length()) + ".class"
            : target;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }

    private static byte[] bytesOf(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new PatchEngineException("Failed to read " + path, e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    static String sha1Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JRE does not provide SHA-1", e);
        }
    }

    private static byte[] readClassFromJar(Path jar, String entryName) {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            LOG.debug("Could not read {} from {}", entryName, jar, e);
            return null;
        }
    }

    /**
     * The file a code source points at, as an absolute normalized {@link Path}.
     *
     * <p>Compared as a path rather than as a URI string, because the two spellings
     * differ for reasons that have nothing to do with the answer:
     * {@code Path.toUri()} renders Windows drives as {@code file:///C:/...} while a
     * class loader's code source renders the same directory as {@code file:/C:/...}.
     * Comparing those as text would fail the guard on Windows for every class, in
     * every configuration, which is the worst possible failure for a check that is
     * supposed to be trustworthy.
     *
     * @return the path, or {@code null} when the location is absent or not a file URL
     */
    private static Path locationPath(java.net.URL location) {
        try {
            if (!"file".equalsIgnoreCase(location.getProtocol())) {
                return null;
            }
            return Path.of(location.toURI()).toAbsolutePath().normalize();
        } catch (java.net.URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }

    private static Object loaderUrlOf(ClassLoader loader, String resource) {
        var url = loader.getResource(resource);
        return url == null ? "<not found>" : url;
    }
}
