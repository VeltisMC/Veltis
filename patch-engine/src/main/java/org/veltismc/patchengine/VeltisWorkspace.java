package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Every path the pipeline reads or writes, for one Minecraft version, in one of
 * two layouts: a <em>development</em> checkout and a <em>server installation</em>.
 *
 * <p>The two layouts exist for a reason that is not cosmetic. A contributor
 * needs the decompiled and patched sources kept between runs, because the IDE
 * navigates them and {@code rebuildVeltisPatches} diffs them. An operator needs
 * none of that and must not be left with it: their server directory is a
 * deployment, not a build tree. So the same engine writes to a layout chosen by
 * who is asking, and the difference is <em>where the work tree lives</em> — not
 * what the work tree is called.
 *
 * <h2>Development</h2>
 *
 * <pre>
 * &lt;project&gt;/build/minecraft/&lt;version&gt;/
 *   metadata/           cached Mojang version metadata + library coordinate list
 *   vanilla/            Mojang's verified jars (bundler, classes, widened)
 *   libraries/          Mojang's declared library jars, SHA-1 verified
 *   source/             pristine decompile — the baseline every patch applies to
 *   patched/            source/ + the Veltis patch set (the IDE source root)
 *   classes/            javac output for the patched sources
 *   resources/          the data and modules patch delta
 *   build/              deterministic scratch that survives a run (patch targets)
 *   veltis-server.jar   the packaged runtime artifact
 *   .runtime-marker     written last; proves classes/ is complete and current
 * </pre>
 *
 * <p>{@code build/} is Gradle's own generated tree, which is exactly what this
 * is: generated, disposable, and reproducible with {@code ./gradlew
 * prepareMinecraft}. It is declared as the root of the {@code minecraft} source
 * set, so an IDE import picks it up with no manual "Mark Directory as Sources
 * Root" and no source download of any kind.
 *
 * <h2>Server installation</h2>
 *
 * <pre>
 * &lt;home&gt;/
 *   Vanilla/&lt;version&gt;/vanilla-server.jar    Mojang's artifact, SHA-1 verified
 *   libraries/**                           the jars Minecraft needs to run,
 *                                          shared by every version
 *   Veltis/&lt;version&gt;/veltis-server.jar       the patched runtime
 * </pre>
 *
 * <p>That is the whole persistent surface, and it is deliberately all of it.
 * There is no marker file, no metadata directory, no source tree and no
 * {@code .vlt}: the validity of the runtime is recorded <em>inside</em>
 * {@code veltis-server.jar} (see {@link RuntimeIdentity#readFromJar}), and
 * everything needed to <em>produce</em> that jar — the bundler's inner classes
 * jar, the access-widened jar, the decompile, the patched tree, the compiled
 * output — lives in a per-installation work directory under the system temporary
 * directory, is used for the length of one build, and is removed when the build
 * succeeds.
 *
 * <p>Two things follow from that, both on purpose. A failed build leaves the
 * server directory exactly as clean as a successful one, because the work never
 * happens there. And a warm start never creates a work directory at all: it
 * reads the jar, hashes the vanilla artifact and launches.
 */
public final class VeltisWorkspace {

    /** Deterministic scratch inside the development workspace. */
    public static final String BUILD_DIRECTORY = "build";
    /** Directory under {@link #BUILD_DIRECTORY} that holds per-version state. */
    public static final String MINECRAFT_DIRECTORY = "minecraft";
    /** The persistent vanilla artifact root of a server installation. */
    public static final String VANILLA_INSTALL_DIRECTORY = "Vanilla";
    /** The persistent Veltis artifact root of a server installation. */
    public static final String VELTIS_INSTALL_DIRECTORY = "Veltis";

    private static final String METADATA = "metadata";
    private static final String VANILLA = "vanilla";
    private static final String LIBRARIES = "libraries";
    private static final String SOURCE = "source";
    private static final String PATCHED = "patched";
    private static final String CLASSES = "classes";
    private static final String RESOURCES = "resources";
    private static final String SCRATCH = "build";

    /** Marks a completed decompile and records what produced it. */
    public static final String DECOMPILE_MARKER = ".decompile-marker";
    /** Gradle coordinate list of Mojang's libraries, one {@code g:a:v} per line. */
    public static final String LIBRARY_COORDINATES = "libraries.txt";

    /**
     * Development-only proof of which patch set, artifact and build
     * configuration produced {@code classes/}. A server installation has no
     * equivalent file: its record travels inside the jar.
     */
    public static final String RUNTIME_MARKER = ".runtime-marker";

    /** Which of the two layouts this workspace follows. */
    public enum Layout {
        /**
         * A contributor's checkout: everything is kept, because the IDE and the
         * patch-rebuild workflow read it on every run.
         */
        DEVELOPMENT,
        /**
         * An operator's server directory: only {@code Vanilla/} and
         * {@code Veltis/} persist, and the work tree lives outside the
         * installation entirely.
         */
        SERVER_INSTALL
    }

