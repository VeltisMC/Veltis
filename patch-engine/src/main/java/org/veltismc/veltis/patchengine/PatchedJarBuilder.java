package org.veltismc.veltis.patchengine;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
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

    private static final class PhaseTimer {
    private PrintStream log;
        private final String name;
        private final Instant start;
        PhaseTimer(PrintStream log, String name) { this.log = log; this.name = name; this.start = Instant.now(); }
        void end() {
            var elapsed = Duration.between(start, Instant.now()).toMillis();
            log.info("  [Profile] " + name + ": " + elapsed + "ms");
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
            log.info("Cache valid, using existing patched jar: " + patchedJar);
            return patchedJar;
        }

        try {
            log.info("Starting patched jar build for version " + config.minecraftVersion());
            var totalStart = Instant.now();
            
            // Step 1: Download vanilla jar (extracts from bundler if needed)
            var t = new PhaseTimer(log, "Download");
            var vanillaJar = downloadVanillaJar();
            log.info("Vanilla jar: " + vanillaJar);
            t.end();

            // Step 2: Download Minecraft libraries
            downloadLibraries();

            // Step 3: Decompile vanilla jar with libraries on classpath
            boolean freshDecompile = true;
            List<String> changedPatches = List.of();
            if (!skipDecompile) {
                t = new PhaseTimer(log, "Decompile");
                freshDecompile = decompileVanillaJar(vanillaJar);
                t.end();

                // Step 4: Apply patches to decompiled source
                t = new PhaseTimer(log, "Patch");
                changedPatches = applyPatches();
                t.end();

                // Step 5: Compile patched sources (incremental)
                if (!skipCompile) {
                    t = new PhaseTimer(log, "Compile");
                    compilePatchesIncremental(vanillaJar, freshDecompile, changedPatches);
                    t.end();
                } else {
                    log.info("Compilation skipped (--skip-compile)");
                }
            } else {
                log.info("Decompilation skipped (--skip-decompile)");
            }

            // Step 6: Package into final jar
            t = new PhaseTimer(log, "Package");
            packagePatchedJar(vanillaJar);
            t.end();

            // Step 7: Clean up temporary build files
            if (!keepBuildFiles) {
                cleanBuildOutput();
            }

            var totalElapsed = Duration.between(totalStart, Instant.now()).toMillis();
            log.info("  [Profile] Total: " + totalElapsed + "ms");

            // Mark cache as valid
            CacheValidator.markCacheValid(homeDir, version);

            log.info("Successfully built patched jar: " + patchedJar);
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
            log.info("Vanilla jar already cached: " + vanillaJar);
            return vanillaJar;
        }

        log.info("Downloading vanilla server jar from Mojang...");
        return downloader.download(config.minecraftVersion(), vanillaJar);
    }

    private boolean decompileVanillaJar(Path vanillaJar) throws PatchEngineException {
        var patchedSourceDir = config.patchedSourceDirectory();
        
        if (Files.exists(patchedSourceDir)) {
            log.info("Decompiled sources already cached, skipping decompilation");
            return false;
        }

        var libraries = discoverLibraryJars();

        log.info("Decompiling vanilla jar" + (libraries.isEmpty() ? "" : " with " + libraries.size() + " libraries") + "...");
        decompiler.decompile(vanillaJar, patchedSourceDir, libraries);
        log.info("Decompilation complete: " + patchedSourceDir);
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

        log.info("Applying " + patches.size() + " patches...");
        var changedFiles = patchApplier.applyPatches(patches, patchedSourceDir);
        log.info("Patches applied successfully (" + changedFiles.size() + " files changed)");
        return changedFiles;
    }

    private List<RuntimePatchApplier.PatchContent> loadBundledPatches() {
        var patches = new LinkedHashMap<String, RuntimePatchApplier.PatchContent>();
        scanJarForPatches(patches);
        scanFilesystemForPatches(patches);
        scanClasspathForPatches(patches);
        var result = new ArrayList<>(patches.values());
        result.sort(Comparator.comparing(RuntimePatchApplier.PatchContent::name));
        log.info("Loaded " + result.size() + " patches");
        return result;
    }

    private void scanJarForPatches(Map<String, RuntimePatchApplier.PatchContent> patches) {
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
                        var lines = new BufferedReader(new InputStreamReader(jf.getInputStream(entry), StandardCharsets.UTF_8)).lines().toList();
                        patches.put(canonical, new RuntimePatchApplier.PatchContent(canonical, lines));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan JAR for patches: " + e.getMessage());
        }
    }

    private void scanFilesystemForPatches(Map<String, RuntimePatchApplier.PatchContent> patches) {
        var candidates = List.of(
            Path.of("patches", "server"),
            Path.of("patches", "features"),
            Path.of("server", "patches"),
            Path.of("server", "patches", "features"),
            Path.of(System.getProperty("user.dir"), "patches", "server"),
            Path.of(System.getProperty("user.dir"), "patches", "features"),
            Path.of(System.getProperty("user.dir"), "server", "patches"),
            Path.of(System.getProperty("user.dir"), "server", "patches", "features"),
            config.homeDirectory().resolve("patches").resolve("server"),
            config.homeDirectory().resolve("patches").resolve("features"),
            config.homeDirectory().resolve("server").resolve("patches"),
            config.homeDirectory().resolve("server").resolve("patches").resolve("features")
        );
        for (var dir : candidates) {
            if (!Files.isDirectory(dir)) continue;
            try (var walk = Files.walk(dir, 1)) {
                walk.filter(f -> f.toString().endsWith(".patch"))
                    .forEach(f -> {
                        var fileName = f.getFileName().toString();
                        var canonical = canonicalPatchName(fileName);
                        try {
                            var lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                            patches.putIfAbsent(canonical, new RuntimePatchApplier.PatchContent(canonical, lines));
                        } catch (Exception e) {
                            log.warn("Failed to read patch: " + f + ": " + e.getMessage());
                        }
                    });
            } catch (Exception e) {
                log.warn("Failed to scan " + dir + ": " + e.getMessage());
            }
        }
    }

    private void scanClasspathForPatches(Map<String, RuntimePatchApplier.PatchContent> patches) {
        var cl = PatchedJarBuilder.class.getClassLoader();
        for (var prefix : List.of("patches/server/", "patches/features/")) {
            try {
                var resources = cl.getResources(prefix);
                while (resources.hasMoreElements()) {
                    var url = resources.nextElement();
                    if (url == null) continue;
                    var file = new java.io.File(url.toURI());
                    if (file.isDirectory()) {
                        scanDirForPatches(file.toPath(), patches);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void scanDirForPatches(Path dir, Map<String, RuntimePatchApplier.PatchContent> patches) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir, 1)) {
            walk.filter(f -> f.toString().endsWith(".patch"))
                .forEach(f -> {
                    var fileName = f.getFileName().toString();
                    var canonical = canonicalPatchName(fileName);
                    try {
                        var lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                        patches.putIfAbsent(canonical, new RuntimePatchApplier.PatchContent(canonical, lines));
                    } catch (Exception e) {
                        log.warn("Failed to read patch: " + f + ": " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            log.warn("Failed to scan directory " + dir + ": " + e.getMessage());
        }
    }

    private static final HttpClient LIB_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private void downloadLibraries() throws PatchEngineException {
        try {
            var libDir = config.librariesDirectory();
            if (Files.isDirectory(libDir) && hasJarFiles(libDir)) {
                log.info("Libraries already cached: " + libDir);
                return;
            }

            log.info("Downloading Minecraft libraries...");
            Files.createDirectories(libDir);

            var versionUrl = resolveLibraryVersionUrl();
            downloadLibrariesFromManifest(versionUrl, libDir);
            log.info("Libraries downloaded to " + libDir);
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

    private void downloadLibrariesFromManifest(String versionUrl, Path libDir) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(versionUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        var response = LIB_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        var root = new Gson().fromJson(response.body(), JsonObject.class);
        var libraries = root.getAsJsonArray("libraries");
        if (libraries == null) return;

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
            } catch (Exception e) {
                log.warn("  Failed to download library " + path + ": " + e.getMessage());
            }
        }
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
                log.info("Full recompile (" + allPatchTargets.size() + " sources)");
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
                log.info("Incremental compile: " + filesToCompile.size() + " changed of " + allPatchTargets.size() + " total");
            }

            // Also scan for any files not tracked by patches (untracked source changes)
            var extraSources = findUntrackedSourceChanges(patchedSourceDir, previousFingerprints);
            if (!extraSources.isEmpty()) {
                filesToCompile.addAll(extraSources);
                log.info("Found " + extraSources.size() + " additional changed sources");
            }

            if (filesToCompile.isEmpty()) {
                log.info("No sources changed, skipping compilation");
                // Still save fingerprints for tracking
                saveFingerprints(fingerprintFile, currentFingerprints);
                return;
            }

            var classpath = buildCompilationClasspath();
            if (classpath == null || classpath.isEmpty()) {
                log.warn("No classpath entries found, compilation may fail");
            }

            log.info("Compiling " + filesToCompile.size() + " source files...");
            compileJavaFiles(filesToCompile, classesDir, classpath);

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
            saveFingerprints(fingerprintFile, currentFingerprints);

            log.info("Compilation complete: " + filesToCompile.size() + " files -> " + classesDir);

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

    private List<Path> findUntrackedSourceChanges(Path sourceDir, Map<String, String> previousFingerprints) {
        var changed = new ArrayList<Path>();
        try {
            if (!Files.isDirectory(sourceDir) || previousFingerprints.isEmpty()) return changed;
            try (var walk = Files.walk(sourceDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(f -> {
                        var relative = sourceDir.relativize(f).toString().replace("\\", "/");
                        if (!previousFingerprints.containsKey(relative)) {
                            changed.add(f);
                        }
                    });
            }
        } catch (Exception ignored) {}
        return changed;
    }



    private static final Pattern PATCH_SRC_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^\\d{3}-(.+)$");

    /** Strip leading numeric prefix (e.g. "003-Improve-Command-Logging.patch" → "Improve-Command-Logging.patch") */
    private static String canonicalPatchName(String fileName) {
        var m = NUMERIC_PREFIX.matcher(fileName);
        return m.matches() ? m.group(1) : fileName;
    }

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
        var sourceFileStrings = sourceFiles.stream()
            .map(p -> p.toAbsolutePath().toString())
            .toList();

        var cmd = new ArrayList<String>();
        cmd.add("javac");
        cmd.add("-d");
        cmd.add(outputDir.toAbsolutePath().toString());
        cmd.add("-encoding");
        cmd.add("UTF-8");
        if (classpath != null && !classpath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(String.join(File.pathSeparator, classpath));
        }
        cmd.addAll(sourceFileStrings);

        var pb = new ProcessBuilder(cmd)
            .inheritIO();

        var process = pb.start();
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new PatchEngineException("Javac compilation failed with exit code " + exitCode);
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

            log.info("Packaged patched jar: " + patchedJar);

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
                log.info("Cleaned temporary source directory: " + sourceDir);
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
                log.info("Cleaned temporary classes directory: " + classesDir);
            }
        } catch (Exception e) {
            log.warn("Failed to clean classes directory: " + e.getMessage());
        }
    }

    private static class DefaultPrintStream implements PrintStream {
        @Override
        public void info(String msg) {
            System.out.println("[INFO] " + msg);
        }

        @Override
        public void warn(String msg) {
            System.out.println("[WARN] " + msg);
        }

        @Override
        public void error(String msg) {
            System.err.println("[ERROR] " + msg);
        }
    }
}
