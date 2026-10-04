package org.veltismc.patchengine;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Repeatable benchmark for the Veltis patch pipeline.
 *
 * <p>Not run by {@code build}. Run it with {@code ./gradlew :patch-engine:benchmark}
 * (or with explicit arguments via {@code -PbenchArgs="..."}).
 *
 * <pre>
 * PatchBench &lt;repoRoot&gt; &lt;mode&gt; [options]
 *   mode:  patch-only | scale | git | all
 *   options:
 *     --iterations N                       timed runs per corpus (default 11 / 6)
 *     --files N --lines N --patches N      scale corpus shape (default 300/400/600)
 *     --workers N                          patch workers (default 4)
 *     --git-per-patch                      time a git process pair per patch
 *     --save FILE                          write metrics to FILE
 *     --compare FILE                       print metrics next to a saved FILE
 * </pre>
 *
 * <p>Every timed run restores pristine inputs first (untimed), then measures
 * discovery + parse + application + I/O, then verifies the output byte-for-byte
 * against the expected result (untimed). The first timed run after preparation is
 * reported as "cold", the rest as "warm" (min/avg).
 */
public final class PatchBench {

    private PatchBench() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        var repo = Path.of(args[0]).toAbsolutePath().normalize();
        var mode = args[1];
        var opts = parseOpts(args, 2);

        var all = new LinkedHashMap<String, Double>();

        switch (mode) {
            case "patch-only" -> runReal(repo, opts, all, true, false);
            case "scale" -> runScale(opts, all, true, false);
            case "git" -> {
                runReal(repo, opts, all, false, true);
                runScale(opts, all, false, true);
            }
            case "all" -> {
                runReal(repo, opts, all, true, true);
                runScale(opts, all, true, true);
            }
            default -> usage();
        }

