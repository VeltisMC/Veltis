package org.veltismc.veltis.builder;

import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class BuildMetadata {

    private static final String FILENAME = "build.meta";

    private BuildMetadata() {}

    public static Path path(Path homeDir, String version) {
        return homeDir.resolve("versions").resolve(version).resolve(FILENAME);
    }

    public static void generate(Path homeDir, String version, Path outputJar, org.veltismc.veltis.patchengine.PatchEngineConfig config) {
        try {
            var meta = new Metadata();
            meta.formatVersion = 1;
            meta.minecraftVersion = version;
            meta.buildTime = Instant.now().toString();

            meta.compiler = new CompilerInfo();
            meta.compiler.vendor = System.getProperty("java.vendor", "unknown");
            meta.compiler.version = System.getProperty("java.version", "unknown");

            meta.inputs = new InputHashes();
            meta.inputs.vanillaJar = hashFile(config.vanillaServerJar());
            meta.inputs.patches = new TreeMap<>();
            var patchesDir = homeDir.resolve("patches");
            if (Files.isDirectory(patchesDir)) {
                try (var walk = Files.walk(patchesDir)) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".patch"))
                        .forEach(f -> {
                            var rel = patchesDir.relativize(f).toString().replace("\\", "/");
                            meta.inputs.patches.put(rel, hashFile(f));
                        });
                }
            }
            meta.inputs.dependencies = hashDependencies(homeDir);

            meta.outputs = new OutputInfo();
            meta.outputs.jarSize = Files.exists(outputJar) ? Files.size(outputJar) : 0;
            meta.outputs.jarSha256 = hashFile(outputJar);
            if (Files.isDirectory(config.patchedSourceDirectory())) {
                try (var walk = Files.walk(config.patchedSourceDirectory())) {
                    meta.outputs.sourceFiles = walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java")).count();
                }
            }
            if (Files.isDirectory(config.compiledClassesDirectory())) {
                try (var walk = Files.walk(config.compiledClassesDirectory())) {
                    meta.outputs.classFiles = walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".class")).count();
                }
            }

            meta.cache = new CacheInfo();
            meta.cache.format = 2;
            meta.cache.rebuildRequired = false;

            var gson = new GsonBuilder().setPrettyPrinting().create();
            var json = gson.toJson(meta);
            var metaPath = path(homeDir, version);
            Files.createDirectories(metaPath.getParent());
            Files.writeString(metaPath, json, StandardCharsets.UTF_8);

        } catch (Exception e) {
            System.err.println("[WARN] Failed to write build metadata: " + e.getMessage());
        }
    }

    public static boolean isCacheValid(Path homeDir, String version) {
        var metaPath = path(homeDir, version);
        if (!Files.isRegularFile(metaPath)) return false;
        try {
            var content = Files.readString(metaPath, StandardCharsets.UTF_8);
            var gson = new com.google.gson.Gson();
            var meta = gson.fromJson(content, Metadata.class);
            if (meta == null || meta.cache == null) return false;
            return !meta.cache.rebuildRequired;
        } catch (Exception e) {
            return false;
        }
    }

    private static String hashFile(Path file) {
        if (!Files.isRegularFile(file)) return "";
        try {
            var digester = MessageDigest.getInstance("SHA-256");
            digester.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hashDependencies(Path homeDir) {
        try {
            var digester = MessageDigest.getInstance("SHA-256");
            var files = new Path[]{
                homeDir.resolve("build.gradle.kts"),
                homeDir.resolve("settings.gradle.kts"),
                homeDir.resolve("gradle.properties")
            };
            for (var f : files) {
                if (Files.isRegularFile(f)) {
                    digester.update(f.toString().getBytes(StandardCharsets.UTF_8));
                    digester.update((byte) 0);
                    digester.update(Files.readAllBytes(f));
                }
            }
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    static class Metadata {
        int formatVersion;
        String minecraftVersion;
        String buildTime;
        CompilerInfo compiler;
        InputHashes inputs;
        OutputInfo outputs;
        CacheInfo cache;
    }

    static class CompilerInfo {
        String vendor;
        String version;
    }

    static class InputHashes {
        String vanillaJar;
        Map<String, String> patches;
        String dependencies;
    }

    static class OutputInfo {
        long sourceFiles;
        long classFiles;
        long jarSize;
        String jarSha256;
    }

    static class CacheInfo {
        int format;
        boolean rebuildRequired;
    }
}
