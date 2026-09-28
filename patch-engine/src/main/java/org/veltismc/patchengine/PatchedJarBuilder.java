package org.veltismc.patchengine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Orchestrates the complete runtime patching pipeline:
 * 1. Download vanilla jar from Mojang
 * 2. Decompile it
 * 3. Apply patches
 * 4. Compile patched sources
 * 5. Package into versioned jar
 */
public class PatchedJarBuilder {

    private final PatchEngineConfig config;
    private final VanillaJarDownloader downloader;
    private final RuntimeDecompiler decompiler;
    private final RuntimePatchApplier patchApplier;
    private PrintStream log;
    /** javac chatter: lint "notes" at DEBUG, real diagnostics at ERROR. */
    private static final org.apache.logging.log4j.Logger COMPILER_LOG =
        org.apache.logging.log4j.LogManager.getLogger("veltis.compiler");
    private final boolean keepBuildFiles;

    public interface PrintStream {
        void info(String msg);
        void warn(String msg);
        void error(String msg);
    }

    public PatchedJarBuilder(PatchEngineConfig config) {
        this(config, new VanillaJarDownloader(), new RuntimeDecompiler(), new RuntimePatchApplier(), 
            new DefaultPrintStream(), false);
    }

    public PatchedJarBuilder(PatchEngineConfig config, boolean keepBuildFiles) {
        this(config, new VanillaJarDownloader(), new RuntimeDecompiler(), new RuntimePatchApplier(), 
            new DefaultPrintStream(), keepBuildFiles);
    }

    private boolean forceRebuild;
    private boolean skipDecompile;
    private boolean skipCompile;

    public void setForceRebuild(boolean forceRebuild) {
        this.forceRebuild = forceRebuild;
    }

    public void setSkipDecompile(boolean skipDecompile) {
        this.skipDecompile = skipDecompile;
    }

    public void setSkipCompile(boolean skipCompile) {
        this.skipCompile = skipCompile;
    }

    public void setLog(PrintStream log) {
        this.log = log;
    }

    public PatchedJarBuilder(PatchEngineConfig config, VanillaJarDownloader downloader, 
                           RuntimeDecompiler decompiler, RuntimePatchApplier patchApplier,
                           PrintStream log) {
        this(config, downloader, decompiler, patchApplier, log, false);
    }

    public PatchedJarBuilder(PatchEngineConfig config, VanillaJarDownloader downloader, 
                           RuntimeDecompiler decompiler, RuntimePatchApplier patchApplier,
                           PrintStream log, boolean keepBuildFiles) {
        this.config = config;
        this.downloader = downloader;
        this.decompiler = decompiler;
        this.patchApplier = patchApplier;
        this.log = log;
        this.keepBuildFiles = keepBuildFiles;
    }

    /**
     * Simple reusable timing for build phases: the caller logs the phase
     * start, then {@link #done} logs the completion message with a
     * human-readable elapsed time ({@code 42ms} / {@code 8.421s}).
     */
    private static final class PhaseTimer {
        private final PrintStream log;
        private final long start = System.nanoTime();
        PhaseTimer(PrintStream log) { this.log = log; }
        void done(String completionMessage) {
            log.info(completionMessage + " (" + VeltisConsole.formatDuration(System.nanoTime() - start) + ")");
        }
    }

    /**
     * Builds the patched server jar, downloading from Mojang if necessary.
     *
     * @return the path to the built veltismc-server.jar
     * @throws PatchEngineException if any step fails
     */
    private static final String SOURCE_FINGERPRINTS = "source-fingerprints.sha256";
    private static final String COMPILED_CLASS_MANIFEST = "compiled-classes.txt";

