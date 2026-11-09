package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and installs the complete platform directory from a signed release ZIP. */
final class NativeCommitInstaller {

    static final String BUILD_INFO = "native-build-info.json";
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_BUILD_INFO_BYTES = 1024L * 1024L;

    record InstallState(boolean valid, String detail) {}

    private NativeCommitInstaller() {}

    static InstallState validate(Path platformDir, String commit,
            NativeCommitManifest.PlatformArtifact artifact) {
        try {
            var infoPath = platformDir.resolve(BUILD_INFO);
            if (!Files.isRegularFile(infoPath)) {
                return new InstallState(false, "installed build metadata is missing");
            }
            if (Files.size(infoPath) > MAX_BUILD_INFO_BYTES) {
                return new InstallState(false, "installed build metadata exceeds its size limit");
            }
            var info = parseBuildInfo(Files.readAllBytes(infoPath), artifact.platform(), commit);
            verifyBuildInfo(info, commit, artifact);
            for (var module : artifact.modules()) {
                var library = platformDir.resolve(module.name()).resolve(module.library());
                if (!Files.isRegularFile(library)) {
                    return new InstallState(false, "installed module " + module.name() + " is missing");
                }
                if (!module.sha256().equals(sha256(library))) {
                    return new InstallState(false, "installed module " + module.name() + " checksum mismatch");
                }
                var problem = NativeLoader.checkBinary(library, artifact.platform());
                if (problem != null) {
                    return new InstallState(false, "installed module " + module.name() + ": " + problem);
                }
            }
            return new InstallState(true, "verified commit-pinned installation");
        } catch (IOException | RuntimeException e) {
            return new InstallState(false, "installed native contents are invalid: " + describe(e));
        }
    }

