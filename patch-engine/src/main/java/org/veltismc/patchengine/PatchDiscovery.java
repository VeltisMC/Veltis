package org.veltismc.patchengine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Finds the patch set for a run, in deterministic order.
 *
 * <p>Three sources are consulted, cheapest first:
 * <ol>
 *   <li>the jar the engine itself is loaded from (how the shipped
 *       {@code veltismc.jar} carries its patches),</li>
 *   <li>well-known filesystem directories around the home/working directory,</li>
 *   <li>classpath directory entries (development runs).</li>
 * </ol>
 * Later sources never overwrite an entry an earlier source already provided, and
 * the numeric filename prefix is stripped so that {@code 003-Foo.patch} and
 * {@code Foo.patch} are one logical patch (the numbered one wins). The result is
 * sorted by canonical name, which is the application order.
 */
final class PatchDiscovery {

    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^\\d{3}-(.+)$");

    private PatchDiscovery() {}

    /** Discovers patches from every source for the given home directory. */
    static List<RuntimePatchApplier.PatchContent> discover(Path homeDirectory, PatchStats stats) {
        return discover(homeDirectory, stats, NO_WARNINGS);
    }

    /** Discovers patches from every source (no statistics). */
    static List<RuntimePatchApplier.PatchContent> discover(Path homeDirectory) {
        return discover(homeDirectory, new PatchStats(), NO_WARNINGS);
    }

    /** Discovers patches, reporting non-fatal scan problems through {@code warn}. */
    static List<RuntimePatchApplier.PatchContent> discover(Path homeDirectory, PatchStats stats,
                                                           java.util.function.Consumer<String> warn) {
        var start = System.nanoTime();
        Map<String, RuntimePatchApplier.PatchContent> patches = new LinkedHashMap<>();
        scanJarForPatches(patches, warn);
        scanFilesystemForPatches(homeDirectory, patches, warn);
        scanClasspathForPatches(patches, warn);
        var result = sort(patches.values());
        stats.discoveryNanos += System.nanoTime() - start;
        stats.patchesDiscovered += result.size();
        return result;
    }

    /** Reads every {@code *.patch} directly inside {@code dir}, deterministically sorted. */
    static List<RuntimePatchApplier.PatchContent> fromDirectory(Path dir, PatchStats stats) {
        var start = System.nanoTime();
        Map<String, RuntimePatchApplier.PatchContent> patches = new LinkedHashMap<>();
        scanDirForPatches(dir, patches, NO_WARNINGS);
        var result = sort(patches.values());
        stats.discoveryNanos += System.nanoTime() - start;
        stats.patchesDiscovered += result.size();
        return result;
    }

    private static final java.util.function.Consumer<String> NO_WARNINGS = msg -> {};

    private static List<RuntimePatchApplier.PatchContent> sort(
            java.util.Collection<RuntimePatchApplier.PatchContent> patches) {
        var result = new ArrayList<>(patches);
        result.sort(Comparator.comparing(RuntimePatchApplier.PatchContent::name));
        return result;
    }

