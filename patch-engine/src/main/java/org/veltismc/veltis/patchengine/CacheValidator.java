package org.veltismc.veltis.patchengine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class CacheValidator {

    private static final String CACHE_DIR = "patch-engine.cache";
    private static final String PATCH_HASHES = "patch-hashes.sha256";
    private static final String COMPAT_HASHES = "compatibility-hashes.sha256";
    private static final String VANILLA_JAR_HASH = "vanilla-jar.sha256";
    private static final String MAPPINGS_HASH = "mappings.sha256";
    private static final String COMPILER_VERSION = "compiler-version.txt";
    private static final String DEPENDENCIES_HASH = "dependencies.sha256";
    private static final String CACHE_MARKER = "cache-valid";
    private static final String CACHE_METADATA = "cache-metadata.json";

    private CacheValidator() {}

    public static boolean isRebuildRequired(Path homeDir, String version) {
        var cacheDir = homeDir.resolve("versions").resolve(version).resolve(CACHE_DIR);
        var marker = cacheDir.resolve(CACHE_MARKER);
        if (!Files.isRegularFile(marker)) return true;

        if (!compareHash(cacheDir.resolve(PATCH_HASHES), computePatchHashes(homeDir))) return true;
        if (!compareHash(cacheDir.resolve(COMPAT_HASHES), computeCompatibilityHashes(homeDir))) return true;
        if (!compareHash(cacheDir.resolve(VANILLA_JAR_HASH), computeVanillaJarHash(homeDir, version))) return true;
        if (!compareHash(cacheDir.resolve(MAPPINGS_HASH), computeMappingsHash(homeDir))) return true;
        if (!compareHash(cacheDir.resolve(DEPENDENCIES_HASH), computeDependenciesHash(homeDir))) return true;
        if (!compareString(cacheDir.resolve(COMPILER_VERSION), detectCompilerVersion())) return true;

        var patchedJar = homeDir.resolve("versions").resolve(version).resolve("veltismc-server.jar");
        return !Files.isRegularFile(patchedJar);
    }

    public static void markCacheValid(Path homeDir, String version) {
        try {
            var cacheDir = homeDir.resolve("versions").resolve(version).resolve(CACHE_DIR);
            Files.createDirectories(cacheDir);
            writeHash(cacheDir.resolve(PATCH_HASHES), computePatchHashes(homeDir));
            writeHash(cacheDir.resolve(COMPAT_HASHES), computeCompatibilityHashes(homeDir));
            writeHash(cacheDir.resolve(VANILLA_JAR_HASH), computeVanillaJarHash(homeDir, version));
            writeHash(cacheDir.resolve(MAPPINGS_HASH), computeMappingsHash(homeDir));
            writeHash(cacheDir.resolve(DEPENDENCIES_HASH), computeDependenciesHash(homeDir));
            writeString(cacheDir.resolve(COMPILER_VERSION), detectCompilerVersion());
            writeMetadata(cacheDir.resolve(CACHE_METADATA), version);
            Files.writeString(cacheDir.resolve(CACHE_MARKER), "ok");
        } catch (Exception ignored) {}
    }

    public static void invalidateCache(Path homeDir, String version) {
        try {
            var cacheDir = homeDir.resolve("versions").resolve(version).resolve(CACHE_DIR);
            var marker = cacheDir.resolve(CACHE_MARKER);
            Files.deleteIfExists(marker);
        } catch (Exception ignored) {}
    }

    private static boolean compareHash(Path file, String current) {
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file).trim().equals(current);
            }
        } catch (Exception ignored) {}
        return current.isEmpty();
    }

    private static boolean compareString(Path file, String current) {
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file).trim().equals(current);
            }
        } catch (Exception ignored) {}
        return current.isEmpty();
    }

    private static String computeVanillaJarHash(Path homeDir, String version) {
        var jar = homeDir.resolve("vanilla").resolve(version).resolve("server.jar");
        if (!Files.isRegularFile(jar)) return "";
        return sha256File(jar);
    }

    private static String computeMappingsHash(Path homeDir) {
        var mappingsDir = homeDir.resolve("build-tools").resolve("src");
        if (Files.isDirectory(mappingsDir)) {
            return sha256Tree(mappingsDir, ".java");
        }
        var buildData = homeDir.resolve("build-data");
        if (Files.isDirectory(buildData)) {
            return sha256Tree(buildData, null);
        }
        return "";
    }

    private static String computeDependenciesHash(Path homeDir) {
        var gradle = homeDir.resolve("build.gradle.kts");
        var settings = homeDir.resolve("settings.gradle.kts");
        var props = homeDir.resolve("gradle.properties");
        var digester = newDigester();
        digestFile(digester, gradle);
        digestFile(digester, settings);
        digestFile(digester, props);
        return HexFormat.of().formatHex(digester.digest());
    }

    private static String detectCompilerVersion() {
        try {
            var rt = Runtime.version();
            return rt.feature() + "." + rt.interim() + "." + rt.update() + "+" + rt.build().orElse(0);
        } catch (Exception e) {
            return System.getProperty("java.version", "unknown");
        }
    }

    private static String computePatchHashes(Path homeDir) {
        var patchesDirs = List.of(
            homeDir.resolve("server").resolve("patches"),
            homeDir.resolve("patches")
        );
        var result = new StringBuilder();
        for (var patchesDir : patchesDirs) {
            if (Files.isDirectory(patchesDir)) {
                result.append(sha256Tree(patchesDir, ".patch"));
            }
        }
        return result.toString();
    }

    private static String computeCompatibilityHashes(Path homeDir) {
        var runtimeSrc = homeDir.resolve("runtime").resolve("src");
        if (!Files.isDirectory(runtimeSrc)) return "";
        return sha256Tree(runtimeSrc, ".java");
    }

    private static String sha256File(Path file) {
        try {
            var digester = newDigester();
            digester.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256Tree(Path dir, String extension) {
        try {
            var digester = newDigester();
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> extension == null || f.toString().endsWith(extension))
                    .sorted()
                    .forEachOrdered(f -> digestFile(digester, f));
            }
            return HexFormat.of().formatHex(digester.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static void digestFile(MessageDigest digester, Path file) {
        try {
            var relative = file.toString();
            digester.update(relative.getBytes(StandardCharsets.UTF_8));
            digester.update((byte) 0);
            var data = Files.readAllBytes(file);
            digester.update(data);
        } catch (Exception ignored) {}
    }

    private static MessageDigest newDigester() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeHash(Path file, String hash) {
        try {
            Files.writeString(file, hash);
        } catch (Exception ignored) {}
    }

    private static void writeString(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (Exception ignored) {}
    }

    private static void writeMetadata(Path file, String version) {
        try {
            var json = "{\n" +
                "  \"version\": \"" + version + "\",\n" +
                "  \"buildTime\": \"" + java.time.Instant.now() + "\",\n" +
                "  \"compiler\": \"" + detectCompilerVersion() + "\",\n" +
                "  \"cacheFormat\": 2\n" +
                "}";
            Files.writeString(file, json);
        } catch (Exception ignored) {}
    }
}