    static void install(Path platformDir, Path archive, String commit,
            NativeCommitManifest.PlatformArtifact artifact) throws IOException {
        var archiveDigest = sha256(archive);
        if (!archiveDigest.equals(artifact.sha256())) {
            throw new IOException("the cached native ZIP checksum does not match the signed manifest");
        }
        var files = readArchive(Files.readAllBytes(archive), artifact);
        var info = parseBuildInfo(files.remove(BUILD_INFO), artifact.platform(), commit);
        verifyBuildInfo(info, commit, artifact);

        Files.createDirectories(platformDir.getParent());
        var staging = platformDir.resolveSibling(platformDir.getFileName() + ".staging-" + UUID.randomUUID());
        var backup = platformDir.resolveSibling(platformDir.getFileName() + ".backup-" + UUID.randomUUID());
        var previousInstallationMoved = false;
        Files.createDirectory(staging);
        try {
            for (var module : artifact.modules()) {
                var contents = files.get(module.name() + "/" + module.library());
                if (contents == null) {
                    throw new IOException("ZIP is missing module " + module.name() + "/" + module.library());
                }
                var moduleDir = staging.resolve(module.name());
                Files.createDirectories(moduleDir);
                var stagedLibrary = moduleDir.resolve(module.library());
                Files.write(stagedLibrary, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            var stagedInfo = staging.resolve(BUILD_INFO);
            Files.write(stagedInfo, info.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            var stagedState = validate(staging, commit, artifact);
            if (!stagedState.valid()) {
                throw new IOException("staged native contents did not verify: " + stagedState.detail());
            }

            if (Files.exists(platformDir)) {
                moveAtomically(platformDir, backup);
                previousInstallationMoved = true;
            }
            try {
                moveAtomically(staging, platformDir);
            } catch (IOException installFailure) {
                if (previousInstallationMoved) {
                    try {
                        moveAtomically(backup, platformDir);
                    } catch (IOException restoreFailure) {
                        installFailure.addSuppressed(restoreFailure);
                    }
                }
                throw installFailure;
            }
            if (previousInstallationMoved) {
                deleteTree(backup);
            }
        } finally {
            deleteTree(staging);
        }
        var installed = validate(platformDir, commit, artifact);
        if (!installed.valid()) {
            throw new IOException("installed native contents did not verify: " + installed.detail());
        }
    }

    private record BuildInfo(byte[] bytes, JsonObject root) {}

    private static BuildInfo parseBuildInfo(byte[] bytes, String platform, String commit) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BUILD_INFO_BYTES) {
            throw new IOException("native build metadata is missing or oversized");
        }
        final JsonObject root;
        try {
            var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("native build metadata is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("native build metadata is invalid JSON", e);
        }
        requireString(root, "commit", commit);
        requireString(root, "shortCommit", commit.substring(0, 7));
        requireString(root, "platform", platform);
        var parts = platform.split("-", 2);
        if (parts.length != 2) {
            throw new IOException("invalid platform in signed manifest");
        }
        requireString(root, "os", parts[0]);
        requireString(root, "architecture", parts[1]);
        return new BuildInfo(bytes, root);
    }

    private static void verifyBuildInfo(BuildInfo info, String commit,
            NativeCommitManifest.PlatformArtifact artifact) throws IOException {
        var modulesJson = info.root().get("modules");
        if (modulesJson == null || !modulesJson.isJsonArray()
                || modulesJson.getAsJsonArray().size() != artifact.modules().size()) {
            throw new IOException("native build metadata module list differs from the signed manifest");
        }
        var declared = new HashMap<String, NativeCommitManifest.Module>();
        for (var value : modulesJson.getAsJsonArray()) {
            if (!value.isJsonObject()) {
                throw new IOException("native build metadata has an invalid module entry");
            }
            var object = value.getAsJsonObject();
            var name = string(object, "name");
            if (!NativeLoader.isValidModuleName(name) || declared.containsKey(name)) {
                throw new IOException("native build metadata has an invalid or duplicate module " + name);
            }
            var abi = number(object, "abiVersion");
            declared.put(name, new NativeCommitManifest.Module(name,
                    string(object, "library"), string(object, "sha256"), abi,
                    string(object, "nativeVersion")));
        }
        for (var module : artifact.modules()) {
            var claimed = declared.get(module.name());
            if (!module.equals(claimed)) {
                throw new IOException("native build metadata for module " + module.name()
                        + " differs from the signed manifest");
            }
        }
    }

    private static Map<String, byte[]> readArchive(byte[] bytes,
            NativeCommitManifest.PlatformArtifact artifact) throws IOException {
        var expected = new HashSet<String>();
        expected.add(BUILD_INFO);
        for (var module : artifact.modules()) {
            expected.add(module.name() + "/" + module.library());
        }
        var result = new HashMap<String, byte[]>();
        var seen = new HashSet<String>();
        var rootSeen = false;
        long total = 0;
        int count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("native ZIP exceeds the " + MAX_ENTRIES + "-entry limit");
                }
                var name = entry.getName();
                if (name.indexOf('\\') >= 0 || name.startsWith("/") || name.contains("../")
                        || name.equals("..") || name.contains("//")) {
                    throw new IOException("unsafe ZIP entry path '" + name + "'");
                }
                var prefix = artifact.platform() + "/";
                if (!name.startsWith(prefix)) {
                    throw new IOException("ZIP entry is outside the expected root " + artifact.platform());
                }
                var relative = name.substring(prefix.length());
                if (entry.isDirectory()) {
                    if (relative.isEmpty()) {
                        rootSeen = true;
                        continue;
                    }
                    if (!(artifact.modules().stream()
                            .anyMatch(module -> relative.equals(module.name() + "/")))) {
                        throw new IOException("unexpected directory in native ZIP: " + name);
                    }
                    continue;
                }
                if (!expected.contains(relative) || !seen.add(relative)) {
                    throw new IOException("unexpected or duplicate native ZIP entry '" + name + "'");
                }
                long limit = relative.equals(BUILD_INFO)
                        ? MAX_BUILD_INFO_BYTES : NativeManifest.MAX_ARCHIVE_BYTES;
                var content = readEntry(zip, name, limit);
                total += content.length;
                if (total > NativeManifest.MAX_ARCHIVE_BYTES) {
                    throw new IOException("native ZIP contents exceed the size limit");
                }
                result.put(relative, content);
            }
        }
        if (!seen.equals(expected)) {
            var missing = new HashSet<>(expected);
            missing.removeAll(seen);
            throw new IOException("native ZIP is missing required entries: " + missing);
        }
        if (!rootSeen) {
            throw new IOException("native ZIP is missing its " + artifact.platform() + "/ root directory entry");
        }
        for (var module : artifact.modules()) {
            var library = result.get(module.name() + "/" + module.library());
            if (!module.sha256().equals(sha256(library))) {
                throw new IOException("module " + module.name() + " checksum does not match the signed manifest");
            }
        }
        return result;
    }

    private static byte[] readEntry(ZipInputStream zip, String name, long limit) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("ZIP entry " + name + " exceeds its size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void requireString(JsonObject object, String field, String expected) throws IOException {
        if (!expected.equals(string(object, field))) {
            throw new IOException("native build metadata " + field + " does not match the expected value");
        }
    }

    private static String string(JsonObject object, String field) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) {
            throw new IOException("native build metadata is missing string " + field);
        }
        return object.get(field).getAsString();
    }

    private static int number(JsonObject object, String field) throws IOException {
        try {
            if (!object.has(field) || !object.get(field).isJsonPrimitive()
                    || !object.getAsJsonPrimitive(field).isNumber()) {
                throw new NumberFormatException("not numeric");
            }
            return object.get(field).getAsInt();
        } catch (NumberFormatException e) {
            throw new IOException("native build metadata has invalid " + field, e);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (var item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException ignored) {
            // Staging is never used as an installation source after this call.
        }
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
