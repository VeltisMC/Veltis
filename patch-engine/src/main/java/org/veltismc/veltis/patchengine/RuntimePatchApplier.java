package org.veltismc.veltis.patchengine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Applies patches to decompiled source directory.
 * Uses Java-based unified diff patching (no external dependencies).
 * Patches can come from filesystem or in-memory (classpath resources).
 * Applies patches to different target files in parallel.
 */
public class RuntimePatchApplier {

    private final UnifiedDiffPatcher patcher;

    public RuntimePatchApplier() {
        this(new UnifiedDiffPatcher());
    }

    public RuntimePatchApplier(UnifiedDiffPatcher patcher) {
        this.patcher = patcher;
    }

    public List<String> applyPatches(List<PatchContent> patches, Path targetDir) throws PatchEngineException {
        // Group patches by their target file to ensure sequential application per file
        var byTarget = new ConcurrentHashMap<String, List<PatchContent>>();
        for (var patch : patches) {
            var targetFile = resolveTargetFile(patch.lines());
            byTarget.computeIfAbsent(targetFile, k -> new ArrayList<>()).add(patch);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var entry : byTarget.entrySet()) {
                var filePatches = entry.getValue();
                futures.add(executor.submit(() -> {
                    for (var patch : filePatches) {
                        patcher.applyPatchLines(patch.lines(), patch.name(), targetDir);
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (Exception e) {
            var cause = e.getCause();
            if (cause instanceof PatchEngineException pe) {
                throw pe;
            }
            throw new PatchEngineException("Failed to apply patches", cause != null ? cause : e);
        }
        return new ArrayList<>(byTarget.keySet());
    }

    private String resolveTargetFile(List<String> patchLines) {
        for (var line : patchLines) {
            if (line.startsWith("+++ b/")) {
                return line.substring(6);
            }
        }
        return "unknown";
    }

    public record PatchContent(String name, List<String> lines) {
    }
}
