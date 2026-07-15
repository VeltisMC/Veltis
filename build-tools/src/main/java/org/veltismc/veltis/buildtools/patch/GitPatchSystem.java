package org.veltismc.veltis.buildtools.patch;

import org.veltismc.veltis.buildtools.context.BuildContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class GitPatchSystem implements PatchSystem {

    private static final Pattern PATCH_FILE_PATTERN =
        Pattern.compile("^(\\d{4})-(.+)\\.patch$");
    private static final Pattern SOURCE_PATH_PATTERN =
        Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);

    @Override
    public String name() {
        return "GitPatch";
    }

    @Override
    public List<Patch> discover(BuildContext context) throws Exception {
        var patches = new ArrayList<Patch>();
        discoverFromDir(context.serverPatchesPath(), patches);
        discoverFromDir(context.apiPatchesPath(), patches);
        patches.sort(Comparator.comparing(Patch::id));
        return List.copyOf(patches);
    }

    private static void discoverFromDir(Path dir, List<Patch> out) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".patch"))
                .map(GitPatchSystem::parsePatchEntry)
                .flatMap(Optional::stream)
                .forEach(out::add);
        }
    }

    private static Optional<Patch> parsePatchEntry(Path path) {
        var fileName = path.getFileName().toString();
        var matcher = PATCH_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) return Optional.empty();
        var id = matcher.group(1) + "-" + matcher.group(2);
        var description = matcher.group(2).replace('-', ' ');
        return Optional.of(new Patch(
            id, description, Patch.PatchType.SOURCE, path, path,
            Patch.Priority.NORMAL));
    }

    @Override
    public PatchResult apply(BuildContext context, Patch patch) throws Exception {
        return apply(context, patch, context.patchedSourcePath());
    }

    @Override
    public PatchResult apply(BuildContext context, Patch patch, Path targetDirectory) throws Exception {
        var startTime = System.currentTimeMillis();
        var patchFile = patch.sourceFile();
        var projectRoot = context.workingDirectory().normalize().toAbsolutePath();
        var targetRel = projectRoot.relativize(targetDirectory.normalize().toAbsolutePath());
        var gitDir = projectRoot.resolve(".git");

        if (runGitCheck(gitDir, patchFile, targetRel.toString(), "--reverse") == 0) {
            var elapsed = System.currentTimeMillis() - startTime;
            return PatchResult.success(patch, targetDirectory, elapsed);
        }

        try {
            var exitCode = runGitApply(gitDir, patchFile, targetRel.toString());
            if (exitCode != 0) {
                var errorOutput = captureGitStderr(gitDir, patchFile, targetRel.toString());
                return PatchResult.failure(patch,
                    "git apply failed (exit " + exitCode + ") for " + patchFile.getFileName()
                    + ":\n" + errorOutput);
            }
            var elapsed = System.currentTimeMillis() - startTime;
            return PatchResult.success(patch, targetDirectory, elapsed);
        } catch (Exception e) {
            return PatchResult.failure(patch,
                "Failed to apply " + patchFile + ": " + e.getMessage());
        }
    }

    @Override
    public PatchResult rebuild(BuildContext context, Patch patch) throws Exception {
        var startTime = System.currentTimeMillis();
        var patchFile = patch.sourceFile();

        var baselineDir = context.baselineSourcePath();
        var patchedDir = context.patchedSourcePath();

        Files.createDirectories(patchFile.getParent());

        var sourcePath = extractTargetPath(patchFile);
        if (sourcePath == null) {
            return PatchResult.failure(patch, "Cannot determine source path from " + patchFile);
        }

        var baselineFile = baselineDir.resolve(sourcePath);
        var patchedFile = patchedDir.resolve(sourcePath);

        if (!Files.exists(patchedFile) && !Files.exists(baselineFile)) {
            return PatchResult.failure(patch, "Neither source nor target file exists: " + sourcePath);
        }

        var diffContent = captureDiff(baselineFile, patchedFile);
        if (diffContent == null || diffContent.isBlank()) {
            Files.deleteIfExists(patchFile);
            var elapsed = System.currentTimeMillis() - startTime;
            return PatchResult.success(patch, patchFile, elapsed);
        }

        var normalized = normalizePaths(diffContent, sourcePath);
        Files.writeString(patchFile, normalized, StandardCharsets.UTF_8);

        var elapsed = System.currentTimeMillis() - startTime;
        return PatchResult.success(patch, patchFile, elapsed);
    }

    @Override
    public List<PatchResult> rebuildAll(BuildContext context) throws Exception {
        var results = new ArrayList<PatchResult>();
        var baselineDir = context.baselineSourcePath();
        var patchedDir = context.patchedSourcePath();
        var patchDir = context.serverPatchesPath();

        if (!Files.isDirectory(baselineDir) || !Files.isDirectory(patchedDir)) {
            System.out.println("[rebuildPatches] Baseline or patched source missing");
            return List.copyOf(results);
        }

        var diffOutput = captureDiff(baselineDir, patchedDir);
        if (diffOutput == null || diffOutput.isBlank()) {
            System.out.println("[rebuildPatches] No changes detected");
            // Remove stale patches
            removeStalePatches(patchDir, java.util.Set.of());
            return List.copyOf(results);
        }

        var fileDiffs = splitByFileHeader(diffOutput);
        var changedPaths = new HashSet<String>();

        // Build map: relative source path → existing patch
        var existingPatches = buildExistingPatchMap(patchDir);

        // Determine next sequence number
        int nextSeq = existingPatches.keySet().stream()
            .mapToInt(id -> {
                try { return Integer.parseInt(id.substring(0, 4)); }
                catch (Exception e) { return 0; }
            })
            .max()
            .orElse(0) + 1;

        for (var fileDiff : fileDiffs) {
            if (fileDiff.isBlank()) continue;
            var sourcePath = parseSourcePath(fileDiff);
            if (sourcePath == null) continue;

            changedPaths.add(sourcePath);
            var normalized = normalizePaths(fileDiff, sourcePath);

            var existingId = findPatchIdForSource(existingPatches, sourcePath);
            if (existingId != null) {
                var existingPatch = existingPatches.get(existingId);
                var patchFile = existingPatch.sourceFile();
                Files.writeString(patchFile, normalized, StandardCharsets.UTF_8);

                // Validate: verify the updated patch applies cleanly to baseline
                var validation = validatePatch(context, patchFile, baselineDir, sourcePath);
                if (!validation.success()) {
                    System.out.println("[rebuildPatches] WARNING: Updated patch " + existingId
                        + " does not reapply cleanly to baseline:");
                    System.out.println("  " + validation.errorMessage().replace("\n", "\n  "));
                }

                results.add(PatchResult.success(existingPatch, patchFile, 0));
                System.out.println("[rebuildPatches] Updated: " + existingPatch.sourceFile().getFileName());
            } else {
                var desc = sourcePath.replace('/', '-').replace('\\', '-')
                    .replace(".java", "");
                var id = String.format("%04d-%s", nextSeq++, desc);
                var patchFile = patchDir.resolve(id + ".patch");
                var patch = new Patch(id, desc.replace('-', ' '),
                    Patch.PatchType.SOURCE, patchFile, patchFile, Patch.Priority.NORMAL);

                // Validate: verify the new patch applies cleanly to baseline
                var validation = validatePatch(context, patchFile, baselineDir, sourcePath);
                if (validation.success()) {
                    Files.writeString(patchFile, normalized, StandardCharsets.UTF_8);
                    results.add(PatchResult.success(patch, patchFile, 0));
                    System.out.println("[rebuildPatches] Created: " + patchFile.getFileName());
                } else {
                    System.out.println("[rebuildPatches] VALIDATION FAILED for " + id
                        + " (" + sourcePath + "):");
                    System.out.println("  " + validation.errorMessage().replace("\n", "\n  "));
                    results.add(PatchResult.failure(patch,
                        "Validation failed: " + validation.errorMessage()));
                }
            }
        }

        // Remove stale patches (patches whose target files are no longer in the diff)
        removeStalePatches(patchDir, changedPaths);

        return List.copyOf(results);
    }

    @Override
    public List<PatchResult> applyAll(BuildContext context, Path targetDirectory) throws Exception {
        var patches = discover(context);
        var sorted = patches.stream()
            .sorted(Comparator.comparingInt(p -> p.priority().order()))
            .toList();
        var results = new ArrayList<PatchResult>();
        for (var patch : sorted) {
            var result = apply(context, patch, targetDirectory);
            results.add(result);
            if (!result.success()) {
                throw new PatchException(
                    "Patch failed: " + patch.id() + " - " + result.errorMessage());
            }
        }
        return List.copyOf(results);
    }

    // ----  Validation ----
    private PatchResult validatePatch(BuildContext context, Path patchFile,
                                       Path baselineDir, String sourcePath) throws Exception {
        var tempDir = Files.createTempDirectory("veltismc-patch-validate-");
        try {
            var tempFile = tempDir.resolve(sourcePath);
            var baselineFile = baselineDir.resolve(sourcePath);
            if (Files.exists(baselineFile)) {
                Files.createDirectories(tempFile.getParent());
                Files.copy(baselineFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            var projectRoot = context.workingDirectory().normalize().toAbsolutePath();
            var tempRel = projectRoot.relativize(tempDir.normalize().toAbsolutePath());
            var gitDir = projectRoot.resolve(".git");

            var exitCode = runGitApply(gitDir, patchFile, tempRel.toString());
            if (exitCode != 0) {
                var errorOutput = captureGitStderr(gitDir, patchFile, tempRel.toString());
                return PatchResult.failure(null,
                    "Validation failed for " + patchFile + ": " + errorOutput);
            }
            return PatchResult.success(null, tempDir, 0);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    // ----  Patch file helpers ----

    private static String extractTargetPath(Path patchFile) throws Exception {
        var content = Files.readString(patchFile, StandardCharsets.UTF_8);
        var matcher = SOURCE_PATH_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static java.util.Map<String, Patch> buildExistingPatchMap(Path patchDir) throws Exception {
        var map = new java.util.LinkedHashMap<String, Patch>();
        if (!Files.isDirectory(patchDir)) return map;
        try (var files = Files.list(patchDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".patch"))
                .forEach(p -> parsePatchEntry(p).ifPresent(patch -> map.put(patch.id(), patch)));
        }
        return map;
    }

    private static String findPatchIdForSource(java.util.Map<String, Patch> patches, String sourcePath) throws Exception {
        for (var entry : patches.entrySet()) {
            var patchContent = Files.readString(entry.getValue().sourceFile(), StandardCharsets.UTF_8);
            var matcher = SOURCE_PATH_PATTERN.matcher(patchContent);
            if (matcher.find() && matcher.group(1).equals(sourcePath)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void removeStalePatches(Path patchDir, java.util.Set<String> activePaths) throws Exception {
        if (!Files.isDirectory(patchDir)) return;
        try (var files = Files.list(patchDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".patch"))
                .forEach(p -> {
                    try {
                        var content = Files.readString(p, StandardCharsets.UTF_8);
                        var matcher = SOURCE_PATH_PATTERN.matcher(content);
                        if (matcher.find()) {
                            var targetPath = matcher.group(1);
                            if (!activePaths.contains(targetPath)) {
                                Files.delete(p);
                                System.out.println("[rebuildPatches] Removed stale: "
                                    + p.getFileName());
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                });
        }
    }

    // ----  Path normalization ----

    private static String normalizePaths(String diffContent, String sourcePath) {
        var lines = diffContent.split("\n", -1);
        var sb = new StringBuilder();
        for (var line : lines) {
            if (line.startsWith("diff --git ")) {
                sb.append("diff --git a/").append(sourcePath).append(" b/").append(sourcePath);
            } else if (line.startsWith("--- ")) {
                sb.append("--- a/").append(sourcePath);
            } else if (line.startsWith("+++ ")) {
                sb.append("+++ b/").append(sourcePath);
            } else if (line.startsWith("index ")) {
                // Skip index lines to avoid churn on rebuild
                continue;
            } else {
                sb.append(line);
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    // ----  Git operations ----

    private static int runGitCheck(Path gitDir, Path patchFile, String sourceRel, String... extraArgs) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.add("--git-dir=" + gitDir.toAbsolutePath().normalize());
        cmd.add("-C");
        cmd.add(gitDir.getParent().toAbsolutePath().normalize().toString());
        cmd.add("apply");
        cmd.add("--check");
        cmd.addAll(List.of(extraArgs));
        cmd.add("--whitespace=nowarn");
        cmd.add("--directory=" + sourceRel.replace('\\', '/'));
        cmd.add(patchFile.toAbsolutePath().normalize().toString());

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true);
        var process = pb.start();
        return process.waitFor();
    }

    private static int runGitApply(Path gitDir, Path patchFile, String sourceRel) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.add("--git-dir=" + gitDir.toAbsolutePath().normalize());
        cmd.add("-C");
        cmd.add(gitDir.getParent().toAbsolutePath().normalize().toString());
        cmd.add("apply");
        cmd.add("--whitespace=nowarn");
        cmd.add("--directory=" + sourceRel.replace('\\', '/'));
        cmd.add(patchFile.toAbsolutePath().normalize().toString());

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .inheritIO();
        var process = pb.start();
        return process.waitFor();
    }

    private static String captureGitStderr(Path gitDir, Path patchFile, String sourceRel) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.add("--git-dir=" + gitDir.toAbsolutePath().normalize());
        cmd.add("-C");
        cmd.add(gitDir.getParent().toAbsolutePath().normalize().toString());
        cmd.add("apply");
        cmd.add("--check");
        cmd.add("--whitespace=nowarn");
        cmd.add("--directory=" + sourceRel.replace('\\', '/'));
        cmd.add(patchFile.toAbsolutePath().normalize().toString());

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true);
        var process = pb.start();
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            var buf = new char[8192];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            process.waitFor();
            return sb.toString().trim();
        }
    }

    private static String captureDiff(Path source, Path target) throws Exception {
        if (!Files.exists(source) || !Files.exists(target)) return "";

        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.add("diff");
        cmd.add("--no-color");
        cmd.add("--unified=3");
        cmd.add("--no-index");
        cmd.add(source.toAbsolutePath().normalize().toString());
        cmd.add(target.toAbsolutePath().normalize().toString());

        var pb = new ProcessBuilder(cmd)
            .redirectErrorStream(true);
        var process = pb.start();
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            var buf = new char[8192];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            process.waitFor();
            return sb.toString();
        }
    }

    // ----  Diff parsing ----

    private static List<String> splitByFileHeader(String diffOutput) {
        var parts = new ArrayList<String>();
        if (diffOutput == null || diffOutput.isBlank()) return parts;
        var lines = diffOutput.split("\n", -1);
        var sb = new StringBuilder();
        for (var line : lines) {
            if (line.startsWith("diff --git ")) {
                if (!sb.isEmpty()) {
                    parts.add(sb.toString());
                    sb = new StringBuilder();
                }
            }
            sb.append(line).append(System.lineSeparator());
        }
        if (!sb.isEmpty()) {
            parts.add(sb.toString());
        }
        return parts;
    }

    private static String parseSourcePath(String fileDiff) {
        var matcher = SOURCE_PATH_PATTERN.matcher(fileDiff);
        if (matcher.find()) {
            var path = matcher.group(1);
            // Strip absolute path prefix — keep only the relative source path
            // e.g., "C:/Users/.../ver/26.2/patched-source-baseline/net/minecraft/..." -> "net/minecraft/..."
            var idx = path.indexOf("net/minecraft");
            if (idx >= 0) return path.substring(idx);
            idx = path.indexOf("net/minecraft");
            if (idx >= 0) return path.substring(idx);
            // Also handle com/mojang etc
            idx = path.indexOf("com/");
            if (idx >= 0) return path.substring(idx);
            // For other paths, take the last meaningful segment
            return path;
        }
        return null;
    }

    private static void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }
}
