package org.veltismc.patchengine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Turns a compiled VeltisMC build into the patch set a server applies.
 *
 * <p>Inputs are exactly three: the vanilla classes jar, the classes javac
 * produced from the patched sources, and the patched resource delta. Output is
 * one deterministic file. Nothing else is consulted, so the same three inputs
 * always produce the same patch set, byte for byte, on any machine.
 *
 * <h2>What goes in</h2>
 *
 * <ul>
 *   <li><b>One class delta per compiled class that differs from vanilla</b>,
 *       produced by {@link ClassDeltaGenerator}: only the members that actually
 *       changed after normalisation, the members that vanished, and the flags
 *       that differ — never the unchanged Mojang methods javac reproduced on the
 *       way through, never a whole compiled class. Classes javac merely derived
 *       from a patched file are compared too — an anonymous class left at
 *       vanilla's copy beside a patched outer class is a class loading
 *       disagreement three frames deep in vanilla code. Classes that happen to
 *       be byte-identical to vanilla are dropped, because a patch set is only
 *       supposed to carry what differs.</li>
 *   <li><b>Flag-only deltas for the vanilla classes that need widening</b>,
 *       decided by {@link AccessRequirements} from the references the patched
 *       classes actually make: one delta whose header or whose recorded members
 *       get rewritten flags, and no bodies at all. This is the difference
 *       between shipping a handful of classes and shipping seven thousand: the
 *       development pipeline widens everything because it has to hand a whole
 *       tree to a decompiler, but a patch set only has to widen what the patched
 *       bytecode reaches. Measured against Minecraft 26.3, that set is empty.</li>
 *   <li><b>The patched resources</b>, from {@code resources/}, which is the
 *       {@code data} and {@code modules} delta and never a copy of Minecraft's
 *       own files.</li>
 *   <li><b>Mojang's signature entries, as removals.</b> The applier has no rule
 *       about signatures; the index says so, the original hash is checked like
 *       any other, and a jar signed differently is a different index rather than
 *       a special case.</li>
 * </ul>
 *
 * <h2>What is checked first</h2>
 *
 * <p>Before anything is written, the patched classes are checked for two ways of
 * being worse than the vanilla classes they replace: narrowed access, and
 * members that vanished. Either one compiles, packages, and then fails at
 * runtime on a class the patch never mentioned, so neither is allowed to reach
 * a patch set. Both are reported with the class, the member and the reason.
 */
public final class BytecodePatchGenerator {

    private static final Logger LOG = LogManager.getLogger(BytecodePatchGenerator.class);

    /**
     * What one generation did.
     *
     * @param file         where the patch set was written
     * @param metadata     the metadata block that was written
     * @param patchedClasses how many compiled classes differ from vanilla and
     *                       therefore became class-bearing entries
     * @param widenedClasses how many vanilla classes needed a flag-only widening delta
     * @param resources    how many patched resources are included
     * @param removals     how many baseline entries are removed
     * @param analysisNanos how long deciding the widening set took
     * @param writeNanos   how long writing the patch set took
     */
    public record Result(Path file, BytecodePatch.Metadata metadata, int patchedClasses,
                         int widenedClasses, int resources, int removals, long analysisNanos,
                         long writeNanos) {
    }

    private BytecodePatchGenerator() {
    }

    /**
     * Generates the bytecode patch set for a workspace.
     *
     * @param workspace        the development workspace holding {@code classes/}
     *                         and {@code resources/}
     * @param version          the Minecraft version the baseline belongs to
     * @param serverSha1       Mojang's published SHA-1 for that version, recorded
     *                         so an application can refuse a re-published artifact
     * @param classFileRelease the release javac compiled the payloads to
     * @param sourceRevision   the fingerprint of the source patch set, recorded in
     *                         every delta so a delta cannot be applied out of its
     *                         patch set's context
     * @param sourcePatchCount how many source patches produced this build, for the
     *                         metadata block and the §25 summary lines
     * @param workers          the bounded pool size for class comparison and delta
     *                         generation
     * @return what was written
     * @throws PatchEngineException when the workspace is not ready, when the
     *                              patched classes would not link against the
     *                              vanilla jar, or when a patch targets a file
     *                              the applier generates itself
     */
    public static Result generate(VeltisWorkspace workspace, MinecraftVersion version,
                                  String serverSha1, int classFileRelease,
                                  String sourceRevision, int sourcePatchCount, int workers) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(sourceRevision, "sourceRevision cannot be null");
        var vanillaJar = workspace.vanillaClassesJar();
        if (!Files.isRegularFile(vanillaJar)) {
            throw new PatchEngineException(
                "Cannot generate a bytecode patch set: the verified Minecraft classes"
                    + " jar is missing: " + vanillaJar
                    + "\n  Reason: there is no baseline to diff against, and a patch set with"
                    + " no baseline would have to guess what vanilla looks like");
        }
        var compiled = readCompiledClasses(workspace.classesDirectory());
        if (compiled.isEmpty()) {
            throw new PatchEngineException(
                "Cannot generate a bytecode patch set: " + workspace.classesDirectory()
                    + " holds no class files"
                    + "\n  Reason: compilation did not produce output, so there is nothing to"
                    + " ship");
        }