    private static void scanJarForPatches(Map<String, RuntimePatchApplier.PatchContent> patches,
                                          java.util.function.Consumer<String> warn) {
        try {
            var codeSource = PatchedJarBuilder.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return;
            var location = codeSource.getLocation();
            if (location == null) return;
            var file = new java.io.File(location.toURI());
            if (!file.isFile() || !file.getName().endsWith(".jar")) return;
            try (var jf = new JarFile(file)) {
                var entries = jf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var jarPath = entry.getName();
                    if (jarPath.startsWith("patches/") && jarPath.endsWith(".patch")) {
                        var fileName = jarPath.substring(jarPath.lastIndexOf('/') + 1);
                        var canonical = canonicalPatchName(fileName);
                        // Prefer numbered (features/) patches over unnumbered (server/) duplicates
                        var hasNumeric = !canonical.equals(fileName);
                        var existing = patches.get(canonical);
                        if (existing != null && !hasNumeric) continue; // keep the numbered one
                        var lines = new BufferedReader(
                                new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8))
                                .lines().toList();
                        patches.put(canonical,
                            new RuntimePatchApplier.PatchContent(canonical, lines));
                    }
                }
            }
        } catch (Exception e) {
            warn.accept("Failed to scan JAR for patches: " + e.getMessage());
        }
    }

    private static void scanFilesystemForPatches(Path homeDirectory,
                                                  Map<String, RuntimePatchApplier.PatchContent> patches,
                                                  java.util.function.Consumer<String> warn) {
        var candidates = List.of(
            Path.of("patches", "server"),
            Path.of("patches", "features"),
            Path.of("server", "patches"),
            Path.of("server", "patches", "features"),
            Path.of(System.getProperty("user.dir"), "patches", "server"),
            Path.of(System.getProperty("user.dir"), "patches", "features"),
            Path.of(System.getProperty("user.dir"), "server", "patches"),
            Path.of(System.getProperty("user.dir"), "server", "patches", "features"),
            homeDirectory.resolve("patches").resolve("server"),
            homeDirectory.resolve("patches").resolve("features"),
            homeDirectory.resolve("server").resolve("patches"),
            homeDirectory.resolve("server").resolve("patches").resolve("features")
        );
        for (var dir : candidates) {
            scanDirForPatches(dir, patches, warn);
        }
    }

    private static void scanClasspathForPatches(Map<String, RuntimePatchApplier.PatchContent> patches,
                                                java.util.function.Consumer<String> warn) {
        var cl = PatchedJarBuilder.class.getClassLoader();
        for (var prefix : List.of("patches/server/", "patches/features/")) {
            try {
                var resources = cl.getResources(prefix);
                while (resources.hasMoreElements()) {
                    var url = resources.nextElement();
                    if (url == null) continue;
                    var file = new java.io.File(url.toURI());
                    if (file.isDirectory()) {
                        scanDirForPatches(file.toPath(), patches, warn);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void scanDirForPatches(Path dir, Map<String, RuntimePatchApplier.PatchContent> patches,
                                          java.util.function.Consumer<String> warn) {
        if (!Files.isDirectory(dir)) return;
        java.util.List<java.nio.file.Path> files;
        // Files.list, not Files.walk: only direct children are wanted, and the walk
        // machinery is extra first-touch class loading on the cold path (measured).
        try (var stream = Files.list(dir)) {
            files = stream.filter(f -> f.toString().endsWith(".patch")).sorted().toList();
        } catch (Exception e) {
            warn.accept("Failed to scan " + dir + ": " + e.getMessage());
            return;
        }
        if (files.isEmpty()) return;

        if (files.size() >= PARALLEL_READ_THRESHOLD) {
            // Large patch sets are read concurrently (one virtual thread per file);
            // the merge below still happens in sorted order, so precedence and the
            // final deterministic order are unaffected.
            var contents = new java.util.List[files.size()];
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<java.util.concurrent.Future<java.util.List<String>>>(
                    files.size());
                for (var f : files) {
                    futures.add(executor.submit(
                        () -> Files.readAllLines(f, StandardCharsets.UTF_8)));
                }
                for (int i = 0; i < files.size(); i++) {
                    try {
                        contents[i] = futures.get(i).get();
                    } catch (Exception e) {
                        warn.accept("Failed to read patch: " + files.get(i) + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                warn.accept("Failed to read patches in " + dir + ": " + e.getMessage());
            }
            mergeSorted(files, contents, patches);
        } else {
            var contents = new java.util.List[files.size()];
            for (int i = 0; i < files.size(); i++) {
                try {
                    contents[i] = Files.readAllLines(files.get(i), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    warn.accept("Failed to read patch: " + files.get(i) + ": " + e.getMessage());
                }
            }
            mergeSorted(files, contents, patches);
        }
    }

    /**
     * Merges already-read patches into the map in sorted file order.
     * The numeric prefix is stripped before the lookup, so {@code 003-Foo.patch}
     * and {@code Foo.patch} resolve to one logical patch and the numbered one
     * (which sorts first) wins.
     */
    private static void mergeSorted(java.util.List<java.nio.file.Path> files,
                                    java.util.List<String>[] contents,
                                    Map<String, RuntimePatchApplier.PatchContent> patches) {
        for (int i = 0; i < files.size(); i++) {
            if (contents[i] == null) continue;
            var canonical = canonicalPatchName(files.get(i).getFileName().toString());
            patches.putIfAbsent(canonical,
                new RuntimePatchApplier.PatchContent(canonical, contents[i]));
        }
    }

    /** Patch directories at least this large are read with one virtual thread per file. */
    private static final int PARALLEL_READ_THRESHOLD = 16;

    /** Strip the leading numeric prefix ("003-Improve-Command-Logging.patch" → "Improve-Command-Logging.patch"). */
    private static String canonicalPatchName(String fileName) {
        var m = NUMERIC_PREFIX.matcher(fileName);
        return m.matches() ? m.group(1) : fileName;
    }
}
