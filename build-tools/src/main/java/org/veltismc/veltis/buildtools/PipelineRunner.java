package org.veltismc.veltis.buildtools;

import org.veltismc.veltis.buildtools.context.BuildContext;
import org.veltismc.veltis.buildtools.decompile.DecompilerIntegration;
import org.veltismc.veltis.buildtools.decompile.VineflowerProvider;
import org.veltismc.veltis.buildtools.download.ServerDownloader;
import org.veltismc.veltis.buildtools.patch.GitPatchSystem;
import org.veltismc.veltis.buildtools.patch.PatchSystem;

import org.veltismc.veltis.buildtools.version.MinecraftVersion;
import org.veltismc.veltis.buildtools.version.VersionManifest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;

public final class PipelineRunner {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String MAPPINGS_BASE =
        "https://maven.fabricmc.net/net/fabricmc/intermediary";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: PipelineRunner <step> <mcVersion> [projectDir]");
            System.exit(1);
        }
        var step = args[0];
        var mcVersion = MinecraftVersion.parse(args[1]);
        var projectDir = args.length > 2 ? Path.of(args[2]) : Path.of(".");
        var context = BuildContext.builder()
            .targetVersion(mcVersion)
            .workingDirectory(projectDir)
            .build();

        switch (step) {
            case "downloadMinecraft" -> downloadMinecraft(context);
            case "downloadMappings" -> downloadMappings(context);

            case "decompileMinecraft" -> decompileMinecraft(context);
            case "generateSourceWorkspace" -> generateSourceWorkspace(context);
            case "applyPatches" -> applyPatches(context);
            case "rebuildPatches" -> rebuildPatches(context);
            case "verifyPatches" -> verifyPatches(context);
            case "downloadLibraries" -> downloadLibraries(context);
            default -> throw new IllegalArgumentException("Unknown step: " + step);
        }
    }

    static void downloadMinecraft(BuildContext context) throws Exception {
        var output = context.serverJarPath();
        if (Files.exists(output) && Files.size(output) > 10000) {
            System.out.println("[downloadMinecraft] Already cached: " + output);
            return;
        }
        System.out.println("[downloadMinecraft] Downloading server jar for "
            + context.targetVersion() + "...");
        var manifest = VersionManifest.fetch();
        var entry = manifest.find(context.targetVersion().toString());
        var downloader = new ServerDownloader();

        // Download the bundler/merged jar to a temp file
        var tempBundler = output.resolveSibling(output.getFileName() + ".bundler.tmp");
        downloader.download(entry.url(), tempBundler);

        // Extract the actual server jar from the bundler
        var versionStr = context.targetVersion().toString();
        var innerPath = "META-INF/versions/" + versionStr + "/server-" + versionStr + ".jar";
        var extracted = extractFromJar(tempBundler, innerPath, output);
        if (extracted) {
            System.out.println("[downloadMinecraft] Extracted server jar from bundler: " + output);
        } else {
            // Not a bundler jar, use the downloaded file directly
            System.out.println("[downloadMinecraft] Not a bundler jar, using downloaded file directly");
            Files.move(tempBundler, output, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(tempBundler);
        System.out.println("[downloadMinecraft] Downloaded: " + output);
    }

    private static boolean extractFromJar(Path jarFile, String entryPath, Path output) throws IOException {
        try (var fs = FileSystems.newFileSystem(jarFile, (ClassLoader) null)) {
            var entry = fs.getPath("/" + entryPath);
            if (Files.exists(entry)) {
                Files.createDirectories(output.getParent());
                Files.copy(entry, output, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (Exception e) {
            // Not a valid filesystem (not a zip)
        }
        return false;
    }

    static void downloadMappings(BuildContext context) throws Exception {
        var output = context.mappingsPath();
        if (Files.exists(output)) {
            System.out.println("[downloadMappings] Already cached: " + output);
            return;
        }
        var version = context.targetVersion().toString();
        var mappingsUrl = MAPPINGS_BASE + "/" + version
            + "/intermediary-" + version + "-v2.jar";
        System.out.println("[downloadMappings] Downloading mappings from " + mappingsUrl);

        var tempJar = output.resolveSibling("intermediary-" + version + "-v2.jar");
        downloadFile(mappingsUrl, tempJar);

        extractMappings(tempJar, output);
        Files.deleteIfExists(tempJar);
        System.out.println("[downloadMappings] Extracted mappings: " + output);
    }

    static void decompileMinecraft(BuildContext context) throws Exception {
        var input = context.serverJarPath();
        var output = context.minecraftSourcePath();

        if (!Files.exists(input) || Files.size(input) < 10000) {
            throw new IllegalStateException(
                "Server jar not found or invalid: " + input + ". Run downloadMinecraft first.");
        }
        if (Files.exists(output) && hasJavaFiles(output)) {
            System.out.println("[decompileMinecraft] Already decompiled: " + output);
            return;
        }

        System.out.println("[decompileMinecraft] Decompiling " + input + " -> " + output);
        Files.createDirectories(output);

        DecompilerIntegration decompiler = new VineflowerProvider();
        var specBuilder = DecompilerIntegration.DecompileSpec.builder()
            .inputJar(input)
            .outputSourceDirectory(output);
        var libDir = context.cacheDirectory().resolve("libraries");
        if (Files.isDirectory(libDir)) {
            try (var files = Files.walk(libDir)) {
                var libs = files.filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().toString())
                    .toList();
                if (!libs.isEmpty()) {
                    specBuilder.classpath(libs);
                    System.out.println("[decompileMinecraft] Added " + libs.size()
                        + " library jars to decompiler classpath");
                }
            }
        }
        var spec = specBuilder.build();
        var result = decompiler.decompile(context, spec);

        if (!result.success()) {
            throw new RuntimeException("Decompile failed: " + result.errorMessage());
        }
        var javaCount = countJavaFiles(output);
        System.out.println("[decompileMinecraft] Decompiled "
            + result.fileCount() + " files in " + result.durationMs() + "ms");
    }

    private static long countJavaFiles(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    static void ensureBaseline(BuildContext context) throws Exception {
        var minecraftSource = context.minecraftSourcePath();
        var baseline = context.baselineSourcePath();

        if (!Files.exists(minecraftSource) || !hasJavaFiles(minecraftSource)) {
            throw new IllegalStateException(
                "Minecraft source not found or empty: " + minecraftSource
                    + ". Run decompileMinecraft first.");
        }

        var baselineMarker = baseline.resolve(".baseline_complete");
        if (!Files.exists(baseline) || !Files.exists(baselineMarker)) {
            System.out.println("[Pipeline] Creating baseline: "
                + minecraftSource + " -> " + baseline);
            deleteDirectory(baseline);
            Files.createDirectories(baseline);
            copyDirectory(minecraftSource, baseline);

            var patcher = new GitPatchSystem();
            var patches = patcher.discover(context);
            if (!patches.isEmpty()) {
                System.out.println("[Pipeline] Applying "
                    + patches.size() + " patches to baseline...");
                var results = patcher.applyAll(context, baseline);
                var failed = results.stream().filter(r -> !r.success()).count();
                if (failed > 0) {
                    deleteDirectory(baseline);
                    throw new RuntimeException("Baseline creation failed: "
                        + failed + " patches did not apply cleanly");
                }
            }
            Files.writeString(baselineMarker, "baseline-complete");
            System.out.println("[Pipeline] Baseline ready: " + baseline);
        } else {
            System.out.println("[Pipeline] Reusing existing baseline: " + baseline);
        }
    }

    static void generateSourceWorkspace(BuildContext context) throws Exception {
        ensureBaseline(context);

        var baseline = context.baselineSourcePath();
        var workspace = context.patchedSourcePath();

        // Copy baseline -> workspace (always refresh)
        System.out.println("[generateSourceWorkspace] Copying baseline -> workspace");
        deleteDirectory(workspace);
        Files.createDirectories(workspace);
        copyDirectory(baseline, workspace);
        System.out.println("[generateSourceWorkspace] Workspace ready: " + workspace);
    }

    static void applyPatches(BuildContext context) throws Exception {
        ensureBaseline(context);

        var baseline = context.baselineSourcePath();
        var workspace = context.patchedSourcePath();

        if (!Files.exists(workspace) || !hasJavaFiles(workspace)) {
            System.out.println("[applyPatches] Workspace missing, copying from baseline...");
            deleteDirectory(workspace);
            Files.createDirectories(workspace);
            copyDirectory(baseline, workspace);
        }

        PatchSystem patcher = new GitPatchSystem();
        var patches = patcher.discover(context);
        if (patches.isEmpty()) {
            System.out.println("[applyPatches] No patches found to apply");
            return;
        }

        System.out.println("[applyPatches] Verifying " + patches.size()
            + " patches apply to workspace...");
        var results = patcher.applyAll(context);
        var applied = results.stream().filter(r -> r.success()).count();
        System.out.println("[applyPatches] All " + applied + " patches verified");
    }

    static void rebuildPatches(BuildContext context) throws Exception {
        // Ensure baseline exists (does NOT touch patched-source — developer edits preserved)
        ensureBaseline(context);

        PatchSystem patcher = new GitPatchSystem();
        var results = patcher.rebuildAll(context);
        var success = results.stream().filter(r -> r.success()).count();
        var failed = results.size() - success;
        System.out.println("[rebuildPatches] " + success + " patches written"
            + (failed > 0 ? ", " + failed + " failed" : ""));
        if (failed > 0) {
            throw new RuntimeException("Patch rebuild failed: "
                + failed + " patches could not be validated");
        }
        System.out.println("[rebuildPatches] Done");
    }

    static void verifyPatches(BuildContext context) throws Exception {
        var tempDir = Files.createTempDirectory(
            context.workingDirectory(), "veltismc-verify-");
        try {
            var source = context.minecraftSourcePath();
            if (!Files.exists(source)) {
                throw new IllegalStateException(
                    "Minecraft source not found: " + source);
            }

            var testDir = tempDir.resolve("patched-source");
            copyDirectory(source, testDir);

            PatchSystem patcher = new GitPatchSystem();
            var patches = patcher.discover(context);
            if (patches.isEmpty()) {
                System.out.println("[verifyPatches] No patches to verify");
                return;
            }

            System.out.println("[verifyPatches] Verifying " + patches.size() + " patches...");
            var results = new ArrayList<PatchSystem.PatchResult>();
            var sorted = patches.stream()
                .sorted(Comparator.comparing(p -> p.id()))
                .toList();
            for (var patch : sorted) {
                var ctx = BuildContext.builder()
                    .targetVersion(context.targetVersion())
                    .workingDirectory(context.workingDirectory())
                    .cacheDirectory(context.cacheDirectory())
                    .outputDirectory(tempDir)
                    .build();
                var result = patcher.apply(ctx, patch);
                results.add(result);
                if (!result.success()) {
                    System.out.println("[verifyPatches] FAILED: "
                        + patch.id() + " - " + result.errorMessage());
                } else {
                    System.out.println("[verifyPatches] OK: " + patch.id());
                }
            }

            var failed = results.stream().filter(r -> !r.success()).count();
            if (failed > 0) {
                throw new RuntimeException("Verification failed: "
                    + failed + " patches do not apply cleanly");
            }
            System.out.println("[verifyPatches] All " + patches.size()
                + " patches verified successfully");
        } finally {
            deleteDirectory(tempDir);
        }
    }

    static void downloadLibraries(BuildContext context) throws Exception {
        var version = context.targetVersion().toString();
        var manifest = VersionManifest.fetch();
        var entry = manifest.find(version);
        var libDir = context.cacheDirectory().resolve("libraries");
        Files.createDirectories(libDir);

        System.out.println("[downloadLibraries] Resolving libraries for " + version);
        resolveAndDownloadLibraries(entry.url(), libDir);

        var downloader = new ServerDownloader();
        var mavenUrls = ServerDownloader.mavenUrls();
        for (var mavenEntry : mavenUrls.entrySet()) {
            var coord = mavenEntry.getKey();
            var url = mavenEntry.getValue();
            var parts = coord.split(":");
            var path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + ".jar";
            var target = libDir.resolve(path);
            if (Files.exists(target)) continue;
            Files.createDirectories(target.getParent());
            try {
                downloader.downloadDirect(url, target);
                System.out.println("  Downloaded annotation library: " + path);
            } catch (Exception e) {
                System.out.println("  WARNING: Failed to download " + path + ": " + e.getMessage());
            }
        }

        System.out.println("[downloadLibraries] Libraries downloaded to " + libDir);
    }

    private static void resolveAndDownloadLibraries(
        String versionUrl, Path libDir
    ) throws Exception {
        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var request = HttpRequest.newBuilder()
            .uri(URI.create(versionUrl))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        var response = client.send(request,
            HttpResponse.BodyHandlers.ofString());
        var gson = new com.google.gson.Gson();
        var root = gson.fromJson(response.body(), com.google.gson.JsonObject.class);

        var libraries = root.getAsJsonArray("libraries");
        if (libraries == null) return;

        var downloader = new ServerDownloader(client);
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
                downloader.downloadDirect(url, target);
                System.out.println("  Downloaded library: " + path);
            } catch (Exception e) {
                System.out.println("  WARNING: Failed to download " + path + ": " + e.getMessage());
            }
        }
    }

    private static boolean hasJavaFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (var stream = Files.walk(directory)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        }
    }

    private static void downloadFile(String url, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .GET()
            .build();
        var response = client.send(request,
            HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            Files.copy(stream, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void extractMappings(Path mappingsJar, Path output) throws Exception {
        try (var fs = FileSystems.newFileSystem(mappingsJar, (ClassLoader) null)) {
            var root = fs.getPath("/");
            try (var walk = Files.walk(root)) {
                var mappingsFile = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".tiny"))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                        "No .tiny mappings found in " + mappingsJar));
                Files.createDirectories(output.getParent());
                Files.copy(mappingsFile, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile)
                .forEach(src -> {
                    try {
                        var rel = source.relativize(src);
                        var dst = target.resolve(rel);
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        // ignore individual deletion errors during cleanup
                    }
                });
        }
    }
}
