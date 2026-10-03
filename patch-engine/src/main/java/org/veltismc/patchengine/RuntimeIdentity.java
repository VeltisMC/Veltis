package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/**
 * What the compiled runtime in a workspace depends on, and how to tell whether
 * what is on disk still matches.
 *
 * <p>Four questions a server start has to answer, and each one is a reason the
 * previous runtime cannot be reused:
 *
 * <ul>
 *   <li><b>Is it the right Minecraft?</b> The version id <em>and</em> the SHA-1
 *       of the server artifact Mojang published. A version id alone is not
 *       enough: Mojang re-publishes an artifact for the same id, and a patch
 *       authored against the old bytecode would then be applied to different
 *       classes.</li>
 *   <li><b>Is it the right bytecode patch?</b> The patch fingerprint — SHA-256
 *       over the patch set's index, which carries the SHA-1 of every payload and
 *       of the baseline it replaces. This is what a cache is keyed on: the same
 *       Minecraft, the same artifact, the same fingerprint and the same patch
 *       format means the jar on disk is the jar this build would produce, so it
 *       is reused rather than rebuilt.</li>
 *   <li><b>Is it the right source patch set?</b> A single SHA-256 over every
 *       development patch file, in discovery order. A checkout has both kinds of
 *       patch to account for — the source patches that produce the bytecode and
 *       the bytecode itself — and only an unchanged source set can be trusted
 *       not to have invalidated {@code classes/}. A server installation has no
 *       source patches at all, and records the placeholder instead.</li>
 *   <li><b>Is it the right build configuration?</b> The class file release the
 *       patched sources are compiled to, the rewriter format the vanilla jar was
 *       widened with, and the bytecode patch format the payload was cut in. A
 *       change here invalidates classes that would otherwise still be
 *       byte-identical but wrong for this JVM or unreadable by this build.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> part of the identity: the decompiler's settings
 * and the decompiler's own version. Those are already covered, because the
 * decompile marker binds the source tree to the widened jar it came from, and a
 * different decompiler would produce a different {@code patched/} tree whose
 * compiled output differs. Putting them here as well would mean a redecompile
 * was demanded before the identity could even be computed.
 *
 * <p>Deliberately not part of it either: the worker count. More workers change
 * how long a rebuild takes and nothing about what it produces — the patcher is
 * required to be worker-count-independent — so folding it in would mean
 * changing a performance setting silently invalidated a working runtime.
 */
