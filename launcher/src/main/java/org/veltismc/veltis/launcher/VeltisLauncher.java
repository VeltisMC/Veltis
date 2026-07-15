package org.veltismc.veltis.launcher;

import com.google.gson.JsonParser;
import org.fusesource.jansi.AnsiConsole;
import org.veltismc.veltis.patchengine.CacheValidator;
import org.veltismc.veltis.patchengine.PatchedJarBuilder;
import org.veltismc.veltis.patchengine.PatchEngineConfig;
import org.veltismc.veltis.patchengine.VanillaJarDownloader;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class VeltisLauncher {

    private static final PrintStream LOG = System.out;

    private VeltisLauncher() {
    }

    public static void main(String[] args) {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        System.setProperty("jdk.console.encoding", "UTF-8");
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        AnsiConsole.systemInstall();
        var manifest = LauncherManifest.from(args);
        var minecraftVersion = manifest.minecraftVersion().orElse("26.2");
        var homeDir = manifest.homeDirectory();

        LOG.println("╔══════════════════════════════════════╗");
        LOG.println("║       VeltisMC Launcher v1.0         ║");
        LOG.println("╚══════════════════════════════════════╝");
        LOG.printf("  Started at: %s%n", Instant.now());
        LOG.printf("  Java: %s (%s)%n",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"));
        LOG.printf("  Home: %s%n", homeDir.toAbsolutePath());
        LOG.printf("  Version: %s%n", minecraftVersion);

        try {
            var serverJar = locateOrBuildServerJar(homeDir, minecraftVersion);
            if (serverJar == null) {
                LOG.println("  [FATAL] Could not obtain server jar.");
                System.exit(1);
                return;
            }
            LOG.printf("  Server jar: %s%n", serverJar);

            var classpathUrls = buildClasspath(serverJar, homeDir, minecraftVersion);
            LOG.printf("  Classpath: %d entries%n", classpathUrls.size());

            var classLoader = new URLClassLoader(
                classpathUrls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader()
            );

            var mainClass = Class.forName("org.veltismc.veltis.Main", true, classLoader);
            var mainMethod = mainClass.getMethod("main", String[].class);

            var serverArgs = new ArrayList<String>();
            serverArgs.add("--home");
            serverArgs.add(homeDir.toAbsolutePath().toString());
            serverArgs.add("--version");
            serverArgs.add(minecraftVersion);
            manifest.serverName().ifPresent(n -> {
                serverArgs.add("--name");
                serverArgs.add(n);
            });
            manifest.protocolVersion().ifPresent(p -> {
                serverArgs.add("--protocol");
                serverArgs.add(String.valueOf(p));
            });
            manifest.port().ifPresent(p -> {
                serverArgs.add("--port");
                serverArgs.add(String.valueOf(p));
            });
            manifest.maxPlayers().ifPresent(mp -> {
                serverArgs.add("--max-players");
                serverArgs.add(String.valueOf(mp));
            });

            if (manifest.hasFlag("nogui")) {
                serverArgs.add("--nogui");
            }

            LOG.println("Starting VeltisMC server...");
            Thread.currentThread().setContextClassLoader(classLoader);
            mainMethod.invoke(null, (Object) serverArgs.toArray(String[]::new));

        } catch (Exception e) {
            LOG.println("  [FATAL] Failed to start VeltisMC server:");
            e.printStackTrace(LOG);
            System.exit(1);
        }
    }

    private static Path locateOrBuildServerJar(Path homeDir, String version) throws IOException {
        var jarPath = serverJarPath(homeDir, version);
        var buildMetaPath = homeDir.resolve("versions").resolve(version).resolve("build.meta");

        // Check 1: Existing jar with valid build.meta (fastest path — pre-built)
        if (Files.isRegularFile(jarPath) && Files.isRegularFile(buildMetaPath)) {
            if (validateBuildMeta(buildMetaPath, jarPath)) {
                if (!CacheValidator.isRebuildRequired(homeDir, version)) {
                    LOG.println("  Build cache valid, launching existing server jar...");
                    return jarPath.toAbsolutePath();
                }
            }
            LOG.println("  Build meta invalidated, will rebuild...");
        } else if (Files.isRegularFile(jarPath)) {
            // No build.meta — check legacy cache validator
            if (!CacheValidator.isRebuildRequired(homeDir, version)) {
                LOG.println("  Found existing jar (legacy cache), launching...");
                return jarPath.toAbsolutePath();
            }
            LOG.println("  Cache invalidated, rebuilding server jar...");
        }

        // Check 2: Gradle build output directory
        var buildPath = homeDir.resolve("build").resolve("versions")
            .resolve(version).resolve("veltismc-server.jar");
        if (Files.isRegularFile(buildPath)) {
            if (!CacheValidator.isRebuildRequired(homeDir, version)) {
                LOG.println("  Found build output jar, launching...");
                return buildPath.toAbsolutePath();
            }
        }

        LOG.println("  No valid server jar found, building from vanilla jar...");
        return buildPatchedJar(homeDir, version);
    }

    private static boolean validateBuildMeta(Path buildMetaPath, Path jarPath) {
        try {
            var content = Files.readString(buildMetaPath);
            var meta = JsonParser.parseString(content).getAsJsonObject();

            // Verify jar hash
            if (meta.has("outputs")) {
                var outputs = meta.getAsJsonObject("outputs");
                if (outputs.has("jarSha256")) {
                    var expectedHash = outputs.get("jarSha256").getAsString();
                    if (!expectedHash.isEmpty()) {
                        var actualHash = sha256File(jarPath);
                        if (!expectedHash.equals(actualHash)) {
                            LOG.println("  Jar hash mismatch (expected=" + expectedHash + ", actual=" + actualHash + ")");
                            return false;
                        }
                    }
                }
            }

            // Check rebuild flag
            if (meta.has("cache")) {
                var cache = meta.getAsJsonObject("cache");
                if (cache.has("rebuildRequired") && cache.get("rebuildRequired").getAsBoolean()) {
                    LOG.println("  Build meta indicates rebuild required");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOG.println("  Failed to validate build meta: " + e.getMessage());
            return false;
        }
    }

    private static String sha256File(Path file) {
        try {
            var digester = MessageDigest.getInstance("SHA-256");
            digester.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static Path buildPatchedJar(Path homeDir, String version) {
        // Try 1: Full PatchedJarBuilder pipeline (decompile, patch, compile, package)
        try {
            var config = new PatchEngineConfig(version, homeDir, Path.of("unused"));
            var keepBuildFiles = Boolean.getBoolean("veltismc.keepBuildFiles");
            var builder = new PatchedJarBuilder(config, keepBuildFiles);

            LOG.println("  Building patched server jar (this may take a few minutes)...");
            return builder.build();
        } catch (Exception e) {
            LOG.println("  [WARN] Full patching failed: " + e.getMessage());
        }

        // Try 2: Copy vanilla jar directly (Mixin handles all VeltisMC modifications at class level)
        try {
            var vanillaJar = homeDir.resolve("vanilla").resolve(version).resolve("server.jar");
            if (!Files.isRegularFile(vanillaJar)) {
                LOG.println("  [INFO] Vanilla jar not cached, downloading...");
                var downloader = new VanillaJarDownloader();
                downloader.download(version, vanillaJar);
            }
            if (Files.isRegularFile(vanillaJar)) {
                var target = serverJarPath(homeDir, version);
                Files.createDirectories(target.getParent());
                Files.copy(vanillaJar, target, StandardCopyOption.REPLACE_EXISTING);
                LOG.println("  [INFO] Copied vanilla jar to " + target);
                return target;
            }
        } catch (Exception e) {
            LOG.println("  [WARN] Vanilla jar copy also failed: " + e.getMessage());
        }

        LOG.println("  [FATAL] Could not obtain server jar.");
        return null;
    }

    private static Path serverJarPath(Path homeDir, String version) {
        return homeDir.resolve("versions").resolve(version).resolve("veltismc-server.jar");
    }

    private static List<URL> buildClasspath(Path serverJar, Path homeDir, String version) {
        var urls = new ArrayList<URL>();
        try {
            // Patched Minecraft jar first — provides Minecraft classes (net.minecraft.*)
            urls.add(serverJar.toUri().toURL());

            // Minecraft libraries — provides common deps (Guava, Gson, commons-lang3, etc.)
            var mcLibDir = homeDir.resolve("ver").resolve(version).resolve("libraries");
            if (Files.isDirectory(mcLibDir)) {
                try (var files = Files.walk(mcLibDir)) {
                    files.filter(p -> p.toString().endsWith(".jar"))
                         .filter(Files::isRegularFile)
                         .sorted()
                         .map(VeltisLauncher::toURL)
                         .forEach(urls::add);
                }
            }

            // User-facing libraries folder — merged from libs + libraries
            var userLibDir = homeDir.resolve("libraries");
            if (!Files.isDirectory(userLibDir)) {
                Files.createDirectories(userLibDir);
            }

            // Auto-download any missing Minecraft libraries from Mojang's manifest
            downloadMissingLibraries(version, userLibDir, mcLibDir);

            // Scan the user libraries folder
            try (var files = Files.walk(userLibDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                     .filter(Files::isRegularFile)
                     .map(VeltisLauncher::toURL)
                     .forEach(urls::add);
            }

            // Launcher jar last — VeltisMC-specific classes (Main, API, Moonrise, Mixin)
            urls.add(locationOf(VeltisLauncher.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build classpath", e);
        }
        return urls;
    }

    private static void downloadMissingLibraries(String version, Path userLibDir, Path mcLibDir) {
        var httpClient = HttpClient.newHttpClient();
        try {
            // Fetch version manifest
            var manifestReq = HttpRequest.newBuilder()
                .uri(URI.create("https://launchermeta.mojang.com/mc/game/version_manifest.json"))
                .build();
            var manifestResp = httpClient.send(manifestReq, HttpResponse.BodyHandlers.ofString());
            if (manifestResp.statusCode() != 200) return;

            // Find the version entry
            var manifestJson = com.google.gson.JsonParser.parseString(manifestResp.body()).getAsJsonObject();
            var versions = manifestJson.getAsJsonArray("versions");
            String versionUrl = null;
            for (var v : versions) {
                var entry = v.getAsJsonObject();
                if (version.equals(entry.get("id").getAsString())) {
                    versionUrl = entry.get("url").getAsString();
                    break;
                }
            }
            if (versionUrl == null) return;

            // Fetch version metadata
            var metaReq = HttpRequest.newBuilder().uri(URI.create(versionUrl)).build();
            var metaResp = httpClient.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() != 200) return;

            var metaJson = com.google.gson.JsonParser.parseString(metaResp.body()).getAsJsonObject();
            var libraries = metaJson.getAsJsonArray("libraries");
            if (libraries == null) return;

            for (var lib : libraries) {
                var libObj = lib.getAsJsonObject();
                var downloads = libObj.getAsJsonObject("downloads");
                if (downloads == null) continue;
                var artifact = downloads.getAsJsonObject("artifact");
                if (artifact == null) continue;

                var path = artifact.get("path").getAsString();
                var url = artifact.get("url").getAsString();

                // Check if already present in either lib dir
                var targetPath = userLibDir.resolve(path.replace('/', java.io.File.separatorChar));
                if (Files.isRegularFile(targetPath)) continue;

                if (mcLibDir != null) {
                    var mcPath = mcLibDir.resolve(path.replace('/', java.io.File.separatorChar));
                    if (Files.isRegularFile(mcPath)) {
                        // Copy from Minecraft lib dir to user lib dir
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(mcPath, targetPath);
                        continue;
                    }
                }

                // Download missing library
                LOG.println("  [Lib] Downloading " + path + " ...");
                try {
                    Files.createDirectories(targetPath.getParent());
                    var dlReq = HttpRequest.newBuilder().uri(URI.create(url)).build();
                    var dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());
                    if (dlResp.statusCode() == 200) {
                        try (var in = dlResp.body()) {
                            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (Exception e) {
                    LOG.println("  [WARN] Failed to download " + path + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.println("  [WARN] Library download failed: " + e.getMessage());
        }
    }

    private static URL toURL(Path path) {
        try {
            return path.toUri().toURL();
        } catch (Exception e) {
            throw new RuntimeException("Invalid path: " + path, e);
        }
    }

    private static URL locationOf(Class<?> cls) {
        try {
            return cls.getProtectionDomain().getCodeSource().getLocation().toURI().toURL();
        } catch (Exception e) {
            throw new RuntimeException("Cannot determine location of " + cls.getName(), e);
        }
    }
}
