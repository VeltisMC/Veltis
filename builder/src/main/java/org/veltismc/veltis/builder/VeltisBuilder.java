package org.veltismc.veltis.builder;

import org.veltismc.veltis.patchengine.CacheValidator;
import org.veltismc.veltis.patchengine.PatchEngineConfig;
import org.veltismc.veltis.patchengine.PatchedJarBuilder;
import org.veltismc.veltis.patchengine.PatchedJarBuilder.PrintStream;
import org.veltismc.veltis.patchengine.VanillaJarDownloader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
        @Option(names = "--version", defaultValue = "26.2",
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

            log.info("VeltisMC Builder v1.0");
            log.info("  Version: " + minecraftVersion);
            log.info("  Home:    " + resolvedHome);
            log.info("  Output:  " + outputPath);
            log.info("");

            if (!forceRebuild && Files.exists(outputPath)) {
                if (!CacheValidator.isRebuildRequired(resolvedHome, minecraftVersion)) {
                    log.info("Build cache is valid, no rebuild needed.");
                    log.info("  Use --force to force rebuild.");
                    return 0;
                }
                log.info("  Cache invalidated, rebuilding...");
            }

            try {
                if (skipDownload) {
                    log.info("Skipping vanilla jar download...");
                } else {
                    ensureVanillaJar(config, log);
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

                log.info("Build complete: " + outputPath);
                log.info("Build metadata: " + BuildMetadata.path(resolvedHome, minecraftVersion));
                return 0;

            } catch (Exception e) {
                log.error("Build failed: " + e.getMessage());
                e.printStackTrace(System.err);
                CacheValidator.invalidateCache(resolvedHome, minecraftVersion);
                return 1;
            }
        }

        private void ensureVanillaJar(PatchEngineConfig config, ConsolePrintStream log) throws Exception {
            var vanillaJar = config.vanillaServerJar();
            if (Files.exists(vanillaJar)) {
                log.info("Vanilla jar already cached: " + vanillaJar);
                return;
            }
            log.info("Downloading vanilla server jar...");
            var downloader = new VanillaJarDownloader();
            downloader.download(config.minecraftVersion(), vanillaJar);
        }
    }

    @Command(
        name = "validate",
        description = "Validate the build cache for a version",
        mixinStandardHelpOptions = true
    )
    static class ValidateCommand implements Callable<Integer> {
        @Option(names = "--version", defaultValue = "26.2",
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

            System.out.println("VeltisMC Build Cache Validation");
            System.out.println("  Version:    " + minecraftVersion);
            System.out.println("  Home:       " + resolvedHome);
            System.out.println("  Jar exists: " + jarExists);
            System.out.println("  Rebuild:    " + (rebuildNeeded ? "NEEDED" : "OK"));

            if (jarExists && !rebuildNeeded) {
                System.out.println("  Status:     VALID");
                return 0;
            } else if (!jarExists) {
                System.out.println("  Status:     MISSING (build required)");
                return 1;
            } else {
                System.out.println("  Status:     INVALID (rebuild required)");
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
            System.out.println("Cleaning build artifacts...");

            try (var stream = Files.list(resolvedHome.resolve("versions"))) {
                var cleaned = false;
                for (var dir : (Iterable<Path>) stream::iterator) {
                    var name = dir.getFileName().toString();
                    if (version.equals("*") || name.equals(version)) {
                        if (Files.isDirectory(dir)) {
                            var cacheDir = dir.resolve("patch-engine.cache");
                            if (Files.exists(cacheDir)) {
                                try (var walk = Files.walk(cacheDir)) {
                                    walk.sorted(java.util.Comparator.reverseOrder())
                                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                                }
                                System.out.println("  Cleared cache: " + cacheDir);
                                cleaned = true;
                            }
                            var classesDir = dir.resolve("classes");
                            if (Files.exists(classesDir)) {
                                try (var walk = Files.walk(classesDir)) {
                                    walk.sorted(java.util.Comparator.reverseOrder())
                                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                                }
                                System.out.println("  Cleared classes: " + classesDir);
                                cleaned = true;
                            }
                            var patchedDir = dir.resolve("patched-source");
                            if (Files.exists(patchedDir)) {
                                try (var walk = Files.walk(patchedDir)) {
                                    walk.sorted(java.util.Comparator.reverseOrder())
                                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                                }
                                System.out.println("  Cleared sources: " + patchedDir);
                                cleaned = true;
                            }
                            var buildMeta = dir.resolve("build.meta");
                            if (Files.exists(buildMeta)) {
                                Files.deleteIfExists(buildMeta);
                                System.out.println("  Removed: " + buildMeta);
                                cleaned = true;
                            }
                            var jar = dir.resolve("veltismc-server.jar");
                            if (Files.exists(jar)) {
                                Files.deleteIfExists(jar);
                                System.out.println("  Removed: " + jar);
                                cleaned = true;
                            }
                        }
                    }
                }
                if (!cleaned) {
                    System.out.println("  Nothing to clean.");
                }
            } catch (Exception e) {
                System.err.println("Clean failed: " + e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    static class ConsolePrintStream implements PrintStream {
        @Override
        public void info(String msg) { System.out.println("[INFO] " + msg); }
        @Override
        public void warn(String msg) { System.out.println("[WARN] " + msg); }
        @Override
        public void error(String msg) { System.err.println("[ERROR] " + msg); }
    }
}
