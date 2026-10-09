package org.veltismc.patchengine;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Decompiles the verified, access-widened server jar into the workspace's
 * pristine source tree.
 *
 * <p>Decompiling takes minutes, so it happens at most once per
 * (version, artifact) pair. Whether it may be skipped is decided by a marker
 * written into the source tree after a successful run, and that marker records
 * the version id, the server artifact's SHA-1, the library count and the
 * SHA-1 of the jar actually fed to the decompiler. A tree decompiled from a
 * different jar — an older run, a hand-copied directory, a version bumped in
 * {@code gradle.properties} without re-running the pipeline — fails the check
 * and is decompiled again, because a patch that was authored against different
 * bytecode must not be applied to it.
 *
 * <p>The input is {@link VeltisWorkspace#widenedServerJar()}, not the jar
 * Mojang published. The decompiled source is compiled against the widened jar,
 * so it has to <em>be</em> the widen jar's decompile: a class that was
 * {@code protected} in Mojang's bytecode is {@code public} in the widened one,
 * and recompiling Mojang's original modifiers over a widened superclass is a
 * compile error ("attempting to assign weaker access privileges").
 *
 * <p>Two properties make a failed decompile safe:
 *
 * <ul>
 *   <li>the previous tree is deleted <em>before</em> the decompiler starts, so a
 *       half-written tree can never be mistaken for a complete one;</li>
 *   <li>the marker is written <em>only</em> after the decompiler returns, so its
 *       presence means the tree is whole.</li>
 * </ul>
 *
 * <p>Output goes to {@link VeltisWorkspace#sourceDirectory()} and nowhere else.
 * The decompiler also lifts Minecraft's own resources (data packs, assets,
 * {@code version.json}) into the same tree, which is what lets a {@code data}
 * patch address a resource path directly.
 */
public final class MinecraftDecompiler {

    /**
     * Vineflower leaves this placeholder where it could not invent a variable
     * name. It is not valid Java and would break compilation, so it is stripped
     * as a post-pass. Applied once, to files, in a single walk.
     */
    private static final Pattern VAR_NAMELESS = Pattern.compile(
        Pattern.quote("<VAR_NAMELESS_ENCLOSURE>"));

    /**
     * Decompiler settings, in one place.
     *
     * <p>Held as data rather than a sequence of calls for two reasons: the cache
     * marker records them, so changing a setting invalidates the cached tree
     * instead of silently leaving an old tree in place, and the setting that
     * matters is documented next to the other ones instead of buried in a list.
     *
     * <p>{@code REMOVE_SYNTHETIC} is off deliberately. javac compiles an enum
     * {@code switch} through a synthetic {@code $SwitchMap$} field declared in an
     * anonymous class; dropping synthetics hides that field from the decompiler,
     * which then emits the placeholder {@code <unrepresentable>} instead of the
     * switch. That is not valid Java, and it is spread over hundreds of files
     * rather than confined to one.
     */
    private static final Map<String, String> OPTIONS = Map.ofEntries(
        Map.entry(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1"),
        Map.entry(IFernflowerPreferences.REMOVE_SYNTHETIC, "0"),
        Map.entry(IFernflowerPreferences.REMOVE_BRIDGE, "1"),
        Map.entry(IFernflowerPreferences.LOG_LEVEL, "warn"),
        Map.entry(IFernflowerPreferences.MAX_PROCESSING_METHOD, "5"),
        Map.entry(IFernflowerPreferences.INDENT_STRING, "    "),
        Map.entry(IFernflowerPreferences.UNIT_TEST_MODE, "0"),
        Map.entry(IFernflowerPreferences.PATTERN_MATCHING, "1"),
        Map.entry(IFernflowerPreferences.SWITCH_EXPRESSIONS, "1"),
        Map.entry(IFernflowerPreferences.EXPLICIT_GENERIC_ARGUMENTS, "1"),
        Map.entry(IFernflowerPreferences.INLINE_SIMPLE_LAMBDAS, "1"));

    private final org.apache.logging.log4j.Logger log =
        org.apache.logging.log4j.LogManager.getLogger(MinecraftDecompiler.class);

    /** What a decompile call did. */
    public enum Outcome {
        /** The source tree already belonged to this artifact; nothing was decompiled. */
        CACHED,
        /** The source tree was produced by this call. */
        DECOMPILED
    }

    /**
     * Decompiles unless the workspace already holds a tree for this artifact.
     *
     * @param workspace  the target workspace (created if absent)
     * @param metadata   the resolved version, which supplies the identity to check
     * @return whether the decompile was reused
     */
    public Outcome decompile(VeltisWorkspace workspace, MojangMetadata.VersionMetadata metadata) {
        workspace.createDirectories();
        if (isValid(workspace, metadata)) {
            log.debug("[VeltisMinecraft] Reusing the decompiled source for {} in {}",
                metadata.id(), workspace.sourceDirectory().relativize(workspace.root()));
            return Outcome.CACHED;
        }

        var jar = workspace.widenedServerJar();
        if (!Files.isRegularFile(jar)) {
            throw new PatchEngineException(
                "[VeltisMinecraft] No access-widened server jar to decompile at " + jar
                    + "\n  Reason: run the widening step first"
                    + " (./gradlew downloadMinecraft widenServerJarAccess)");
        }

        // Removed up front so an interrupted decompile can never leave a tree
        // that a later run mistakes for a complete one.
        VeltisWorkspace.deleteTree(workspace.sourceDirectory());
        try {
            Files.createDirectories(workspace.sourceDirectory());
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Failed to create the source directory "
                    + workspace.sourceDirectory(), e);
        }

        long started = System.nanoTime();
        run(jar, workspace);
        fixupDecompileErrors(workspace.sourceDirectory());
        // Only now is the tree real. The marker is the last thing written.
        MinecraftDownloader.writeMarker(workspace.decompileMarker(),
            markerContent(metadata, sha1Of(jar)));
        log.debug("[VeltisMinecraft] Decompiled {} in {} ({} .java files)", metadata.id(),
            VeltisConsole.formatDuration(System.nanoTime() - started),
            countJavaFiles(workspace.sourceDirectory()));
        return Outcome.DECOMPILED;
    }

    /**
     * A cached tree is only reused when the marker names this exact artifact and
     * the tree still contains sources. Any one of the three failing means redo
     * the work: a marker without sources, or sources without a marker, is exactly
     * the half-finished state this check exists to catch.
     */
    static boolean isValid(VeltisWorkspace workspace,
                           MojangMetadata.VersionMetadata metadata) {
        var marker = workspace.decompileMarker();
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        // The jar the tree was decompiled from has to still be there: if it is
        // gone or has been rebuilt, the tree cannot be proven to match it.
        var jar = workspace.widenedServerJar();
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            if (!Files.readString(marker, StandardCharsets.UTF_8)
                    .equals(markerContent(metadata, sha1Of(jar)))) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return countJavaFiles(workspace.sourceDirectory()) > 0;
    }

    /**
     * The marker's full contents: the artifact identity, not just a version string.
     *
     * @param decompileInputSha1 SHA-1 of the jar handed to the decompiler, so a
     *                           tree cannot be reused after the widener's output
     *                           has changed underneath it
     */
    private static String markerContent(MojangMetadata.VersionMetadata metadata,
                                        String decompileInputSha1) {
        var content = new StringBuilder()
            .append("minecraft=").append(metadata.id()).append('\n')
            .append("serverSha1=")
            .append(metadata.serverArtifact().sha1().toLowerCase(java.util.Locale.ROOT)).append('\n')
            .append("libraries=").append(metadata.libraries().size()).append('\n')
            .append("decompileInputSha1=").append(decompileInputSha1).append('\n');
        // Sorted, because Map.ofEntries does not promise an iteration order and
        // the marker is compared for equality.
        OPTIONS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> content.append("decompiler.").append(e.getKey())
                .append('=').append(e.getValue()).append('\n'));
        return content.toString();
    }

    /** The decompiler settings, exposed so a test can assert the marker's format. */
    static Map<String, String> OPTIONS() {
        return OPTIONS;
    }

    private static String sha1Of(Path file) {
        try {
            return MojangMetadata.sha1(file);
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Could not hash the decompile input " + file, e);
        }
    }

    private void run(Path jar, VeltisWorkspace workspace) {
        var builder = Decompiler.builder();
        for (var option : OPTIONS.entrySet()) {
            builder.option(option.getKey(), option.getValue());
        }

        builder.output(new DirectoryResultSaver(workspace.sourceDirectory().toFile()));
        builder.logger(new PrintStreamLogger(VeltisConsole.logStream("veltis.decompiler")));
        builder.inputs(jar.toFile());

        // Mojang's own libraries are on the decompiler classpath so external
        // types resolve and the output is the same on every machine instead of
        // depending on what else happens to be on the JVM's classpath.
        for (var library : MinecraftDownloader.libraryJars(workspace)) {
            builder.libraries(library.toFile());
        }

        try {
            builder.build().decompile();
        } catch (Exception e) {
            throw new PatchEngineException(
                "[VeltisMinecraft] Decompilation failed for " + jar
                    + "\n  Output: " + workspace.sourceDirectory()
                    + "\n  Reason: " + MojangMetadata.rootMessage(e)
                    + "\n  The partial source tree was left for inspection; the next run"
                    + " starts from scratch because the decompile marker was not written.", e);
        }
    }

    /**
     * Strips the decompiler's unresolvable-variable placeholder.
     *
     * <p>One walk, and only the files that actually contain it are rewritten, so
     * the common case costs a read and a string comparison per file and no writes
     * at all.
     */
    private void fixupDecompileErrors(Path sourceDirectory) {
        var fixed = 0;
        try (var stream = Files.walk(sourceDirectory)) {
            for (var file : stream.filter(MinecraftDecompiler::isJava).toList()) {
                try {
                    var content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.indexOf("<VAR_NAMELESS_ENCLOSURE>") < 0) {
                        continue;
                    }
                    Files.writeString(file,
                        VAR_NAMELESS.matcher(content).replaceAll(""), StandardCharsets.UTF_8);
                    fixed++;
                } catch (IOException e) {
                    log.warn("[VeltisMinecraft] Could not clean up {}", file);
                }
            }
        } catch (IOException e) {
            log.warn("[VeltisMinecraft] Could not scan for decompiler placeholders: {}",
                MojangMetadata.rootMessage(e));
        }
        if (fixed > 0) {
            log.debug("[VeltisMinecraft] Cleaned {} decompiled file(s)", fixed);
        }
    }

    private static boolean isJava(Path path) {
        return path.toString().endsWith(".java") && Files.isRegularFile(path);
    }

    private static long countJavaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(MinecraftDecompiler::isJava).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