        long analysisStarted = System.nanoTime();
        Map<String, byte[]> vanillaClasses;
        Map<String, String> vanillaOtherSha1;
        try {
            var loaded = readBaseline(vanillaJar);
            vanillaClasses = loaded.classes();
            vanillaOtherSha1 = loaded.others();
        } catch (IOException e) {
            throw new PatchEngineException(
                "Cannot read the Minecraft classes jar " + vanillaJar
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        var analysis = AccessRequirements.analyze(vanillaClasses, compiled);
        if (!analysis.acceptable()) {
            throw new PatchEngineException(
                "The patched classes are not safe to ship over the vanilla jar"
                    + "\n  Baseline: " + vanillaJar
                    + "\n  Reasons:"
                    + "\n    " + String.join("\n    ", analysis.violations())
                    + "\n  Reason: a patched class that is less reachable than the vanilla one"
                    + " it replaces, or that drops a member another class may still call,"
                    + " compiles and packages and then fails at runtime on a class the patch"
                    + " never mentioned"
                    + "\n  Fix: widen the declaration in the source patch instead of narrowing"
                    + " it, or keep the member the vanilla class had");
        }
        long analysisNanos = System.nanoTime() - analysisStarted;
        LOG.debug("Baseline analysis: {}", AccessRequirements.describe(analysis));

        // One delta per distinct modified class, compared and verified against
        // the baseline bytes — bounded pool, deterministic order.
        var classResult = ClassDeltaGenerator.generate(vanillaClasses, compiled,
            version.toString(), sourceRevision, workers);
        var records = new ArrayList<>(classResult.entries());
        int patchedClasses = classResult.entries().size();

        // Flag-only deltas for vanilla classes the patched bytecode reaches into.
        var wideningEntries =
            ClassDeltaGenerator.wideningDeltas(vanillaClasses, analysis, version.toString(),
                sourceRevision);
        records.addAll(wideningEntries);
        int widenedClasses = wideningEntries.size();

        var resources = collectResources(workspace.resourcesDirectory(), vanillaOtherSha1);
        records.addAll(resources.values());

        int removals = 0;
        try (var zip = new ZipFile(vanillaJar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !JarSignatures.isSignatureEntry(entry.getName())) {
                    continue;
                }
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                records.add(new BytecodePatch.ProducedEntry(entry.getName(),
                    BytecodePatch.Kind.DELETE, null, BytecodePatch.sha256Hex(bytes),
                    BytecodePatch.ABSENT));
                removals++;
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "Cannot scan " + vanillaJar + " for signature entries"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }

        var file = workspace.bytecodePatchFile();
        long writeStarted = System.nanoTime();
        var metadata = BytecodePatch.write(file, version.toString(),
            serverSha1.toLowerCase(Locale.ROOT), sha1OfFile(vanillaJar),
            classFileRelease, sourcePatchCount, records);
        long writeNanos = System.nanoTime() - writeStarted;
        writeExploded(workspace.runtimePatchesDirectory(), records);

        var timings = classResult.timings();
        var runtimeClassPatches = classResult.deltaCount() + widenedClasses;
        LOG.debug("Generated {} runtime class patches.", runtimeClassPatches);
        LOG.debug("Runtime patches: {} classes modified / {} Shulker source patches"
                + " / {} unchanged classes",
            metadata.classCount(), sourcePatchCount, classResult.unchanged());
        LOG.debug("Runtime patch generation: analysis {}, class comparison {}, delta"
                + " generation {}, verification {}, write {} ({} workers)",
            VeltisConsole.formatDuration(analysisNanos),
            VeltisConsole.formatDuration(timings.comparisonNanos()),
            VeltisConsole.formatDuration(timings.generationNanos()),
            VeltisConsole.formatDuration(timings.verificationNanos()),
            VeltisConsole.formatDuration(writeNanos), timings.workers());
        return new Result(file, metadata, patchedClasses, widenedClasses, resources.size(),
            removals, analysisNanos, writeNanos);
    }

    /**
     * Writes the exploded mirror of the payload directory: same names, same
     * bytes, plain files.
     *
     * <p>The tree is removed first, so a payload deleted from the patch set in a
     * later build cannot linger here and read as present — the container and this
     * directory are two views of one run, and the second must not remember the
     * first.
     */
    private static void writeExploded(Path directory, List<BytecodePatch.ProducedEntry> records) {
        try {
            if (Files.exists(directory)) {
                try (Stream<Path> walk = Files.walk(directory)) {
                    for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            Files.createDirectories(directory);
            for (var record : records) {
                if (record.payload() == null) {
                    continue;
                }
                var target = directory.resolve(record.name());
                Files.createDirectories(target.getParent());
                Files.write(target, record.payload());
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to write the exploded runtime patches to " + directory
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Collects the patched resources.
     *
     * <p>Each one records the SHA-256 of the vanilla entry it replaces, or
     * {@code -} when vanilla has no such entry. The applier checks that claim in
     * both directions, so a resource written against a vanilla jar that does not
     * have the state the patch expects is refused rather than silently
     * overwriting something else.
     */
    private static Map<String, BytecodePatch.ProducedEntry> collectResources(
            Path resources, Map<String, String> vanillaOther) {
        var collected = new TreeMap<String, BytecodePatch.ProducedEntry>();
        if (!Files.isDirectory(resources)) {
            return collected;
        }
        try (Stream<Path> walk = Files.walk(resources)) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                var name = resources.relativize(file).toString().replace('\\', '/');
                if (name.endsWith(".class")) {
                    // A class is not a resource. Compilers put them in classes/;
                    // something naming one here would silently duplicate it.
                    throw new PatchEngineException(
                        "The resource delta " + file + " is a class file"
                            + "\n  Entry name: " + name
                            + "\n  Reason: class payloads come from the compiled output, and"
                            + " letting a resource shadow one would make the served bytes"
                            + " depend on which directory a build happened to look in first");
                }
                if (RuntimeIdentity.JAR_IDENTITY_ENTRY.equals(name)
                        || RuntimeIdentity.JAR_GUARD_ENTRY.equals(name)
                        || JarFile.MANIFEST_NAME.equals(name)) {
                    throw new PatchEngineException(
                        "The resource delta targets " + name
                            + "\n  Reason: the applier generates that entry from the identity of"
                            + " the artifact it is writing, so a patch for it would be"
                            + " overwritten by the very thing that applies it");
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException e) {
                    throw new PatchEngineException(
                        "Failed to read the patched resource " + file
                            + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
                }
                var original = vanillaOther.get(name);
                collected.put(name, new BytecodePatch.ProducedEntry(name, BytecodePatch.Kind.ENTRY,
                    bytes, original == null ? BytecodePatch.ABSENT : original,
                    BytecodePatch.sha256Hex(bytes)));
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read the patched resources in " + resources
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        return collected;
    }

    private record Baseline(Map<String, byte[]> classes, Map<String, String> others) {
    }

    /**
     * Reads the baseline once, in one pass: class bytes for the analysis and for
     * the diffs, and a hash for every other entry so a resource's original can be
     * stated without keeping two and a half megabytes of manifest in memory.
     */
    private static Baseline readBaseline(Path jar) throws IOException {
        var classes = new HashMap<String, byte[]>();
        var others = new HashMap<String, String>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    classes.put(entry.getName(), bytes);
                } else {
                    others.put(entry.getName(), BytecodePatch.sha256Hex(bytes));
                }
            }
        }
        return new Baseline(classes, others);
    }

    private static Map<String, byte[]> readCompiledClasses(Path directory) {
        try {
            var classes = AccessRequirements.readClassDirectory(directory);
            if (classes.isEmpty()) {
                return Map.of();
            }
            return classes;
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read the compiled classes in " + directory
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    private static String sha1OfFile(Path file) {
        try {
            return MojangMetadata.sha1(file).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to hash the baseline " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }
}