    public Path build() throws PatchEngineException {
        var patchedJar = config.patchedServerJar();

        // Check cache validation (forceRebuild skips this check)
        var homeDir = config.homeDirectory();
        var version = config.minecraftVersion();
        var rebuildRequired = forceRebuild || CacheValidator.isRebuildRequired(homeDir, version);

        if (!rebuildRequired && Files.exists(patchedJar)) {
            log.info("Found cached VeltisMC server " + version);
            return patchedJar;
        }

        try {
            log.info("Building VeltisMC server " + version);
            var total = new PhaseTimer(log);

            // Step 1: Download vanilla jar (extracts from bundler if needed)
            var vanillaJar = downloadVanillaJar();

            // Step 2: Download Minecraft libraries
            downloadLibraries();

            // Step 3: Decompile vanilla jar with libraries on classpath
            boolean freshDecompile = true;
            List<String> changedPatches = List.of();
            if (!skipDecompile) {
                freshDecompile = decompileVanillaJar(vanillaJar);

                // Step 4: Apply patches to decompiled source
                changedPatches = applyPatches();

                // Step 5: Compile patched sources (incremental)
                if (!skipCompile) {
                    compilePatchesIncremental(vanillaJar, freshDecompile, changedPatches);
                } else {
                    log.info("Compilation skipped (--skip-compile)");
                }
            } else {
                log.info("Decompilation skipped (--skip-decompile)");
            }

            // Step 6: Package into final jar
            log.info("Packaging VeltisMC server");
            var packageTimer = new PhaseTimer(log);
            packagePatchedJar(vanillaJar);
            packageTimer.done("Packaging completed");

            // Step 7: Clean up temporary build files
            if (!keepBuildFiles) {
                cleanBuildOutput();
            }

            // Mark cache as valid
            CacheValidator.markCacheValid(homeDir, version);

            total.done("VeltisMC server build completed");
            return patchedJar;

        } catch (PatchEngineException e) {
            var homeDir2 = config.homeDirectory();
            CacheValidator.invalidateCache(homeDir2, config.minecraftVersion());
            throw e;
        } catch (Exception e) {
            var homeDir2 = config.homeDirectory();
            CacheValidator.invalidateCache(homeDir2, config.minecraftVersion());
            throw new PatchEngineException("Unexpected error during jar build", e);
        }
    }

    private Path downloadVanillaJar() throws PatchEngineException {
        var vanillaJar = config.vanillaServerJar();
        
        if (Files.exists(vanillaJar)) {
            log.info("Found cached Minecraft server " + config.minecraftVersion());
            return vanillaJar;
        }

        log.info("Downloading Minecraft server " + config.minecraftVersion());
        var timer = new PhaseTimer(log);
        var downloaded = downloader.download(config.minecraftVersion(), vanillaJar);
        timer.done("Minecraft server downloaded");
        return downloaded;
    }

    private boolean decompileVanillaJar(Path vanillaJar) throws PatchEngineException {
        var patchedSourceDir = config.patchedSourceDirectory();
        
        if (Files.exists(patchedSourceDir)) {
            log.info("Found cached decompiled Minecraft source");
            return false;
        }

        var libraries = discoverLibraryJars();

        log.info("Decompiling Minecraft server");
        var timer = new PhaseTimer(log);
        decompiler.decompile(vanillaJar, patchedSourceDir, libraries);
        timer.done("Minecraft server decompiled");
        return true;
    }

