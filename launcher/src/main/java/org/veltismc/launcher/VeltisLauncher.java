package org.veltismc.launcher;

import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.veltismc.patchengine.CacheValidator;
import org.veltismc.patchengine.PatchedJarBuilder;
import org.veltismc.patchengine.PatchEngineConfig;
import org.veltismc.patchengine.VanillaJarDownloader;
import org.veltismc.patchengine.VeltisConsole;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class VeltisLauncher {

    /**
     * Assigned in {@link #main} right after {@link VeltisConsole#configureLog4j()}:
     * a logger obtained earlier would initialize Log4j2 before its configuration
     * is in place, which is exactly what produced mixed output patterns before.
     */
    private static Logger LOG;

    private VeltisLauncher() {
    }

    public static void main(String[] args) {
        // One logging system (Log4j2) and a UTF-8 console — before anything logs.
        VeltisConsole.configureLog4j();
        VeltisConsole.installConsole();
        LOG = LogManager.getLogger(VeltisLauncher.class);

        var manifest = LauncherManifest.from(args);
        var minecraftVersion = manifest.minecraftVersion().orElse("26.3");
        var homeDir = manifest.homeDirectory();
        var verbose = manifest.hasFlag("verbose");
        if (verbose) {
            // Diagnostics (javac notes, decompiler chatter) are logged at
            // DEBUG; --verbose is the only way to see them.
            if (LogManager.getContext(false)
                    instanceof org.apache.logging.log4j.core.LoggerContext ctx) {
                ctx.getConfiguration().getRootLogger()
                    .setLevel(org.apache.logging.log4j.Level.DEBUG);
                ctx.updateLoggers();
            }
        }

        // A single useful home-dir message. The restarted child stays quiet:
        // the parent already printed the same absolute path.
        if (!Boolean.getBoolean("veltismc.restarted")) {
            LOG.info("Server home: {}", homeDir.toAbsolutePath());
        }
        if (verbose) {
            printDiagnostics(homeDir, minecraftVersion);
        }
        restartInHomeDirectoryIfNeeded(args, homeDir);

        try {
            var serverJar = locateOrBuildServerJar(homeDir, minecraftVersion);
            if (serverJar == null) {
                System.exit(1);
                return;
            }
            if (verbose) {
                LOG.info("Server jar: {}", serverJar);
            }

            var classpathUrls = buildClasspath(serverJar, homeDir, minecraftVersion);
            if (verbose) {
                LOG.info("Classpath: {} entries", classpathUrls.size());
            }

            var classLoader = new URLClassLoader(
                classpathUrls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader()
            );

            var mainClass = Class.forName("org.veltismc.server.Main", true, classLoader);
            var mainMethod = mainClass.getMethod("main", String[].class);

            var serverArgs = new ArrayList<String>();
            serverArgs.add("--home");
            serverArgs.add(homeDir.toAbsolutePath().toString());
            serverArgs.add("--version");
            serverArgs.add(minecraftVersion);
            // Forward everything the user typed; only our normalized
            // --home/--version replace theirs. Veltis-only flags are stripped
            // by server.Main before vanilla's parser sees them.
            for (int i = 0; i < args.length; i++) {
                if ("--home".equals(args[i]) || "--version".equals(args[i])) {
                    i++;
                    continue;
                }
                serverArgs.add(args[i]);
            }

            Thread.currentThread().setContextClassLoader(classLoader);
            mainMethod.invoke(null, (Object) serverArgs.toArray(String[]::new));

        } catch (Throwable e) {
            var cause = e instanceof InvocationTargetException ite && ite.getCause() != null
                ? ite.getCause() : e;
            LOG.error("Failed to start VeltisMC server", cause);
            System.exit(1);
        }
    }

    /**
     * Environment dump for {@code --verbose} only: JVM, platform, memory and
     * logging wiring — nothing that belongs in normal startup output.
     */
    private static void printDiagnostics(Path homeDir, String version) {
        LOG.info("Java: {} ({}, {})",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("java.arch"));
        LOG.info("OS: {} ({}) | Processors: {} | Max memory: {} MB",
            System.getProperty("os.name"), System.getProperty("os.arch"),
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() / (1024 * 1024));
        LOG.info("Minecraft: {} | Home: {}", version, homeDir.toAbsolutePath());
        LOG.info("Log4j config: {} | JUL bridge: {}",
            System.getProperty("log4j.configurationFile", "default"),
            System.getProperty("java.util.logging.manager", "default"));
    }

    /**
     * Vanilla Minecraft resolves its data (world/, eula.txt, server.properties,
     * logs/) against the process working directory, and java.nio pins that
     * directory when the JVM boots — so an in-process {@code --home} pointing
     * somewhere else can never take effect. Re-launch this jar from the
     * requested home directory instead, keeping config, vanilla data and the
     * patched-jar cache in one place. No-op when the working directory already
     * is the home directory (the normal case).
     */
    private static void restartInHomeDirectoryIfNeeded(String[] args, Path homeDir) {
        if (Boolean.getBoolean("veltismc.restarted")) return;

        var workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var target = homeDir.toAbsolutePath().normalize();
        var onWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        var sameDirectory = onWindows
            ? workingDir.toString().equalsIgnoreCase(target.toString())
            : workingDir.toString().equals(target.toString());
        if (sameDirectory) return;

        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            LOG.error("Cannot create server directory {}", target, e);
            System.exit(1);
        }

        LOG.info("Working directory was {}; restarting in {} so server data stays together",
            workingDir, target);
        var java = ProcessHandle.current().info().command().orElse("java");
        var command = new ArrayList<String>();
        command.add(java);
        command.add("-Dveltismc.restarted=true");
        try {
            var launcherLocation = Path.of(locationOf(VeltisLauncher.class).toURI());
            if (Files.isRegularFile(launcherLocation)) {
                command.add("-jar");
                command.add(launcherLocation.toString());
            } else {
                command.add("-cp");
                command.add(launcherLocation.toString());
                command.add(VeltisLauncher.class.getName());
            }
            // Rewrite --home to the absolute target: the child's working
            // directory already IS the home, so a relative --home would nest a
            // second home directory inside it.
            for (int i = 0; i < args.length; i++) {
                if ("--home".equals(args[i])) {
                    command.add("--home");
                    command.add(target.toString());
                    i++;
                } else {
                    command.add(args[i]);
                }
            }
            var child = new ProcessBuilder(command)
                .directory(target.toFile())
                .inheritIO()
                .start();
            System.exit(child.waitFor());
        } catch (Exception e) {
            LOG.error("Failed to restart in {}", target, e);
            System.exit(1);
        }
    }

    private static Path locateOrBuildServerJar(Path homeDir, String version) throws IOException {
        var jarPath = serverJarPath(homeDir, version);
        var buildMetaPath = homeDir.resolve("versions").resolve(version).resolve("build.meta");

        // Check 1: Existing jar with valid build.meta (fastest path — pre-built)
        if (Files.isRegularFile(jarPath) && Files.isRegularFile(buildMetaPath)) {
            if (validateBuildMeta(buildMetaPath, jarPath)
                    && !CacheValidator.isRebuildRequired(homeDir, version)) {
                LOG.info("Found cached VeltisMC server {}", version);
                return jarPath.toAbsolutePath();
            }
            LOG.info("VeltisMC server cache is outdated");
        } else if (Files.isRegularFile(jarPath)) {
            // No build.meta — check legacy cache validator
            if (!CacheValidator.isRebuildRequired(homeDir, version)) {
                LOG.info("Found cached VeltisMC server {}", version);
                return jarPath.toAbsolutePath();
            }
            LOG.info("VeltisMC server cache is outdated");
        }

        // Check 2: Gradle build output directory
        var buildPath = homeDir.resolve("build").resolve("versions")
            .resolve(version).resolve("veltismc-server.jar");
        if (Files.isRegularFile(buildPath) && !CacheValidator.isRebuildRequired(homeDir, version)) {
            LOG.info("Found cached VeltisMC server {}", version);
            return buildPath.toAbsolutePath();
        }

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
                            LOG.debug("Jar hash mismatch (expected={}, actual={})", expectedHash, actualHash);
                            return false;
                        }
                    }
                }
            }

            // Check rebuild flag
            if (meta.has("cache")) {
                var cache = meta.getAsJsonObject("cache");
                if (cache.has("rebuildRequired") && cache.get("rebuildRequired").getAsBoolean()) {
                    LOG.debug("Build meta indicates rebuild required");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOG.debug("Failed to validate build meta: {}", e.toString());
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

            return builder.build();
        } catch (Exception e) {
            LOG.error("Failed to build patched server jar", e);
        }

        // Try 2: fall back to an unpatched vanilla jar. The server still boots, but
        // without the patches nothing wires VeltisBootstrap in, so VeltisMC's
        // runtime stays dormant.
        try {
            var vanillaJar = homeDir.resolve("vanilla").resolve(version).resolve("server.jar");
            if (!Files.isRegularFile(vanillaJar)) {
                LOG.info("Downloading Minecraft server {}", version);
                var start = System.nanoTime();
                var downloader = new VanillaJarDownloader();
                downloader.download(version, vanillaJar);
                LOG.info("Minecraft server downloaded ({})",
                    VeltisConsole.formatDuration(System.nanoTime() - start));
            }
            if (Files.isRegularFile(vanillaJar)) {
                var target = serverJarPath(homeDir, version);
                Files.createDirectories(target.getParent());
                Files.copy(vanillaJar, target, StandardCopyOption.REPLACE_EXISTING);
                LOG.warn("Using the vanilla server jar; VeltisMC patches are not active");
                return target;
            }
        } catch (Exception e) {
            LOG.error("Vanilla server jar fallback failed", e);
        }

        LOG.error("Could not obtain a server jar for Minecraft {}", version);
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

            // Launcher jar last — VeltisMC-specific classes (Main, API, runtime)
            urls.add(locationOf(VeltisLauncher.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build classpath", e);
        }
        return urls;
    }

    private static void downloadMissingLibraries(String version, Path userLibDir, Path mcLibDir) {
        var httpClient = HttpClient.newHttpClient();
        var downloaded = 0;
        var start = System.nanoTime();
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
                try {
                    Files.createDirectories(targetPath.getParent());
                    var dlReq = HttpRequest.newBuilder().uri(URI.create(url)).build();
                    var dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());
                    if (dlResp.statusCode() == 200) {
                        try (var in = dlResp.body()) {
                            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        downloaded++;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to download {}: {}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Library download check failed: {}", e.getMessage());
            return;
        }
        if (downloaded > 0) {
            LOG.info("Downloaded {} Minecraft libraries ({})",
                downloaded, VeltisConsole.formatDuration(System.nanoTime() - start));
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