    private final Path projectDirectory;
    private final Path root;
    private final Path vanillaInstallDirectory;
    private final Path librariesDirectory;
    private final Path veltisServerJar;
    private final MinecraftVersion version;
    private final Layout layout;

    private VeltisWorkspace(Path projectDirectory, Path root, MinecraftVersion version,
                            Layout layout) {
        this.projectDirectory = projectDirectory;
        this.root = root;
        this.version = version;
        this.layout = layout;
        if (layout == Layout.SERVER_INSTALL) {
            var install = projectDirectory
                .resolve(VANILLA_INSTALL_DIRECTORY)
                .resolve(version.toString());
            this.vanillaInstallDirectory = install;
            // At the server root, not under Vanilla/<version>: one library root
            // for every version, so upgrading from 26.3 to 26.4 re-verifies and
            // reuses the jars they share and fetches only what is new. Under
            // Vanilla/<version> each upgrade would re-download all of them, and
            // two versions would carry two copies of the same bytes.
            this.librariesDirectory = projectDirectory.resolve(LIBRARIES);
            this.veltisServerJar = projectDirectory
                .resolve(VELTIS_INSTALL_DIRECTORY)
                .resolve(version.toString())
                .resolve("veltis-server.jar");
        } else {
            this.vanillaInstallDirectory = root.resolve(VANILLA);
            this.librariesDirectory = root.resolve(LIBRARIES);
            this.veltisServerJar = root.resolve("veltis-server.jar");
        }
    }

