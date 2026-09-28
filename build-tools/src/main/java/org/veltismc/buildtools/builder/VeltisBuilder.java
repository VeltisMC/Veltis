package org.veltismc.buildtools.builder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.veltismc.patchengine.CacheValidator;
import org.veltismc.patchengine.PatchEngineConfig;
import org.veltismc.patchengine.PatchedJarBuilder;
import org.veltismc.patchengine.PatchedJarBuilder.PrintStream;
import org.veltismc.patchengine.VanillaJarDownloader;
import org.veltismc.patchengine.VeltisConsole;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;

@Command(
    name = "veltis-builder",
    mixinStandardHelpOptions = true,
    version = "VeltisMC Builder 1.0",
    description = "Builds the VeltisMC patched server jar",
    subcommands = {
        VeltisBuilder.BuildCommand.class,
        VeltisBuilder.ValidateCommand.class,
        VeltisBuilder.CleanCommand.class
    }
)
public final class VeltisBuilder {

    static final Logger LOG = LogManager.getLogger(VeltisBuilder.class);

    private VeltisBuilder() {}

    public static void main(String[] args) {
        var cmd = new CommandLine(new VeltisBuilder());
        cmd.setExecutionStrategy(new CommandLine.RunLast());
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }

    @Command(
        name = "build",
        description = "Build the patched veltismc-server.jar",
        mixinStandardHelpOptions = true
    )
    static class BuildCommand implements Callable<Integer> {
        @Option(names = "--version", defaultValue = "26.3",
                description = "Minecraft version to build for")
        String minecraftVersion;

        @Option(names = "--home", defaultValue = ".",
                description = "Home directory (default: current dir)")
        Path homeDir;

        @Option(names = "--output",
                description = "Output jar path (default: versions/<ver>/veltismc-server.jar)")
        Path outputJar;

        @Option(names = "--keep-build", defaultValue = "false",
                description = "Keep intermediate build files after completion")
        boolean keepBuildFiles;

        @Option(names = "--skip-download", defaultValue = "false",
                description = "Skip vanilla jar download if already cached")
        boolean skipDownload;

        @Option(names = "--skip-decompile", defaultValue = "false",
                description = "Skip decompilation if sources already exist")
        boolean skipDecompile;

        @Option(names = "--skip-compile", defaultValue = "false",
                description = "Skip compilation (package only)")
        boolean skipCompile;

        @Option(names = "--force", defaultValue = "false",
                description = "Force rebuild (ignore cache)")
        boolean forceRebuild;

        @Option(names = "--threads", defaultValue = "4",
                description = "Number of parallel threads for compilation")
        int threads;

        @Override
        public Integer call() {
            var log = new ConsolePrintStream();
            var resolvedHome = homeDir.toAbsolutePath().normalize();
            var config = new PatchEngineConfig(minecraftVersion, resolvedHome, resolvedHome.resolve("server").resolve("patches"));

            var outputPath = outputJar != null ? outputJar
                : config.versionsDirectory().resolve("veltismc-server.jar");

            if (!forceRebuild && Files.exists(outputPath)) {
                if (!CacheValidator.isRebuildRequired(resolvedHome, minecraftVersion)) {
                    LOG.info("Found cached VeltisMC server {}", minecraftVersion);
                    LOG.debug("Use --force to rebuild; output: {}", outputPath);
                    return 0;
                }
                LOG.info("VeltisMC server cache is outdated");
            }

            try {
                if (skipDownload) {
                    LOG.debug("Skipping vanilla jar download (--skip-download)");
                } else {
                    ensureVanillaJar(config);
                }

                var builder = new PatchedJarBuilder(config, keepBuildFiles);
                builder.setLog(log);
                builder.setForceRebuild(forceRebuild);
                builder.setSkipDecompile(skipDecompile);
                builder.setSkipCompile(skipCompile);

                var builtJar = builder.build();

                if (!builtJar.equals(outputPath)) {
                    Files.createDirectories(outputPath.getParent());
                    Files.copy(builtJar, outputPath, StandardCopyOption.REPLACE_EXISTING);
                }

                BuildMetadata.generate(resolvedHome, minecraftVersion, outputPath, config);

                LOG.info("Server jar: {}", outputPath);
                LOG.debug("Build metadata: {}", BuildMetadata.path(resolvedHome, minecraftVersion));
                return 0;

            } catch (Exception e) {
                LOG.error("Build failed: {}", e.getMessage(), e);
                CacheValidator.invalidateCache(resolvedHome, minecraftVersion);
                return 1;
            }
        }