    private List<Path> discoverLibraryJars() {
        var jars = new ArrayList<Path>();
        var dirs = List.of(
            config.librariesDirectory(),
            Path.of(System.getProperty("user.dir")).resolve("ver")
                .resolve(config.minecraftVersion()).resolve("libraries")
        );
        for (var dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.filter(p -> p.toString().endsWith(".jar"))
                        .filter(Files::isRegularFile)
                        .forEach(jars::add);
                } catch (Exception ignored) {
                }
            }
        }
        return jars;
    }

    private List<String> applyPatches() throws PatchEngineException {
        var patchedSourceDir = config.patchedSourceDirectory();
        
        var patches = loadBundledPatches();
        if (patches.isEmpty()) {
            log.warn("No bundled patches found in classpath");
            return List.of();
        }

        log.info("Applying VeltisMC patches");
        var timer = new PhaseTimer(log);
        var changedFiles = patchApplier.applyPatches(patches, patchedSourceDir);
        timer.done("Applied " + patches.size() + " patches to " + changedFiles.size() + " files");
        return changedFiles;
    }

    private List<RuntimePatchApplier.PatchContent> loadBundledPatches() {
        return PatchDiscovery.discover(config.homeDirectory(), new PatchStats(), log::warn);
    }

    private static final HttpClient LIB_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private void downloadLibraries() throws PatchEngineException {
        try {
            var libDir = config.librariesDirectory();
            if (Files.isDirectory(libDir) && hasJarFiles(libDir)) {
                return;
            }

            var timer = new PhaseTimer(log);
            log.info("Checking Minecraft libraries");
            Files.createDirectories(libDir);
            var versionUrl = resolveLibraryVersionUrl();
            var downloaded = downloadLibrariesFromManifest(versionUrl, libDir);
            if (downloaded > 0) {
                timer.done("Minecraft libraries downloaded");
            } else {
                log.info("Found cached Minecraft libraries");
            }
        } catch (Exception e) {
            throw new PatchEngineException("Failed to download Minecraft libraries", e);
        }
    }

    private String resolveLibraryVersionUrl() throws Exception {
        var manifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
        var request = HttpRequest.newBuilder()
            .uri(URI.create(manifestUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        var response = LIB_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        var root = new Gson().fromJson(response.body(), JsonObject.class);
        var versions = root.getAsJsonArray("versions");
        for (var elem : versions) {
            var obj = elem.getAsJsonObject();
            if (obj.get("id").getAsString().equals(config.minecraftVersion())) {
                return obj.get("url").getAsString();
            }
        }
        throw new PatchEngineException("Version not found: " + config.minecraftVersion());
    }

    /** Returns how many library jars were actually downloaded (0 = all cached). */
    private int downloadLibrariesFromManifest(String versionUrl, Path libDir) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(versionUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        var response = LIB_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        var root = new Gson().fromJson(response.body(), JsonObject.class);
        var libraries = root.getAsJsonArray("libraries");
        if (libraries == null) return 0;

        var downloaded = 0;
        for (var elem : libraries) {
            var lib = elem.getAsJsonObject();
            var downloads = lib.getAsJsonObject("downloads");
            if (downloads == null) continue;
            var artifact = downloads.getAsJsonObject("artifact");
            if (artifact == null) continue;

            var path = artifact.get("path").getAsString();
            var url = artifact.get("url").getAsString();
            var target = libDir.resolve(path);
            if (Files.exists(target)) continue;

            Files.createDirectories(target.getParent());
            try {
                var dlReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
                var dlResp = LIB_CLIENT.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = dlResp.body()) {
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                }
                downloaded++;
            } catch (Exception e) {
                log.warn("Failed to download library " + path + ": " + e.getMessage());
            }
        }
        return downloaded;
    }

    private static boolean hasJarFiles(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".jar"));
        } catch (Exception e) {
            return false;
        }
    }

    private void compilePatchesIncremental(Path vanillaJar, boolean freshDecompile, List<String> changedPatches) throws PatchEngineException {
        var patchedSourceDir = config.patchedSourceDirectory();
        var classesDir = config.compiledClassesDirectory();
        var cacheDir = config.homeDirectory().resolve("versions").resolve(config.minecraftVersion()).resolve("patch-engine.cache");
        var fingerprintFile = cacheDir.resolve(SOURCE_FINGERPRINTS);
        var manifestFile = cacheDir.resolve(COMPILED_CLASS_MANIFEST);

        try {
            Files.createDirectories(classesDir);
            Files.createDirectories(cacheDir);

            // Load patches from classpath and find which files they touch
            var bundledPatches = loadBundledPatches();
            var allPatchTargets = findPatchedSourceFiles(bundledPatches, patchedSourceDir);

            if (allPatchTargets.isEmpty()) {
                log.warn("No patched source files found, skipping compilation");
                return;
            }

            // Compute fingerprints for each source file
            var currentFingerprints = computeSourceFingerprints(allPatchTargets);

            // Load previous fingerprints to determine what changed
            var previousFingerprints = loadFingerprints(fingerprintFile);
            var previouslyCompiled = loadCompiledClasses(manifestFile);

            var filesToCompile = new ArrayList<Path>();
            var classesToKeep = new HashSet<>(previouslyCompiled);

            if (freshDecompile || changedPatches != null) {
                // Full recompile needed
                filesToCompile.addAll(allPatchTargets);
                classesToKeep.clear();
            } else {
                // Incremental: only recompile changed sources
                for (var sourceFile : allPatchTargets) {
                    var relative = patchedSourceDir.relativize(sourceFile).toString().replace("\\", "/");
                    var currentHash = currentFingerprints.get(relative);
                    var previousHash = previousFingerprints.get(relative);

                    if (currentHash == null || !currentHash.equals(previousHash)) {
                        filesToCompile.add(sourceFile);
                        // Remove stale class entries
                        var classBase = relative.replace(".java", ".class");
                        classesToKeep.removeIf(c -> c.startsWith(classBase.replace(".class", "$"))
                            || c.equals(classBase));
                    }
                }
            }

            // Also scan for any files not tracked by patches (untracked source changes)
            var extraSources = findUntrackedSourceChanges(patchedSourceDir, previousFingerprints, allPatchTargets);
            if (!extraSources.isEmpty()) {
                filesToCompile.addAll(extraSources);
            }

            if (filesToCompile.isEmpty()) {
                log.info("No source changes, skipping compilation");
                // Still save fingerprints for tracking
                saveFullTreeFingerprints(patchedSourceDir, fingerprintFile, currentFingerprints);
                return;
            }

            var classpath = buildCompilationClasspath();
            if (classpath == null || classpath.isEmpty()) {
                log.warn("No classpath entries found, compilation may fail");
            }

            log.info("Compiling VeltisMC server");
            var timer = new PhaseTimer(log);
            compileJavaFiles(filesToCompile, classesDir, classpath);
            timer.done("Compilation completed");

            // Update class manifest
            var updatedCompiledClasses = new HashSet<>(classesToKeep);
            try (var walk = Files.walk(classesDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".class"))
                    .forEach(f -> {
                        var name = classesDir.relativize(f).toString().replace("\\", "/");
                        updatedCompiledClasses.add(name);
                    });
            }
            saveCompiledClasses(manifestFile, updatedCompiledClasses);

            // Save fingerprints
            for (var sourceFile : filesToCompile) {
                var relative = patchedSourceDir.relativize(sourceFile).toString().replace("\\", "/");
                currentFingerprints.put(relative, computeFileHash(sourceFile));
            }
            saveFullTreeFingerprints(patchedSourceDir, fingerprintFile, currentFingerprints);

        } catch (Exception e) {
            throw new PatchEngineException("Failed to compile patches", e);
        }
    }

    private Map<String, String> computeSourceFingerprints(List<Path> sourceFiles) {
        var fingerprints = new HashMap<String, String>();
        for (var file : sourceFiles) {
            var relative = config.patchedSourceDirectory().relativize(file).toString().replace("\\", "/");
            fingerprints.put(relative, computeFileHash(file));
        }
        return fingerprints;
    }

    private String computeFileHash(Path file) {
        try {
            var digester = MessageDigest.getInstance("SHA-256");
            digester.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> loadFingerprints(Path file) {
        var map = new HashMap<String, String>();
        try {
            if (Files.isRegularFile(file)) {
                for (var line : Files.readAllLines(file)) {
                    var parts = line.split(" ", 2);
                    if (parts.length == 2) {
                        map.put(parts[1], parts[0]);
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private void saveFingerprints(Path file, Map<String, String> fingerprints) {
        try {
            var lines = new ArrayList<String>();
            for (var entry : fingerprints.entrySet()) {
                lines.add(entry.getValue() + " " + entry.getKey());
            }
            lines.sort(String::compareTo);
            Files.write(file, lines);
        } catch (Exception ignored) {}
    }

    private java.util.HashSet<String> loadCompiledClasses(Path file) {
        var set = new java.util.HashSet<String>();
        try {
            if (Files.isRegularFile(file)) {
                set.addAll(Files.readAllLines(file));
            }
        } catch (Exception ignored) {}
        return set;
    }

    private void saveCompiledClasses(Path file, java.util.HashSet<String> classes) {
        try {
            var sorted = new ArrayList<>(classes);
            sorted.sort(String::compareTo);
            Files.write(file, sorted);
        } catch (Exception ignored) {}
    }

    private void saveFullTreeFingerprints(Path sourceDir, Path fingerprintFile, Map<String, String> patchFingerprints) {
        // The fingerprint file doubles as the untracked-change baseline: it must
        // cover the whole source tree, not just patch targets, otherwise every
        // other source looks "untracked" on the next build and triggers a full
        // recompile of the decompiled tree (which fails on decompiler noise).
        var all = new HashMap<>(patchFingerprints);
        try (var walk = Files.walk(sourceDir)) {
            walk.filter(Files::isRegularFile)
                .filter(f -> f.toString().endsWith(".java"))
                .forEach(f -> {
                    var relative = sourceDir.relativize(f).toString().replace("\\", "/");
                    all.put(relative, computeFileHash(f));
                });
        } catch (Exception ignored) {}
        saveFingerprints(fingerprintFile, all);
    }

    private List<Path> findUntrackedSourceChanges(Path sourceDir, Map<String, String> previousFingerprints, List<Path> patchTargets) {
        var changed = new ArrayList<Path>();
        try {
            if (!Files.isDirectory(sourceDir)) return changed;
            // Guard: only scan against a full-tree baseline. A patch-only
            // fingerprint file (e.g. from a pre-baseline build) would make
            // every other source look untracked and trigger a full recompile.
            if (previousFingerprints.size() < 100) return changed;
            var tracked = new HashSet<String>();
            for (var target : patchTargets) {
                tracked.add(sourceDir.relativize(target).toString().replace("\\", "/"));
            }
            try (var walk = Files.walk(sourceDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(f -> {
                        var relative = sourceDir.relativize(f).toString().replace("\\", "/");
                        if (!tracked.contains(relative) && !previousFingerprints.containsKey(relative)) {
                            changed.add(f);
                        }
                    });
            }
        } catch (Exception ignored) {}
        return changed;
    }



    private static final Pattern PATCH_SRC_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

    private List<Path> findPatchedSourceFiles(List<RuntimePatchApplier.PatchContent> patches, Path sourceDir) {
        var patchedFiles = new ArrayList<Path>();
        for (var patch : patches) {
            for (var line : patch.lines()) {
                var m = PATCH_SRC_HEADER.matcher(line);
                if (m.matches()) {
                    var relativePath = m.group(1).replace('/', File.separatorChar);
                    var targetFile = sourceDir.resolve(relativePath);
                    if (Files.isRegularFile(targetFile)) {
                        patchedFiles.add(targetFile);
                    }
                    break;
                }
            }
        }
        return patchedFiles;
    }

    private List<String> buildCompilationClasspath() throws Exception {
        var entries = new ArrayList<String>();

        // 1. Original vanilla jar — provides all unchanged Minecraft classes
        var vanillaJar = config.vanillaServerJar();
        if (Files.isRegularFile(vanillaJar)) {
            entries.add(vanillaJar.toAbsolutePath().toString());
        }

        // 2. User-home libraries directory
        var libDir = config.librariesDirectory();
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.walk(libDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().toString())
                    .forEach(entries::add);
            }
        }

        // 3. Repo libraries directory (ver/{version}/libraries/) as fallback
        var reposDir = Path.of(System.getProperty("user.dir"))
            .resolve("ver").resolve(config.minecraftVersion()).resolve("libraries");
        if (!reposDir.equals(libDir) && Files.isDirectory(reposDir)) {
            try (var stream = Files.walk(reposDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .map(p -> p.toAbsolutePath().toString())
                    .forEach(entries::add);
            }
        }

        return entries;
    }

    private void compileJavaFiles(List<Path> sourceFiles, Path outputDir, List<String> classpath) throws Exception {
        var args = new ArrayList<String>();
        args.add("-d");
        args.add(outputDir.toAbsolutePath().toString());
        args.add("-encoding");
        args.add("UTF-8");
        if (classpath != null && !classpath.isEmpty()) {
            args.add("-cp");
            args.add(String.join(File.pathSeparator, classpath));
        }
        sourceFiles.stream()
            .map(p -> p.toAbsolutePath().toString())
            .forEach(args::add);

        // Use a javac @argfile to avoid Windows command-line length limits
        // (huge classpaths with hundreds of Minecraft libraries overflow CreateProcess).
        var argFile = Files.createTempFile("veltismc-javac-", ".args");
        try {
            // Javac argfiles follow shell quoting rules: arguments containing
            // whitespace must be double-quoted, and inside double quotes a
            // backslash escapes the next character, so double the backslashes.
            var argFileLines = args.stream()
                .map(a -> a.matches(".*\\s.*")
                    ? "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    : a)
                .toList();
            Files.write(argFile, argFileLines);
            var cmd = new ArrayList<String>();
            cmd.add("javac");
            cmd.add("@" + argFile.toAbsolutePath());
            var process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)  // javac reports notes/errors on stderr
                .start();
            // Drain javac's output concurrently instead of inheriting stdio:
            // on success it is only deprecation/unchecked "Note:" chatter
            // (debug), on failure it is the diagnostics explaining why
            // (error). Raw output would bypass the single log format.
            var output = new StringBuilder();
            var drain = new Thread(() -> {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // Process ended; nothing left to read.
                }
            }, "veltis-javac-output");
            drain.setDaemon(true);
            drain.start();
            var exitCode = process.waitFor();
            drain.join(5000);
            String text;
            synchronized (output) {
                text = output.toString().stripTrailing();
            }
            if (exitCode != 0) {
                if (!text.isEmpty()) COMPILER_LOG.error("javac diagnostics:\n{}", text);
                throw new PatchEngineException("Javac compilation failed with exit code " + exitCode);
            }
            if (!text.isEmpty()) COMPILER_LOG.debug("javac output:\n{}", text);
        } finally {
            Files.deleteIfExists(argFile);
        }
    }

    private void packagePatchedJar(Path vanillaJar) throws PatchEngineException {
        var patchedJar = config.patchedServerJar();
        var classesDir = config.compiledClassesDirectory();

        try {
            Files.createDirectories(patchedJar.getParent());

            // Collect compiled class names first so they override vanilla entries
            var seen = new HashSet<String>();
            if (Files.exists(classesDir)) {
                try (var stream = Files.walk(classesDir)) {
                    stream.filter(Files::isRegularFile).forEach(file -> {
                        var name = classesDir.relativize(file).toString().replace("\\", "/");
                        seen.add(name);
                    });
                }
            }

            try (var jarOut = new JarOutputStream(Files.newOutputStream(patchedJar))) {
                // Write manifest first (vanilla manifest is skipped below, and
                // `java -jar` requires a Main-Class entry).
                var manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "net.minecraft.server.Main");
                jarOut.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(jarOut);
                jarOut.closeEntry();

                // Copy vanilla jar entries, skipping overridden classes
                try (var jarIn = new JarFile(vanillaJar.toFile())) {
                    jarIn.entries().asIterator().forEachRemaining(entry -> {
                        try {
                            var name = entry.getName();
                            if (!name.startsWith("META-INF/") && seen.add(name)) {
                                jarOut.putNextEntry(new ZipEntry(name));
                                jarIn.getInputStream(entry).transferTo(jarOut);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }

                // Add compiled classes
                if (Files.exists(classesDir)) {
                    try (var stream = Files.walk(classesDir)) {
                        stream.filter(Files::isRegularFile).forEach(file -> {
                            try {
                                var name = classesDir.relativize(file).toString().replace("\\", "/");
                                if (seen.contains(name)) {
                                    jarOut.putNextEntry(new ZipEntry(name));
                                    Files.copy(file, jarOut);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                }
            }

        } catch (Exception e) {
            throw new PatchEngineException("Failed to package patched jar", e);
        }
    }

    private void cleanBuildOutput() {
        var sourceDir = config.patchedSourceDirectory();
        var classesDir = config.compiledClassesDirectory();
        try {
            if (Files.exists(sourceDir)) {
                try (var walk = Files.walk(sourceDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean source directory: " + e.getMessage());
        }
        try {
            if (Files.exists(classesDir)) {
                try (var walk = Files.walk(classesDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean classes directory: " + e.getMessage());
        }
    }

    private static class DefaultPrintStream implements PrintStream {
        private final org.apache.logging.log4j.Logger logger =
            org.apache.logging.log4j.LogManager.getLogger(PatchedJarBuilder.class);

        @Override
        public void info(String msg) {
            logger.info(msg);
        }

        @Override
        public void warn(String msg) {
            logger.warn(msg);
        }

        @Override
        public void error(String msg) {
            logger.error(msg);
        }
    }
}