    /**
     * A contributor's checkout: {@code <project>/build/minecraft/<version>}.
     *
     * <p>This is the layout the Gradle pipeline, the IDE and the patch-rebuild
     * workflow all use, and it is the only one that keeps a source tree.
     *
     * @param projectDirectory the VeltisMC project root (the directory holding
     *                         {@code settings.gradle.kts})
     * @param version          the Minecraft version this workspace belongs to
     */
    public static VeltisWorkspace of(Path projectDirectory, MinecraftVersion version) {
        Objects.requireNonNull(projectDirectory, "projectDirectory cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        var project = projectDirectory.toAbsolutePath().normalize();
        return new VeltisWorkspace(project, project
            .resolve(BUILD_DIRECTORY)
            .resolve(MINECRAFT_DIRECTORY)
            .resolve(version.toString()), version, Layout.DEVELOPMENT);
    }

    /**
     * An operator's server directory: {@code <home>/Vanilla} and
     * {@code <home>/Veltis} for what persists, and a private work directory
     * under the system temporary directory for everything else.
     *
     * <p>The work directory is derived from the installation's absolute path, so
     * two servers on one machine cannot collide and neither can a re-run of the
     * same one, while nothing about it is random or needs to be looked up: it is
     * a pure function of where the server lives.
     *
     * @param home    the server directory (the one holding {@code server.properties})
     * @param version the Minecraft version this installation runs
     */
    public static VeltisWorkspace serverInstall(Path home, MinecraftVersion version) {
        Objects.requireNonNull(home, "home cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        var base = home.toAbsolutePath().normalize();
        return new VeltisWorkspace(base,
            workDirectoryFor(base, version), version, Layout.SERVER_INSTALL);
    }

    private static Path workDirectoryFor(Path home, MinecraftVersion version) {
        var key = home.toString().toLowerCase(java.util.Locale.ROOT);
        var digest = sha256Hex(key.getBytes(StandardCharsets.UTF_8));
        return Path.of(System.getProperty("java.io.tmpdir", System.getProperty("user.home")),
            "veltismc-build", digest.substring(0, 16), version.toString());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JRE does not provide SHA-256", e);
        }
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public Layout layout() {
        return layout;
    }

    /** Whether this workspace must leave a server installation clean. */
    public boolean isServerInstall() {
        return layout == Layout.SERVER_INSTALL;
    }

    /**
     * The project root (development) or the server home (installation). Kept
     * rather than derived by walking up from {@link #root()}, so a caller
     * looking for the patch set or the distribution directory does not have to
     * know the layout depth.
     */
    public Path projectDirectory() {
        return projectDirectory;
    }

    /**
     * The work tree root: {@code build/minecraft/<version>} in a checkout, a
     * private temporary directory in a server installation.
     *
     * <p>Everything intermediate lives here — metadata, the extracted and
     * widened jars, the decompile, the patched tree, the compiled output. A
     * server installation deletes it once {@code veltis-server.jar} has been
     * written and verified; see {@link #discardWorkTree()}.
     */
    public Path root() {
        return root;
    }

    /** The Minecraft version this workspace is for. */
    public MinecraftVersion version() {
        return version;
    }

    /**
     * The persistent root Mojang's artifacts are materialised into.
     *
     * <p>Equal to {@link #vanillaDirectory()} in a checkout. In a server
     * installation it is {@code Vanilla/<version>}, because the verified
     * bundler jar and the libraries are the two things a launch needs that are
     * not inside {@code veltis-server.jar}.
     */
    public Path vanillaInstallDirectory() {
        return vanillaInstallDirectory;
    }

    // ------------------------------------------------------------------
    // Work tree
    // ------------------------------------------------------------------

    public Path metadataDirectory() {
        return root.resolve(METADATA);
    }

    /** The cached copy of Mojang's version metadata, so resolution is offline on reruns. */
    public Path versionMetadataFile() {
        return metadataDirectory().resolve("version.json");
    }

    /** {@code group:artifact:version} lines for Mojang's declared libraries. */
    public Path libraryCoordinatesFile() {
        return metadataDirectory().resolve(LIBRARY_COORDINATES);
    }

    /** The work-tree jar directory: the extracted classes jar and the widened jar. */
    public Path vanillaDirectory() {
        return root.resolve(VANILLA);
    }

    /**
     * Mojang's server jar exactly as published (a bundler jar for modern
     * versions), at its persistent location.
     */
    public Path vanillaServerJar() {
        if (layout == Layout.SERVER_INSTALL) {
            return vanillaInstallDirectory.resolve("vanilla-server.jar");
        }
        return vanillaInstallDirectory.resolve("server.jar");
    }

    /**
     * The class-bearing jar. For modern versions this is
     * {@code META-INF/versions/<version>/server-<version>.jar} lifted out of
     * {@link #vanillaServerJar()}, because the manifest SHA-1 covers the outer
     * bundler only. Always part of the work tree: it is derived state, and a
     * server installation is not allowed to keep it.
     */
    public Path vanillaClassesJar() {
        return vanillaDirectory().resolve("server-classes.jar");
    }

    /** {@link #vanillaClassesJar()} with class/field/method access widened for module compilation. */
    public Path widenedServerJar() {
        return vanillaDirectory().resolve("server-widened.jar");
    }

    /**
     * Mojang's declared library jars, SHA-1 verified, in Mojang's own layout.
     *
     * <p>Persistent in both layouts: these are the jars Minecraft links against
     * at run time. In a server installation it is <em>one directory at the
     * server root</em>, {@code <home>/libraries/}, shared by every Minecraft
     * version rather than nested inside one — that sharing is what makes a
     * version upgrade cost only the libraries it actually added.
     */
    public Path librariesDirectory() {
        return librariesDirectory;
    }

    /** The pristine decompile; the baseline patches apply to and rebuild diffs against. */
    public Path sourceDirectory() {
        return root.resolve(SOURCE);
    }

    /** The patched workspace. This is the IDE source root and the compile input. */
    public Path patchedDirectory() {
        return root.resolve(PATCHED);
    }

    public Path classesDirectory() {
        return root.resolve(CLASSES);
    }

    /**
     * The resources the {@code data} and {@code modules} patch categories
     * changed, layered over the vanilla jar at load time.
     *
     * <p>Only the delta is here. The runtime jar already carries Minecraft's
     * own resources, so copying all of them would be a second copy of the same
     * bytes and would let a stale resource win over a patched one depending on
     * classpath order.
     */
    public Path resourcesDirectory() {
        return root.resolve(RESOURCES);
    }

    /**
     * The runtime artifact: the access-widened server jar with the patched
     * classes and resources written back into it, plus the record of what it is.
     *
     * <p>This is what the server runs from, in both layouts, and it is the only
     * Minecraft class path entry either of them uses. In a server installation
     * it is {@code Veltis/<version>/veltis-server.jar}.
     */
    public Path veltisServerJar() {
        return veltisServerJar;
    }

    /** Deterministic scratch inside the work tree; holds the patch target manifest. */
    public Path buildDirectory() {
        return root.resolve(SCRATCH);
    }

    /** Marker proving which artifact/version produced {@code source/}. */
    public Path decompileMarker() {
        return sourceDirectory().resolve(DECOMPILE_MARKER);
    }

    /**
     * The manifest of files the patch set addresses, written by the patcher as
     * {@code <category>\t<path>} lines.
     *
     * <p>It exists so the build never has to re-parse patch text in a Gradle
     * script. The engine already knows the answer after discovery, and writing
     * it down means the compile classpath and the resource set are derived from
     * the same parse the patcher used — one source of truth, not two
     * implementations of "which files are patched".
     */
    public Path patchTargetsFile() {
        return buildDirectory().resolve("patch-targets.txt");
    }

    /**
     * Which patches {@code patched/} was last built from: {@code <category>\t
     * <name>\t<revision>} lines.
     *
     * <p>It exists for one question a rebuild has to be able to ask — <em>has a
     * patch file been deleted since this workspace was patched?</em> The answer
     * cannot be recovered from the trees alone: {@code patched/} still contains
     * the deleted patch's changes, so a rebuild would faithfully diff them into a
     * brand new patch and the deletion would look like it never happened. This
     * file is the only record of what was actually applied, so the rebuild can
     * refuse instead, name the missing patch, and say which task puts the
     * workspace back in step.
     */
    public Path appliedPatchesFile() {
        return buildDirectory().resolve("applied-patches.txt");
    }

    /**
     * The bytecode patch set generated for this workspace: the compiled
     * difference between Mojang's classes jar and a VeltisMC build, expressed as
     * one class delta per modified class plus resource and removal entries, with
     * three SHA-256s on every index line.
     *
     * <p>Development builds generate it and then apply it, so the jar they end up
     * with is produced by the same code a server installation runs — which is the
     * only way the distribution path can be tested by the same build that ships
     * it. A server installation has no use for this path: its patch set is the
     * one packaged inside the launcher jar.
     *
     * <p>Inside {@code build/} rather than beside the artifact, because it is a
     * derived file. Gradle copies it into the launcher jar from here; nothing
     * else reads it.
     */
    public Path bytecodePatchFile() {
        return buildDirectory().resolve("veltis-patch.zip");
    }

    /**
     * The exploded mirror of the patch set's payload directory: every delta,
     * resource and payload the container carries, under the same names the
     * container uses, at the workspace root.
     *
     * <p>It exists so the patch set's contents can be inspected, diffed and
     * wired into build outputs without unzipping anything — the container and
     * this tree are written from the same records in the same run, so they
     * agree byte for byte. It is regenerated from scratch on every generation;
     * a leftover file here is never read back.
     *
     * <p>Declared as a Gradle output in the root build script.</p>
     */
    public Path runtimePatchesDirectory() {
        return root.resolve("runtime-patches");
    }

    /**
     * Development-only marker proving the compiled runtime in
     * {@link #classesDirectory()} belongs to this patch set and artifact.
     *
     * <p>A server installation has no marker file. Its equivalent is embedded
     * in {@link #veltisServerJar()}, which is the only place a record is
     * trustworthy anyway: a file next to a jar can be left behind by an
     * installation the jar does not match.
     */
    public Path runtimeMarker() {
        return root.resolve(RUNTIME_MARKER);
    }

    /** The {@code server/Shulker/} directory the development patch set lives in. */
    public Path shulkerDirectory() {
        return projectDirectory.resolve("server").resolve(PatchDiscovery.SHULKER_DIRECTORY);
    }

    /**
     * Creates the directories the pipeline always needs. Idempotent.
     *
     * <p>Only called on the building path. A warm start — which is the normal
     * one — creates nothing at all.
     */
    public VeltisWorkspace createDirectories() {
        for (var dir : List.of(root, metadataDirectory(), vanillaInstallDirectory(),
            vanillaDirectory(), librariesDirectory(), sourceDirectory(), patchedDirectory(),
            classesDirectory(), buildDirectory(), resourcesDirectory())) {
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                throw new PatchEngineException(
                    "Failed to create workspace directory " + dir + ": " + e.getMessage(), e);
            }
        }
        return this;
    }

    /**
     * Removes the whole work tree if this is a server installation.
     *
     * <p>Called as the last step of a successful build, and never before it. A
     * build that failed keeps its tree on purpose: the decompile is the part
     * that costs minutes, it is validated against the widened jar by its own
     * marker, and reusing it is what makes a retry after a compile error cost
     * seconds instead of a full re-decompile. Only a build that produced a jar
     * has nothing left to keep. A checkout never discards — a kept tree is
     * exactly what it is for.
     *
     * @return the number of top-level entries removed, or 0 when nothing was kept
     */
    public long discardWorkTree() {
        if (layout != Layout.SERVER_INSTALL) {
            return 0L;
        }
        if (!Files.exists(root)) {
            return 0L;
        }
        long entries;
        try (var walk = Files.list(root)) {
            entries = walk.count();
        } catch (Exception e) {
            throw new PatchEngineException(
                "[Veltis] Failed to inspect the build work tree " + root
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        deleteTree(root);
        return entries;
    }

    /**
     * Removes a directory tree if it exists. Never throws for a missing path.
     *
     * <p>Belongs to the workspace rather than to the source patch engine that
     * once held it: a server installation reaches it through
     * {@link #discardWorkTree()}, and the distributable deliberately carries no
     * source patcher — a tree utility is not a reason to start shipping one.
     */
    public static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                        throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMC] Failed to clear " + root
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "; close anything holding files open there and try again", e);
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