        private void ensureVanillaJar(PatchEngineConfig config) throws Exception {
            var vanillaJar = config.vanillaServerJar();
            if (Files.exists(vanillaJar)) {
                // The builder logs its own cache line right after; stay quiet here.
                LOG.debug("Found cached Minecraft server {}", config.minecraftVersion());
                return;
            }
            LOG.info("Downloading Minecraft server {}", config.minecraftVersion());
            var start = System.nanoTime();
            var downloader = new VanillaJarDownloader();
            downloader.download(config.minecraftVersion(), vanillaJar);
            LOG.info("Minecraft server downloaded ({})",
                VeltisConsole.formatDuration(System.nanoTime() - start));
        }
    }

    @Command(
        name = "validate",
        description = "Validate the build cache for a version",
        mixinStandardHelpOptions = true
    )
    static class ValidateCommand implements Callable<Integer> {
        @Option(names = "--version", defaultValue = "26.3",
                description = "Minecraft version to validate")
        String minecraftVersion;

        @Option(names = "--home", defaultValue = ".",
                description = "Home directory")
        Path homeDir;

        @Override
        public Integer call() {
            var resolvedHome = homeDir.toAbsolutePath().normalize();
            var rebuildNeeded = CacheValidator.isRebuildRequired(resolvedHome, minecraftVersion);
            var outputJar = resolvedHome.resolve("versions").resolve(minecraftVersion).resolve("veltismc-server.jar");
            var jarExists = Files.exists(outputJar);

            if (jarExists && !rebuildNeeded) {
                LOG.info("VeltisMC server cache is valid: {}", outputJar);
                return 0;
            } else if (!jarExists) {
                LOG.warn("VeltisMC server jar is missing: {}", outputJar);
                return 1;
            } else {
                LOG.warn("VeltisMC server cache is outdated: {}", outputJar);
                return 1;
            }
        }
    }

    @Command(
        name = "clean",
        description = "Clean build artifacts",
        mixinStandardHelpOptions = true
    )
    static class CleanCommand implements Callable<Integer> {
        @Parameters(paramLabel = "<version>", defaultValue = "*",
                    description = "Minecraft version to clean (default: all)")
        String version;

        @Option(names = "--home", defaultValue = ".",
                description = "Home directory")
        Path homeDir;

        @Override
        public Integer call() {
            var resolvedHome = homeDir.toAbsolutePath().normalize();
            LOG.info("Cleaning VeltisMC build artifacts");

            try (var stream = Files.list(resolvedHome.resolve("versions"))) {
                var cleaned = false;
                for (var dir : (Iterable<Path>) stream::iterator) {
                    var name = dir.getFileName().toString();
                    if (version.equals("*") || name.equals(version)) {
                        if (Files.isDirectory(dir)) {
                            var cacheDir = dir.resolve("patch-engine.cache");
                            if (Files.exists(cacheDir)) {
                                deleteRecursively(cacheDir);
                                LOG.debug("Cleared cache: {}", cacheDir);
                                cleaned = true;
                            }
                            var classesDir = dir.resolve("classes");
                            if (Files.exists(classesDir)) {
                                deleteRecursively(classesDir);
                                LOG.debug("Cleared classes: {}", classesDir);
                                cleaned = true;
                            }
                            var patchedDir = dir.resolve("patched-source");
                            if (Files.exists(patchedDir)) {
                                deleteRecursively(patchedDir);
                                LOG.debug("Cleared sources: {}", patchedDir);
                                cleaned = true;
                            }
                            var buildMeta = dir.resolve("build.meta");
                            if (Files.exists(buildMeta)) {
                                Files.deleteIfExists(buildMeta);
                                LOG.debug("Removed: {}", buildMeta);
                                cleaned = true;
                            }
                            var jar = dir.resolve("veltismc-server.jar");
                            if (Files.exists(jar)) {
                                Files.deleteIfExists(jar);
                                LOG.debug("Removed: {}", jar);
                                cleaned = true;
                            }
                        }
                    }
                }
                if (!cleaned) {
                    LOG.info("Nothing to clean");
                } else {
                    LOG.info("Build artifacts cleaned");
                }
            } catch (Exception e) {
                LOG.error("Clean failed: {}", e.getMessage(), e);
                return 1;
            }
            return 0;
        }

        private static void deleteRecursively(Path root) {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {
            }
        }
    }

    static class ConsolePrintStream implements PrintStream {
        @Override
        public void info(String msg) { LOG.info(msg); }
        @Override
        public void warn(String msg) { LOG.warn(msg); }
        @Override
        public void error(String msg) { LOG.error(msg); }
    }
}
