package org.veltismc.patchengine;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
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
 *   mode:  patch-only | scale | git | all | e2e
 *   options:
 *     --iterations N        timed runs per corpus (default: patch-only 11, scale 6)
 *     --files N --lines N --patches N   scale corpus shape (default 300/400/600)
 *     --git-per-patch       time a git process pair per patch (build-time style)
 *     --save FILE           write metrics to FILE
 *     --compare FILE        print metrics next to a previously saved FILE
 *     --home DIR            (e2e) server home directory
 *     --keep-build-files --skip-decompile --skip-compile --no-force   (e2e)
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
            case "scale" -> runScale(repo, opts, all, true, false);
            case "git" -> {
                runReal(repo, opts, all, false, true);
                runScale(repo, opts, all, false, true);
            }
            case "all" -> {
                runReal(repo, opts, all, true, true);
                runScale(repo, opts, all, true, true);
            }
            case "e2e" -> runE2E(repo, opts);
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
              mode: patch-only | scale | git | all | e2e
              --iterations N | --files N | --lines N | --patches N
              --git-per-patch | --save FILE | --compare FILE
              e2e: --home DIR [--keep-build-files] [--skip-decompile] [--skip-compile] [--no-force]""");
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

    /** A prepared input: pristine sources, patch files, workspace, expected output. */
    static final class Corpus {
        final String label;
        final Path pristine;       // pristine copies of every target file (may be a real source tree)
        final Path workspace;      // patches are applied here
        final Path patchesDir;     // *.patch files
        final Path discoveryHome;  // home whose server/patches/features == patchesDir
        final List<String> targets;          // relative paths of patched files
        final Map<String, String> expected;  // rel path -> EOL-normalized sha256 of expected output (may be empty)
        final int patchCount;
        /** true = measure production discovery (discover(home)); false = fromDirectory(patchesDir). */
        final boolean productionDiscovery;

        Corpus(String label, Path pristine, Path workspace, Path patchesDir,
               Path discoveryHome, List<String> targets, Map<String, String> expected,
               int patchCount, boolean productionDiscovery) {
            this.label = label;
            this.pristine = pristine;
            this.workspace = workspace;
            this.patchesDir = patchesDir;
            this.discoveryHome = discoveryHome;
            this.targets = targets;
            this.expected = expected;
            this.patchCount = patchCount;
            this.productionDiscovery = productionDiscovery;
        }

        /** Untimed: puts pristine inputs back into the workspace. */
        void restore() throws IOException {
            for (var target : targets) {
                var src = pristine.resolve(target);
                var dst = workspace.resolve(target);
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        /** sha256 (strict bytes) and sha256 (CR removed) of every target in the workspace. */
        Hashes hashes() throws IOException {
            var strict = new TreeMap<String, String>();
            var normalized = new TreeMap<String, String>();
            for (var target : targets) {
                var f = workspace.resolve(target);
                if (!Files.isRegularFile(f)) {
                    strict.put(target, "<missing>");
                    normalized.put(target, "<missing>");
                    continue;
                }
                var bytes = Files.readAllBytes(f);
                strict.put(target, sha256(bytes));
                normalized.put(target, sha256(normalizeEol(bytes)));
            }
            return new Hashes(strict, normalized);
        }

        List<Path> patchFiles() throws IOException {
            try (var s = Files.list(patchesDir)) {
                return s.filter(p -> p.getFileName().toString().endsWith(".patch"))
                    .sorted()
                    .toList();
            }
        }
    }

    /** sha256 of workspace targets: exact bytes, and with CR removed for oracle comparison. */
    record Hashes(Map<String, String> strict, Map<String, String> normalized) {}

    /** Removes CR bytes so LF and CRLF variants of the same content hash identically. */
    static byte[] normalizeEol(byte[] bytes) {
        var out = new byte[bytes.length];
        int n = 0;
        for (var b : bytes) {
            if (b != (byte) 13) {
                out[n++] = b;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Real corpus: server/patches/features against the decompiled 26.3 workspace. */
    static Corpus realCorpus(Path repo) throws IOException {
        var verRoot = repo.resolve("ver");
        Path verDir = null;
        if (Files.isDirectory(verRoot)) {
            try (var s = Files.list(verRoot)) {
                verDir = s.filter(Files::isDirectory).sorted().findFirst().orElse(null);
            }
        }
        if (verDir == null) {
            throw new IOException("ver/<version> missing - run ./gradlew downloadMinecraft decompileMinecraft first");
        }
        var source = verDir.resolve("minecraft-source");
        var patched = verDir.resolve("patched-source");
        var patchesDir = repo.resolve("server").resolve("patches").resolve("features");
        if (!Files.isDirectory(source)) {
            throw new IOException(source + " missing - run ./gradlew decompileMinecraft first");
        }

        var stats = new PatchStats();
        var patches = PatchDiscovery.fromDirectory(patchesDir, stats);
        if (patches.isEmpty()) {
            throw new IOException("no patches in " + patchesDir);
        }
        var targets = new ArrayList<String>();
        for (var p : patches) {
            var target = resolveTarget(p.lines());
            if (target != null && !targets.contains(target)) {
                targets.add(target);
            }
        }

        var tmp = Files.createTempDirectory("veltis-bench");
        var workspace = tmp.resolve("workspace");
        Files.createDirectories(workspace);

        var expected = new LinkedHashMap<String, String>();
        for (var t : targets) {
            var f = patched.resolve(t);
            if (Files.isRegularFile(f)) {
                // The oracle was produced by build-time `git apply` on Windows, so it may
                // carry CRLF; compare line-ending-normalized content (see Hashes).
                expected.put(t, sha256(normalizeEol(Files.readAllBytes(f))));
            }
        }
        return new Corpus("patch-only", source, workspace, patchesDir, repo,
            targets, expected, patches.size(), true);
    }

    /** Scale corpus: generated files/patches, expected output computed by the generator. */
    static Corpus scaleCorpus(Map<String, String> opts) throws IOException {
        var files = optInt(opts, "files", 300);
        var lines = optInt(opts, "lines", 400);
        var patches = optInt(opts, "patches", 600);

        var tmp = Files.createTempDirectory("veltis-bench-scale");
        var pristine = tmp.resolve("src");
        var workspace = tmp.resolve("ws");
        var home = tmp.resolve("home");
        var patchesDir = home.resolve("server").resolve("patches").resolve("features");
        Files.createDirectories(pristine);
        Files.createDirectories(workspace);
        Files.createDirectories(patchesDir);

        // Current content per file; evolves as patch files are generated in application order.
        var contents = new ArrayList<List<String>>();
        for (int f = 0; f < files; f++) {
            var content = new ArrayList<String>(lines);
            content.add("package gen;");
            content.add("// generated file " + f);
            content.add("class Gen" + f + " {");
            for (int i = 3; i < lines - 1; i++) {
                content.add("    int v" + i + " = " + i + ";");
            }
            content.add("}");
            contents.add(content);
            writeLines(pristine.resolve("gen").resolve("Gen" + f + ".java"), content);
        }

        var targets = new ArrayList<String>();
        for (int f = 0; f < files; f++) {
            targets.add("gen/Gen" + f + ".java");
        }

        for (int p = 0; p < patches; p++) {
            var f = p % files;
            var cur = contents.get(f);
            var pos = 8 + ((f * 7 + p * 13) % Math.max(1, cur.size() - 20));
            var rel = "gen/Gen" + f + ".java";

            var before = List.copyOf(cur);
            var removed = cur.get(pos);
            var added = List.of(removed, removed + " // patched " + p, removed + " // end " + p);

            var patchName = String.format("%03d-Scale-P%03d.patch", p, p);
            var sb = new StringBuilder();
            sb.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n');
            sb.append("--- a/").append(rel).append('\n');
            sb.append("+++ b/").append(rel).append('\n');
            var oldStart = pos - 2;  // 1-based line number of hunk start
            sb.append("@@ -").append(oldStart).append(",7 +").append(oldStart).append(",9 @@\n");
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
            Files.writeString(patchesDir.resolve(patchName), sb.toString(), StandardCharsets.UTF_8);

            cur.remove(pos);
            cur.addAll(pos, added);
        }

        var expected = new LinkedHashMap<String, String>();
        // Expected output = the generator's final content, hashed from actual bytes.
        for (int f = 0; f < files; f++) {
            var tmpOut = Files.createTempFile("veltis-expected", ".java");
            writeLines(tmpOut, contents.get(f));
            expected.put(targets.get(f), sha256(Files.readAllBytes(tmpOut)));
            Files.deleteIfExists(tmpOut);
        }

        return new Corpus("scale", pristine, workspace, patchesDir, home,
            targets, expected, patches, false);
    }

    private static String resolveTarget(List<String> patchLines) {
        for (var line : patchLines) {
            if (line.startsWith("+++ b/")) {
                return line.substring(6);
            }
        }
        return null;
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
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
            var res = benchVeltis(corpus, optInt(opts, "iterations", 11));
            javaStrict = res.strictOutput;
            printVeltis(corpus, res, out);
        }
        if (runGit) {
            benchGit(corpus, optInt(opts, "iterations", 11), opts, out, javaStrict);
        }
        try {
            deleteRecursively(corpus.workspace.getParent());
        } catch (Exception ignored) {
        }
    }

    static void runScale(Path repo, Map<String, String> opts, Map<String, Double> out,
                         boolean runJava, boolean runGit) throws IOException {
        var corpus = scaleCorpus(opts);
        System.out.println(corpusSection(corpus, optInt(opts, "iterations", 6)));
        Map<String, String> javaStrict = null;
        if (runJava) {
            var res = benchVeltis(corpus, optInt(opts, "iterations", 6));
            javaStrict = res.strictOutput;
            printVeltis(corpus, res, out);
        }
        if (runGit) {
            benchGit(corpus, optInt(opts, "iterations", 6), opts, out, javaStrict);
        }
        try {
            deleteRecursively(corpus.workspace.getParent());
        } catch (Exception ignored) {
        }
    }

    private static String corpusSection(Corpus c, int iterations) {
        return "\n=== corpus: " + c.label + " | " + c.patchCount + " patches | "
            + c.targets.size() + " target files | " + iterations + " iterations ===";
    }

    /** One Veltis run: restore (untimed) -> discover + apply (timed) -> verify (untimed). */
    static final class RunResult {
        final List<double[]> phases = new ArrayList<>();   // [disc, parse, match, io, total, prep]
        final List<int[]> counters = new ArrayList<>();    // [read, written, changed, applied, hunks]
        final List<Double> alloc = new ArrayList<>();
        long gcCount;
        long gcMs;
        boolean verified = true;
        int verifyFailures;
        Map<String, String> strictOutput;   // exact bytes of the last run (for git cross-check)
    }

    static RunResult benchVeltis(Corpus c, int iterations) throws IOException {
        var res = new RunResult();
        var gcBefore = gcCount();
        var gcTimeBefore = gcMs();
        Map<String, String> pinnedStrict = null;

        for (int i = 0; i < iterations; i++) {
            var alloc0 = allocatedBytes();
            long prep0 = System.nanoTime();
            c.restore();
            double prep = (System.nanoTime() - prep0) / 1e6;

            var stats = new PatchStats();
            long t0 = System.nanoTime();
            var patches = c.productionDiscovery
                ? PatchDiscovery.discover(c.discoveryHome, stats)
                : PatchDiscovery.fromDirectory(c.patchesDir, stats);
            new RuntimePatchApplier().applyPatches(patches, c.workspace, stats);
            long t2 = System.nanoTime();

            // Verification (untimed): byte-strict determinism run-to-run, plus an
            // EOL-normalized match against the oracle (build-time git output may be CRLF).
            var h = c.hashes();
            if (pinnedStrict == null) {
                pinnedStrict = h.strict();
            } else {
                for (var target : c.targets) {
                    if (!pinnedStrict.get(target).equals(h.strict().get(target))) {
                        res.verified = false;
                        res.verifyFailures++;
                    }
                }
            }
            for (var target : c.targets) {
                var oracle = c.expected.get(target);
                if (oracle != null && !oracle.equals(h.normalized().get(target))) {
                    res.verified = false;
                    res.verifyFailures++;
                }
            }
            res.strictOutput = h.strict();

            res.phases.add(new double[] {
                stats.discoveryNanos / 1e6,
                stats.parseNanos / 1e6,
                stats.matchNanos / 1e6,
                (stats.readNanos + stats.writeNanos) / 1e6,
                (t2 - t0) / 1e6,
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
            "counters: %d patches applied, %d hunks, %d files read, %d written, %d changed, %d unchanged (not rewritten) per run%n",
            counters[3], counters[4], counters[0], counters[1], counters[2], counters[5]);
        System.out.println(memoryLine(r));
        System.out.println(r.verified
            ? "verification: OK - byte-identical across runs"
                + (!c.expected.isEmpty() ? " and matches the build-time oracle (EOL-normalized)" : "")
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
        put(out, prefix + ".patches", (double) counters[3]);
        put(out, prefix + ".files.read", (double) counters[0]);
        put(out, prefix + ".files.written", (double) counters[1]);
        put(out, prefix + ".files.unchanged", (double) counters[5]);
        var alloc = r.alloc.stream().filter(v -> v >= 0).mapToDouble(Double::doubleValue).average().orElse(-1);
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
        var alloc = r.alloc.stream().filter(v -> v >= 0).mapToDouble(Double::doubleValue).average().orElse(-1);
        return "memory: heap after GC %.1f MB | allocated per run %s | GC %d collections / %d ms".formatted(
            heap, alloc >= 0 ? String.format("%.1f MB", alloc / 1024.0) : "n/a",
            r.gcCount, r.gcMs);
    }

    // ------------------------------------------------------------------
    // git comparison (same corpus, same machine, same files)
    // ------------------------------------------------------------------

    static void benchGit(Corpus c, int iterations, Map<String, String> opts,
                         Map<String, Double> out, Map<String, String> javaStrict)
            throws IOException {
        if (opts.containsKey("no-git")) {
            return;
        }
        if (!gitAvailable()) {
            System.out.println("git not found on PATH - skipping git comparison");
            return;
        }
        var patchFiles = c.patchFiles();
        if (patchFiles.isEmpty()) {
            return;
        }
        var perPatch = opts.containsKey("git-per-patch");
        // git apply works on a working tree; initialise one (untimed) for parity with
        // the build-time flow, which always runs inside a repository.
        git(c.workspace, null, "init", "-q");
        // Batch mode feeds every patch through stdin: one process, no command-line
        // length limits, identical bytes to the files on disk.
        byte[] batchInput = null;
        if (!perPatch) {
            var buf = new java.io.ByteArrayOutputStream(1 << 16);
            for (var p : patchFiles) {
                var bytes = Files.readAllBytes(p);
                buf.write(bytes);
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
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
            try (var s = Files.list(c.patchesDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".patch")).sorted().toList();
            }
            long t1 = System.nanoTime();
            int rc;
            if (perPatch) {
                rc = 0;
                for (var p : patchFiles) {
                    // Mirrors the build-time flow: reverse-check first, then apply.
                    git(c.workspace, null, "apply", "--check", "--reverse", p.toString());
                    var apply = git(c.workspace, null, "apply", p.toString());
                    if (apply != 0) {
                        rc = apply;
                    }
                }
            } else {
                rc = git(c.workspace, batchInput, "apply", "-");
            }
            long t2 = System.nanoTime();
            if (rc != 0) {
                System.out.println("git apply failed (exit " + rc + ")");
                return;
            }
            var h = c.hashes();
            if (javaStrict != null) {
                // Strongest check: git output must be byte-identical to the Veltis output.
                for (var target : c.targets) {
                    if (!javaStrict.get(target).equals(h.strict().get(target))) {
                        verified = false;
                    }
                }
            } else if (c.expected.isEmpty()) {
                if (pinned == null) {
                    pinned = h.strict();
                } else {
                    for (var target : c.targets) {
                        if (!pinned.get(target).equals(h.strict().get(target))) {
                            verified = false;
                        }
                    }
                }
            } else {
                for (var target : c.targets) {
                    var oracle = c.expected.get(target);
                    if (oracle != null && !oracle.equals(h.normalized().get(target))) {
                        verified = false;
                    }
                }
            }
            totals.add(new double[] { (t1 - t0) / 1e6, (t2 - t0) / 1e6 });
        }

        var avgList = totals.stream().skip(1).mapToDouble(t -> t[1]).average()
            .orElse(totals.get(0)[1]);
        var avgListMs = totals.stream().skip(1).mapToDouble(t -> t[0]).average()
            .orElse(totals.get(0)[0]);
        var check = javaStrict != null ? "byte-identical to Veltis output"
            : (!c.expected.isEmpty() ? "matches oracle (EOL-normalized)" : "deterministic across runs");
        System.out.printf(
            "git apply%s: discovery(list) %.3f ms | total %.3f ms avg of %d runs | output %s%n",
            perPatch ? " (2 processes per patch)" : " (1 process, all patches)",
            avgListMs, avgList, totals.size(),
            verified ? check : "MISMATCH");
        put(out, c.label + ".git." + (perPatch ? "perpatch" : "batch") + ".discovery.ms", avgListMs);
        put(out, c.label + ".git." + (perPatch ? "perpatch" : "batch") + ".total.ms", avgList);

        if (out.containsKey(c.label + ".warm.total.ms")) {
            var veltis = out.get(c.label + ".warm.total.ms");
            var ratio = veltis <= 0 ? 0 : avgList / veltis;
            System.out.printf(
                "comparison (same corpus): Veltis %.3f ms vs git apply %.3f ms -> git is %.1fx the Veltis time%n",
                veltis, avgList, ratio);
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
    // End-to-end builder run
    // ------------------------------------------------------------------

    static void runE2E(Path repo, Map<String, String> opts) throws Exception {
        var home = Path.of(required(opts, "home")).toAbsolutePath().normalize();
        // Same default as VeltisLauncher; override with --version when needed.
        var version = opts.getOrDefault("version", "26.3");
        var patchesDir = repo.resolve("server").resolve("patches").resolve("features");
        var config = new PatchEngineConfig(version, home, patchesDir);
        var builder = new PatchedJarBuilder(config, opts.containsKey("keep-build-files"));
        builder.setLog(new PatchedJarBuilder.PrintStream() {
            @Override public void info(String msg) { System.out.println(msg); }
            @Override public void warn(String msg) { System.out.println("[WARN] " + msg); }
            @Override public void error(String msg) { System.err.println(msg); }
        });
        builder.setForceRebuild(!opts.containsKey("no-force"));
        if (opts.containsKey("skip-decompile")) builder.setSkipDecompile(true);
        if (opts.containsKey("skip-compile")) builder.setSkipCompile(true);

        var t0 = System.nanoTime();
        var jar = builder.build();
        var total = (System.nanoTime() - t0) / 1e6;
        System.out.printf("E2E wall time: %.1f ms -> %s%n", total, jar);
    }

    private static String required(Map<String, String> opts, String key) {
        var v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
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

    private static void compareMetrics(Path baselineFile, Map<String, Double> current) throws IOException {
        var baseline = new Properties();
        try (var in = Files.newBufferedReader(baselineFile, StandardCharsets.UTF_8)) {
            baseline.load(in);
        }
        System.out.printf("%n%-42s %12s %12s %10s%n", "metric", "baseline", "current", "change");
        System.out.println("-".repeat(78));
        var keys = new ArrayList<String>(baseline.stringPropertyNames());
        for (var k : current.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        java.util.Collections.sort(keys);
        for (var k : keys) {
            var b = baseline.containsKey(k) ? Double.parseDouble(baseline.getProperty(k)) : Double.NaN;
            var c = current.getOrDefault(k, Double.NaN);
            var delta = (Double.isNaN(b) || b == 0) ? Double.NaN : (c - b) / b * 100.0;
            System.out.printf("%-42s %12s %12s %9s%%%n", k, fmt(b), fmt(c),
                Double.isNaN(delta) ? "n/a" : (delta > 0 ? "+" : "") + fmt(delta));
        }
        for (var k : List.of("patch-only.warm.total.ms", "patch-only.cold.total.ms",
                             "scale.warm.total.ms", "scale.cold.total.ms")) {
            var b = baseline.getProperty(k);
            var c = current.get(k);
            if (b != null && c != null) {
                var bv = Double.parseDouble(b);
                if (bv > 0) {
                    System.out.printf("Improvement (%s): %.1f%%%n", k, (bv - c) / bv * 100.0);
                }
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
        }
        return -1;
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
