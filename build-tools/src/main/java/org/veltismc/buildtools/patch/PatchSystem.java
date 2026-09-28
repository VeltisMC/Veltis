package org.veltismc.buildtools.patch;

import org.veltismc.buildtools.context.BuildContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public interface PatchSystem {

    String name();

    List<Patch> discover(BuildContext context) throws Exception;

    PatchResult apply(BuildContext context, Patch patch) throws Exception;

    default PatchResult apply(BuildContext context, Patch patch, Path targetDirectory) throws Exception {
        return apply(context, patch);
    }

    default List<PatchResult> applyAll(BuildContext context) throws Exception {
        return applyAll(context, context.patchedSourcePath());
    }

    default List<PatchResult> applyAll(BuildContext context, Path targetDirectory) throws Exception {
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

    record PatchResult(
        boolean success,
        Patch patch,
        Path outputPath,
        long durationMs,
        String errorMessage
    ) {

        public static PatchResult success(Patch patch, Path output, long durationMs) {
            return new PatchResult(true, patch, output, durationMs, null);
        }

        public static PatchResult failure(Patch patch, String error) {
            return new PatchResult(false, patch, null, 0, error);
        }
    }

    PatchResult rebuild(BuildContext context, Patch patch) throws Exception;

    default List<PatchResult> rebuildAll(BuildContext context) throws Exception {
        var patches = discover(context);
        var sorted = patches.stream()
            .sorted(Comparator.comparingInt(p -> p.priority().order()))
            .toList();
        var results = new ArrayList<PatchResult>();
        for (var patch : sorted) {
            var result = rebuild(context, patch);
            results.add(result);
        }
        return List.copyOf(results);
    }

    class PatchException extends RuntimeException {
        public PatchException(String message) {
            super(message);
        }

        public PatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}