public record RuntimeIdentity(
    String minecraftVersion,
    String serverSha1,
    String sourceRevision,
    String patchFingerprint,
    int classFileRelease,
    int widenFormat,
    int patchFormat
) {

    /**
     * The entry inside {@code veltis-server.jar} that records what produced it.
     *
     * <p>Embedded rather than written next to the jar, and that is the whole
     * point: a marker file can outlive the installation it describes, be copied
     * beside a jar it never matched, or simply be the only survivor of a partial
     * uninstall. The jar and the claim about the jar are then one object, so
     * "is this the runtime I think it is" and "does this jar exist" can never
     * disagree.
     */
    public static final String JAR_IDENTITY_ENTRY = "META-INF/veltis/runtime.properties";

    /**
     * The entry inside {@code veltis-server.jar} listing every patched class
     * with the SHA-1 of the bytes that were compiled for it and of the baseline
     * class it replaced. Written after those bytes have been checked against the
     * jar itself, so it is the record a launch verifies against.
     */
    public static final String JAR_GUARD_ENTRY = "META-INF/veltis/guard.txt";

    /**
     * Bumped when the shape of the artifact or of its record changes in a way
     * that makes an older jar unusable rather than merely stale. Recorded with
     * the identity so an upgraded launcher discards an artifact built by an
     * older one instead of trying to interpret it.
     */
    public static final int ARTIFACT_FORMAT = 1;

    /**
     * What {@link #sourceRevision()} is when there is no source patch set.
     *
     * <p>A server installation has no {@code patches/} directory and never will:
     * the source patches are how the change is written, the bytecode patch set
     * is how it ships. Recording a placeholder rather than omitting the field
     * keeps one record shape across both layouts, so the comparison stays an
     * exact string equality.
     */
    public static final String NO_SOURCE_PATCHES = "-";

    /**
     * The canonical text form written to and compared against the marker file.
     *
     * <p>Key=value lines in a fixed order, so the file is human-readable in a
     * bug report and the comparison is an exact string equality rather than a
     * field-by-field walk that could compare a field against the wrong one.
     */
    public String render() {
        return "minecraft=" + minecraftVersion + '\n'
            + "serverSha1=" + serverSha1 + '\n'
            + "sourceRevision=" + sourceRevision + '\n'
            + "patchFingerprint=" + patchFingerprint + '\n'
            + "classFileRelease=" + classFileRelease + '\n'
            + "widenFormat=" + widenFormat + '\n'
            + "patchFormat=" + patchFormat + '\n';
    }

    /**
     * What is written into {@link #JAR_IDENTITY_ENTRY}: the identity, plus the
     * two facts the identity deliberately does not carry.
     *
     * <p>The artifact format says which layout this jar has. The Veltis version
     * says which build wrote it — informational, because it cannot invalidate
     * anything the identity does not already invalidate, but it is the first
     * thing anyone asked for in a bug report about a jar.
     */
    public String renderArtifact(String veltisVersion) {
        return render()
            + "artifactFormat=" + ARTIFACT_FORMAT + '\n'
            + "veltisVersion=" + (veltisVersion == null || veltisVersion.isBlank()
                ? "unknown" : veltisVersion) + '\n';
    }

    /**
     * The identity recorded inside a packaged runtime, or empty when the jar is
     * absent, unreadable, not a jar, or written in an artifact format this build
     * does not understand.
     *
     * <p>Empty is the only outcome other than the identity, and every one of
     * those states means the same thing to the caller: build it again. There is
     * no partially-trusted reading.
     */
    public static java.util.Optional<RuntimeIdentity> readFromJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return java.util.Optional.empty();
        }
        String text;
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry(JAR_IDENTITY_ENTRY);
            if (entry == null) {
                return java.util.Optional.empty();
            }
            try (var in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        var values = parseKeyValues(text);
        if (!String.valueOf(ARTIFACT_FORMAT).equals(values.get("artifactFormat"))) {
            return java.util.Optional.empty();
        }
        var identity = new RuntimeIdentity(
            values.get("minecraft"),
            values.get("serverSha1"),
            values.get("sourceRevision"),
            values.get("patchFingerprint"),
            parseInt(values.get("classFileRelease")),
            parseInt(values.get("widenFormat")),
            parseInt(values.get("patchFormat")));
        if (identity.minecraftVersion() == null || identity.serverSha1() == null
                || identity.sourceRevision() == null
                || identity.patchFingerprint() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(identity);
    }

    /** The {@code key=value} lines of a record, in file order, first wins. */
    static java.util.Map<String, String> parseKeyValues(String text) {
        var values = new java.util.LinkedHashMap<String, String>();
        for (var line : text.split("\\R")) {
            var eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Folds the whole development source patch set into one digest.
     *
     * <p>Each patch contributes its category, its name and its own SHA-256, in
     * discovery order, with a separator that cannot appear in a patch name. The
     * result changes if a patch is added, removed, renamed, reordered, moved
     * between categories, or edited — and nothing else changes it.
     *
     * <p>This is the <em>source</em> revision, and a server installation has
     * none: see {@link #NO_SOURCE_PATCHES}. The fingerprint that identifies the
     * shipped artifact is {@link #patchFingerprint()}, computed from the bytecode
     * patch set instead. Keeping the two apart is what stops a source patch from
     * looking like something a running server can be asked to apply.
     */
    public static String sourceRevisionOf(List<VeltisPatch> patches) {
        var digest = sha256();
        for (var patch : patches) {
            digest.update(patch.category().directoryName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(patch.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(patch.revision().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Whether the workspace's runtime is usable as-is.
     *
     * <p>Three conditions, and all three have to hold. The marker matching alone
     * is not enough: a user who deleted {@code classes/}, or a filesystem that
     * lost the directory, would otherwise get a runtime that is claimed ready
     * and is not. The class check is a cheap directory walk, not a re-hash of
     * every class file — the classes are produced by javac from sources that are
     * themselves covered by the patch revision, so their presence plus a matching
     * marker is the claim being made, and the load-time guard
     * ({@link VeltisRuntime}) is what actually proves the bytes.
     */
    public static boolean isCurrent(VeltisWorkspace workspace, RuntimeIdentity expected) {
        var marker = workspace.runtimeMarker();
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            if (!Files.readString(marker, StandardCharsets.UTF_8).equals(expected.render())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return hasCompiledClasses(workspace);
    }

    /**
     * Whether {@code classes/} holds at least one class file.
     *
     * <p>Deliberately a count of {@code .class} files and not a comparison
     * against the expected list: the list is derived from the patch set, and
     * requiring it here would mean re-parsing every patch on every launch, which
     * is the one thing a warm start is supposed to avoid. An empty or missing
     * directory is the failure this actually catches.
     */
    private static boolean hasCompiledClasses(VeltisWorkspace workspace) {
        var classes = workspace.classesDirectory();
        if (!Files.isDirectory(classes)) {
            return false;
        }
        try (var walk = Files.walk(classes, 4)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class") && Files.isRegularFile(p));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Records this identity as the workspace's current runtime.
     *
     * <p>Written after every other artifact of a build, so its presence means the
     * build finished. A crash between the last class file and this write leaves a
     * workspace with no marker, and the next launch rebuilds rather than
     * trusting a partial result.
     */
    public void recordAsCurrent(VeltisWorkspace workspace) {
        MinecraftDownloader.writeMarker(workspace.runtimeMarker(), render());
    }

    /** Removes the marker, so the next check reports the runtime as stale. */
    public static void invalidate(VeltisWorkspace workspace) {
        try {
            Files.deleteIfExists(workspace.runtimeMarker());
        } catch (IOException e) {
            throw new PatchEngineException(
                "[Veltis] Failed to invalidate the runtime marker in " + workspace, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            // Every JRE is required to provide SHA-256.
            throw new IllegalStateException("this JRE does not provide SHA-256", e);
        }
    }
}