        if (!all.isEmpty()) {
            System.out.println();
            if (opts.containsKey("save")) {
                saveMetrics(all, Path.of(opts.get("save")));
            }
            if (opts.containsKey("compare")) {
                compareMetrics(Path.of(opts.get("compare")), all);
            }
        }
    }

    private static void usage() {
        System.out.println("""
            Usage: PatchBench <repoRoot> <mode> [options]
              mode: patch-only | scale | git | all
              --iterations N | --files N | --lines N | --patches N | --workers N
              --git-per-patch | --save FILE | --compare FILE""");
    }

    private static Map<String, String> parseOpts(String[] args, int from) {
        var opts = new LinkedHashMap<String, String>();
        for (int i = from; i < args.length; i++) {
            var a = args[i];
            if (a.startsWith("--")) {
                var key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private static int optInt(Map<String, String> opts, String key, int dflt) {
        var v = opts.get(key);
        return v == null ? dflt : Integer.parseInt(v);
    }

    // ------------------------------------------------------------------
    // Corpora
    // ------------------------------------------------------------------

    /**
     * A prepared input: pristine sources, patch files, a workspace and the
     * expected output, so a timed run measures only the pipeline itself.
     */
    static final class Corpus {
        final String label;
        final Path pristine;        // pristine copies of every target file
        final Path patched;         // the real workspace, which patches are applied to
        final VeltisWorkspace workspace;
        final Path patchesRoot;     // the Shulker/ directory
        final List<String> targets;          // relative paths of patched files
        final Map<String, String> expected;  // rel path -> sha256 of the expected output
        final int patchCount;
        /** A workspace needs the pristine tree mirrored in before the first run. */
        boolean mirrored;

        Corpus(String label, Path pristine, Path patched, VeltisWorkspace workspace,
               Path patchesRoot, List<String> targets, Map<String, String> expected,
               int patchCount) {
            this.label = label;
            this.pristine = pristine;
            this.patched = patched;
            this.workspace = workspace;
            this.patchesRoot = patchesRoot;
            this.targets = targets;
            this.expected = expected;
            this.patchCount = patchCount;
        }

        /** Untimed: puts the pristine inputs back, by mirroring or copying. */
        void restore() throws IOException {
            if (mirrored) {
                // What the pipeline does: a fresh copy of the baseline. Exactly
                // the state a real run starts from, and exactly what the patcher
                // is written to expect.
                VeltisPatcher.mirrorPristineSource(pristine, patched);
                return;
            }
            for (var target : targets) {
                var src = pristine.resolve(target);
                var dst = patched.resolve(target);
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        /** sha256 of every target in the workspace, exact bytes. */
        Map<String, String> hashes() throws IOException {
            var out = new TreeMap<String, String>();
            for (var target : targets) {
                var file = patched.resolve(target);
                out.put(target, Files.isRegularFile(file)
                    ? sha256(Files.readAllBytes(file)) : "<missing>");
            }
            return out;
        }

        List<Path> patchFiles() throws IOException {
            try (var s = Files.walk(patchesRoot)) {
                return s.filter(p -> p.getFileName().toString().endsWith(".patch"))
                    .sorted()
                    .toList();
            }
        }
    }

    /**
     * Real corpus: the project's own patch set against its decompiled workspace.
     * Requires {@code ./gradlew applyVeltisPatches} to have been run.
     */
    static Corpus realCorpus(Path repo) throws IOException {
        var patchesRoot = repo.resolve("Shulker");
        if (!Files.isDirectory(patchesRoot)) {
            throw new IOException("Shulker/ missing - run from a VeltisMC checkout");
        }
        var version = resolveWorkspace(repo);
        var source = version.resolve("source");
        if (!Files.isDirectory(source)) {
            throw new IOException(source + " missing - run ./gradlew decompileMinecraft first");
        }

        var patches = PatchDiscovery.discover(patchesRoot, "26.3", new PatchStats());
        if (patches.isEmpty()) {
            throw new IOException("no patches in " + patchesRoot);
        }

        var targets = new ArrayList<String>();
        for (var patch : patches) {
            for (var target : patch.targets()) {
                if (!targets.contains(target)) {
                    targets.add(target);
                }
            }
        }

        var expected = new LinkedHashMap<String, String>();
        for (var target : targets) {
            var pristine = readOrNull(source.resolve(target));
            var current = readOrNull(version.resolve("patched").resolve(target));
            if (pristine == null && current != null) {
                expected.put(target, sha256(current.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new Corpus("patch-only", source, version.resolve("patched"),
            VeltisWorkspace.of(repo, TestWorkspace.VERSION), patchesRoot,
            targets, expected, patches.size());
    }

    /** The first {@code build/minecraft/<version>} directory present. */
    private static Path resolveWorkspace(Path repo) throws IOException {
        var root = repo.resolve(VeltisWorkspace.BUILD_DIRECTORY)
            .resolve(VeltisWorkspace.MINECRAFT_DIRECTORY);
        if (!Files.isDirectory(root)) {
            throw new IOException(root + " missing - run ./gradlew decompileMinecraft first");
        }
        try (var s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted().findFirst().orElseThrow(
                () -> new IOException("no Minecraft version prepared under " + root));
        }
    }

    /**
     * Scale corpus: generated files and patches, expected output computed by the
     * generator as it evolves the file contents in application order.
     */
    static Corpus scaleCorpus(Map<String, String> opts) throws IOException {
        var files = optInt(opts, "files", 300);
        var lines = optInt(opts, "lines", 400);
        var patches = optInt(opts, "patches", 600);

        var root = Files.createTempDirectory("veltis-bench-scale");
        // The corpus lives in a real workspace so the benchmark exercises the same
        // paths the build does, rather than a shortcut the production code never
        // takes.
        var workspace = VeltisWorkspace.of(root, TestWorkspace.VERSION).createDirectories();
        var pristine = workspace.sourceDirectory();
        var patched = workspace.patchedDirectory();
        var patchesRoot = workspace.shulkerDirectory().resolve("code");
        Files.createDirectories(patchesRoot);

        // Current content per file; evolves as patch files are generated in
        // application order, which is the order discovery will return them in.
        var contents = new ArrayList<List<String>>();
        var targets = new ArrayList<String>();
        for (int f = 0; f < files; f++) {
            var content = new ArrayList<String>();
            content.add("package gen;");
            content.add("// generated file " + f);
            content.add("class Gen" + f + " {");
            for (int i = 3; i < lines - 1; i++) {
                content.add("    int v" + i + " = " + i + ";");
            }
            content.add("}");
            contents.add(content);
            targets.add("gen/Gen" + f + ".java");
            writeLines(pristine.resolve(targets.get(f)), content);
        }

        for (int p = 0; p < patches; p++) {
            int f = p % files;
            var cur = contents.get(f);
            int pos = 8 + ((f * 7 + p * 13) % Math.max(1, cur.size() - 20));
            var rel = targets.get(f);

            var before = List.copyOf(cur);
            var removed = cur.get(pos);
            var added = List.of(removed, removed + " // patched " + p, removed + " // end " + p);

            var sb = new StringBuilder();
            sb.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n');
            sb.append("--- a/").append(rel).append('\n');
            sb.append("+++ b/").append(rel).append('\n');
            sb.append("@@ -").append(pos - 2).append(",7 +").append(pos - 2).append(",9 @@\n");
            for (int i = pos - 3; i < pos; i++) {
                sb.append(' ').append(before.get(i)).append('\n');
            }
            sb.append('-').append(removed).append('\n');
            for (var line : added) {
                sb.append('+').append(line).append('\n');
            }
            for (int i = pos + 1; i <= pos + 3; i++) {
                sb.append(' ').append(before.get(i)).append('\n');
            }
            Files.writeString(patchesRoot.resolve(String.format("%03d-Scale-P%03d.patch", p, p)),
                sb.toString(), StandardCharsets.UTF_8);

            cur.remove(pos);
            cur.addAll(pos, added);
        }

        var expected = new LinkedHashMap<String, String>();
        for (int f = 0; f < files; f++) {
            expected.put(targets.get(f),
                sha256(String.join("\n", contents.get(f)).concat("\n")
                    .getBytes(StandardCharsets.UTF_8)));
        }

        var corpus = new Corpus("scale", pristine, patched, workspace, workspace.shulkerDirectory(),
            targets, expected, patches);
        corpus.mirrored = true;
        return corpus;
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static String readOrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    static void runReal(Path repo, Map<String, String> opts, Map<String, Double> out,
                        boolean runJava, boolean runGit) throws IOException {
        Corpus corpus;
        try {
            corpus = realCorpus(repo);
        } catch (IOException e) {
            System.out.println("== patch-only corpus unavailable: " + e.getMessage());
            return;
        }
        System.out.println(corpusSection(corpus, optInt(opts, "iterations", 11)));
        Map<String, String> javaStrict = null;
        if (runJava) {
            var res = benchVeltis(corpus, optInt(opts, "iterations", 11),
                optInt(opts, "workers", 4));
            javaStrict = res.strictOutput;
            printVeltis(corpus, res, out);
        }
        if (runGit) {
            benchGit(corpus, optInt(opts, "iterations", 11), opts, out, javaStrict);
        }
        // Nothing is deleted: this corpus is the project's own workspace, and the
        // benchmark is a read-only measurement of it once the runs finish.
    }

    static void runScale(Map<String, String> opts, Map<String, Double> out,
                         boolean runJava, boolean runGit) throws IOException {
        var corpus = scaleCorpus(opts);
        System.out.println(corpusSection(corpus, optInt(opts, "iterations", 6)));
        Map<String, String> javaStrict = null;
        if (runJava) {
            var res = benchVeltis(corpus, optInt(opts, "iterations", 6),
                optInt(opts, "workers", 4));
            javaStrict = res.strictOutput;
            printVeltis(corpus, res, out);
        }
        if (runGit) {
            benchGit(corpus, optInt(opts, "iterations", 6), opts, out, javaStrict);
        }
        PatchBench.deleteRecursively(corpus.workspace.projectDirectory());
    }

    private static String corpusSection(Corpus c, int iterations) {
        return "\n=== corpus: " + c.label + " | " + c.patchCount + " patches | "
            + c.targets.size() + " target files | " + iterations + " iterations ===";
    }

    /** One Veltis run: restore (untimed) -> discover + apply (timed) -> verify (untimed). */
    static final class RunResult {
        /** Per iteration: {@code [discovery, parse, match, io, total, prep]} in ms. */
        final List<double[]> phases = new ArrayList<>();
        /** Per iteration: {@code [read, written, changed, applied, hunks, unchanged]}. */
        final List<int[]> counters = new ArrayList<>();
        final List<Double> alloc = new ArrayList<>();
        long gcCount;
        long gcMs;
        boolean verified = true;
        int verifyFailures;
        Map<String, String> strictOutput;
    }

    static RunResult benchVeltis(Corpus c, int iterations, int workers) throws IOException {
        var res = new RunResult();
        var gcBefore = gcCount();
        var gcTimeBefore = gcMs();
        Map<String, String> pinned = null;

        for (int i = 0; i < iterations; i++) {
            var alloc0 = allocatedBytes();
            long prep0 = System.nanoTime();
            c.restore();
            double prep = (System.nanoTime() - prep0) / 1e6;

            var stats = new PatchStats();
            long t0 = System.nanoTime();
            var patches = PatchDiscovery.discover(c.patchesRoot, "26.3", stats);
            // The patcher returns its own counters, so the two halves of the run
            // have to be folded together before anything is reported.
            stats.merge(new VeltisPatcher(workers, "26.3").apply(c.workspace, patches));
            long t1 = System.nanoTime();

            // Verification (untimed): byte-strict determinism run-to-run, plus a
            // match against the oracle for the files the oracle knows about.
            var h = c.hashes();
            if (pinned == null) {
                pinned = h;
            } else {
                for (var target : c.targets) {
                    if (!pinned.get(target).equals(h.get(target))) {
                        res.verified = false;
                        res.verifyFailures++;
                    }
                }
            }
            for (var target : c.targets) {
                var oracle = c.expected.get(target);
                if (oracle != null && !oracle.equals(h.get(target))) {
                    res.verified = false;
                    res.verifyFailures++;
                }
            }
            res.strictOutput = h;

            res.phases.add(new double[] {
                stats.discoveryNanos / 1e6,
                stats.parseNanos / 1e6,
                stats.matchNanos / 1e6,
                (stats.readNanos + stats.writeNanos) / 1e6,
                (t1 - t0) / 1e6,
                prep,
            });
            res.counters.add(new int[] {
                stats.filesRead, stats.filesWritten, stats.filesChanged,
                stats.patchesApplied, stats.hunksParsed, stats.filesUnchanged,
            });
            var alloc1 = allocatedBytes();
            res.alloc.add(alloc0 >= 0 && alloc1 >= alloc0 ? (alloc1 - alloc0) / 1e6 : -1.0);
        }
        res.gcCount = gcCount() - gcBefore;
        res.gcMs = gcMs() - gcTimeBefore;
        return res;
    }

    static void printVeltis(Corpus c, RunResult r, Map<String, Double> out) {
        var prefix = c.label;
        var cold = r.phases.get(0);
        var warm = r.phases.subList(Math.min(1, r.phases.size() - 1), r.phases.size());
        var warmMinTotal = warm.stream().mapToDouble(p -> p[4]).min().orElse(cold[4]);
        var avg = avgPhases(warm);
        var counters = r.counters.get(r.counters.size() - 1);

        System.out.println("""
            phase             cold       warm min    warm avg
            discovery       %8.3f ms   %8.3f ms   %8.3f ms
            parsing         %8.3f ms   %8.3f ms   %8.3f ms
            application     %8.3f ms   %8.3f ms   %8.3f ms
            I/O             %8.3f ms   %8.3f ms   %8.3f ms
            total           %8.3f ms   %8.3f ms   %8.3f ms""".formatted(
            cold[0], minCol(r.phases, 0), avg[0],
            cold[1], minCol(r.phases, 1), avg[1],
            cold[2], minCol(r.phases, 2), avg[2],
            cold[3], minCol(r.phases, 3), avg[3],
            cold[4], warmMinTotal, avg[4]));
        System.out.println("input preparation (excluded): " + fmt(avg[5]) + " ms/run"
            + "   | phases are summed across parallel workers");
        System.out.printf(
            "counters: %d patches applied, %d hunks, %d files read, %d written, %d changed,"
                + " %d unchanged (not rewritten) per run%n",
            counters[3], counters[4], counters[0], counters[1], counters[2], counters[5]);
        System.out.println(memoryLine(r));
        System.out.println(r.verified
            ? "verification: OK - byte-identical across runs"
                + (!c.expected.isEmpty() ? " and matches the oracle" : "")
            : "verification: FAILED on " + r.verifyFailures + " mismatch(es)");

        put(out, prefix + ".cold.discovery.ms", cold[0]);
        put(out, prefix + ".cold.parsing.ms", cold[1]);
        put(out, prefix + ".cold.application.ms", cold[2]);
        put(out, prefix + ".cold.io.ms", cold[3]);
        put(out, prefix + ".cold.total.ms", cold[4]);
        put(out, prefix + ".warm.discovery.ms", avg[0]);
        put(out, prefix + ".warm.parsing.ms", avg[1]);
        put(out, prefix + ".warm.application.ms", avg[2]);
        put(out, prefix + ".warm.io.ms", avg[3]);
        put(out, prefix + ".warm.total.ms", avg[4]);
        put(out, prefix + ".warm.min.total.ms", warmMinTotal);
        put(out, prefix + ".prep.ms", avg[5]);
        put(out, prefix + ".patches", counters[3]);
        put(out, prefix + ".files.read", counters[0]);
        put(out, prefix + ".files.written", counters[1]);
        put(out, prefix + ".files.unchanged", counters[5]);
        var alloc = r.alloc.stream().filter(v -> v >= 0)
            .mapToDouble(Double::doubleValue).average().orElse(-1);
        if (alloc >= 0) {
            put(out, prefix + ".alloc.mb", alloc / 1024.0);
        }
        System.out.println();
    }

    private static String memoryLine(RunResult r) {
        System.gc();
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        var rt = Runtime.getRuntime();
        var heap = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        var alloc = r.alloc.stream().filter(v -> v >= 0)
            .mapToDouble(Double::doubleValue).average().orElse(-1);
        return "memory: heap after GC %.1f MB | allocated per run %s | GC %d collections / %d ms"
            .formatted(heap, alloc >= 0 ? String.format("%.1f MB", alloc / 1024.0) : "n/a",
                r.gcCount, r.gcMs);
    }

    // ------------------------------------------------------------------
    // git comparison (same corpus, same machine, same files)
    // ------------------------------------------------------------------

    static void benchGit(Corpus c, int iterations, Map<String, String> opts,
                         Map<String, Double> out, Map<String, String> javaStrict)
            throws IOException {
        if (!gitAvailable()) {
            System.out.println("git not found on PATH - skipping git comparison");
            return;
        }
        var patchFiles = c.patchFiles();
        if (patchFiles.isEmpty()) {
            return;
        }
        var perPatch = opts.containsKey("git-per-patch");
        git(c.patched, null, "init", "-q");
        byte[] batchInput = null;
        if (!perPatch) {
            var buf = new java.io.ByteArrayOutputStream(1 << 16);
            for (var p : patchFiles) {
                var patchBytes = Files.readAllBytes(p);
                buf.write(patchBytes);
                if (patchBytes.length == 0 || patchBytes[patchBytes.length - 1] != '\n') {
                    buf.write('\n');
                }
            }
            batchInput = buf.toByteArray();
        }
        var totals = new ArrayList<double[]>();
        Map<String, String> pinned = null;
        boolean verified = true;

        for (int i = 0; i < iterations; i++) {
            c.restore();
            long t0 = System.nanoTime();
            int rc;
            if (perPatch) {
                rc = 0;
                for (var p : patchFiles) {
                    // Mirrors the build-time flow: reverse-check first, then apply.
                    git(c.patched, null, "apply", "--check", "--reverse", p.toString());
                    var apply = git(c.patched, null, "apply", p.toString());
                    if (apply != 0) {
                        rc = apply;
                    }
                }
            } else {
                rc = git(c.patched, batchInput, "apply", "-");
            }
            long t1 = System.nanoTime();
            if (rc != 0) {
                System.out.println("git apply failed (exit " + rc + ")");
                return;
            }
            var h = c.hashes();
            if (javaStrict != null) {
                // Strongest check: git output must be byte-identical to ours.
                for (var target : c.targets) {
                    if (!javaStrict.get(target).equals(h.get(target))) {
                        verified = false;
                    }
                }
            } else if (pinned == null) {
                pinned = h;
            } else {
                for (var target : c.targets) {
                    if (!pinned.get(target).equals(h.get(target))) {
                        verified = false;
                    }
                }
            }
            totals.add(new double[] {(t1 - t0) / 1e6});
        }

        var avg = totals.stream().skip(1).mapToDouble(t -> t[0])
            .average().orElse(totals.get(0)[0]);
        var check = javaStrict != null ? "byte-identical to Veltis output"
            : (!c.expected.isEmpty() ? "matches oracle" : "deterministic across runs");
        System.out.printf(
            "git apply%s: total %.3f ms avg of %d runs | output %s%n",
            perPatch ? " (2 processes per patch)" : " (1 process, all patches)",
            avg, totals.size(), verified ? check : "MISMATCH");
        put(out, c.label + ".git." + (perPatch ? "perpatch" : "batch") + ".total.ms", avg);

        var veltis = out.get(c.label + ".warm.total.ms");
        if (veltis != null && veltis > 0) {
            System.out.printf(
                "comparison (same corpus): Veltis %.3f ms vs git apply %.3f ms"
                    + " -> git is %.1fx the Veltis time%n", veltis, avg, avg / veltis);
        }
        System.out.println();
    }

    private static boolean gitAvailable() {
        try {
            var p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int git(Path workDir, byte[] stdin, String... args) {
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(workDir.toString());
            cmd.add("-c");
            cmd.add("core.autocrlf=false");
            cmd.addAll(List.of(args));
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var out = p.getOutputStream()) {
                if (stdin != null) {
                    out.write(stdin);
                }
            }
            p.getInputStream().readAllBytes();
            return p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Baseline save / compare
    // ------------------------------------------------------------------

    private static void saveMetrics(Map<String, Double> metrics, Path file) throws IOException {
        var props = new Properties();
        metrics.forEach((k, v) -> props.setProperty(k, String.format("%.4f", v)));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "VeltisMC patch-engine benchmark");
        }
        System.out.println("baseline saved to " + file.toAbsolutePath());
    }

    private static void compareMetrics(Path baselineFile, Map<String, Double> current)
            throws IOException {
        var baseline = new Properties();
        try (var in = Files.newBufferedReader(baselineFile, StandardCharsets.UTF_8)) {
            baseline.load(in);
        }
        System.out.printf("%n%-42s %12s %12s %10s%n", "metric", "baseline", "current", "change");
        System.out.println("-".repeat(78));
        var keys = new ArrayList<>(baseline.stringPropertyNames());
        for (var k : current.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        java.util.Collections.sort(keys);
        for (var k : keys) {
            var b = baseline.containsKey(k) ? Double.parseDouble(baseline.getProperty(k))
                : Double.NaN;
            var c = current.getOrDefault(k, Double.NaN);
            var delta = (Double.isNaN(b) || b == 0) ? Double.NaN : (c - b) / b * 100.0;
            System.out.printf("%-42s %12s %12s %9s%%%n", k, fmt(b), fmt(c),
                Double.isNaN(delta) ? "n/a" : (delta > 0 ? "+" : "") + fmt(delta));
        }
        for (var k : List.of("patch-only.warm.total.ms", "patch-only.cold.total.ms",
                "scale.warm.total.ms", "scale.cold.total.ms")) {
            var b = baseline.getProperty(k);
            var c = current.get(k);
            if (b != null && c != null && Double.parseDouble(b) > 0) {
                System.out.printf("Improvement (%s): %.1f%%%n", k,
                    (Double.parseDouble(b) - c) / Double.parseDouble(b) * 100.0);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static double[] avgPhases(List<double[]> runs) {
        var avg = new double[6];
        if (runs.isEmpty()) {
            return avg;
        }
        for (var r : runs) {
            for (int i = 0; i < 6; i++) {
                avg[i] += r[i];
            }
        }
        for (int i = 0; i < 6; i++) {
            avg[i] /= runs.size();
        }
        return avg;
    }

    private static double minCol(List<double[]> runs, int col) {
        return runs.stream().mapToDouble(r -> r[col]).min().orElse(Double.NaN);
    }

    private static void put(Map<String, Double> out, String key, double value) {
        out.put(key, value);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.3f", v);
    }

    static String sha256(byte[] bytes) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }

    private static long gcMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    /** Approximate allocation of all live threads (n/a when the JVM forbids it). */
    private static long allocatedBytes() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean tmx
                    && tmx.isThreadAllocatedMemorySupported()) {
                long sum = 0;
                boolean any = false;
                for (var id : tmx.getAllThreadIds()) {
                    long b = tmx.getThreadAllocatedBytes(id);
                    if (b > 0) {
                        sum += b;
                        any = true;
                    }
                }
                return any ? sum : -1;
            }
        } catch (Throwable ignored) {
            // Not supported on this JVM; allocation numbers are simply omitted.
        }
        return -1;
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp directory is harmless.
                }
            });
        }
    }
}
