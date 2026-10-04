package org.veltismc.patchengine;

/**
 * A patch failure, reported with enough detail to fix the patch without turning
 * on debug logging or reading a stack trace.
 *
 * <p>Every field answers a different question a developer asks when a build
 * stops: which patch, which category, which target, which hunk, and why.
 */
public record PatchFailure(
    String patch,
    PatchCategory category,
    String target,
    String location,
    String reason,
    String minecraftVersion,
    String patchRevision
) {

    /** Builds a failure for a named patch. */
    public static PatchFailure of(VeltisPatch patch, String target, String location,
                                  String reason, String minecraftVersion) {
        return new PatchFailure(
            patch.name(), patch.category(), target, location, reason,
            minecraftVersion, patch.revision());
    }

    /** Builds a failure for something that went wrong before a patch could be named. */
    public static PatchFailure of(String patch, PatchCategory category, String target,
                                  String location, String reason, String minecraftVersion,
                                  String patchRevision) {
        return new PatchFailure(patch, category, target, location, reason,
            minecraftVersion, patchRevision);
    }

    /**
     * The developer-facing report. Rendered as a single block so a multi-line
     * reason stays inside the log format instead of leaking a stack trace.
     */
    public String render() {
        var sb = new StringBuilder();
        sb.append("[VeltisPatch] Failed to apply patch").append('\n');
        sb.append("  Patch: ").append(patch).append('\n');
        sb.append("  Category: ").append(category.directoryName()).append('\n');
        sb.append("  Target: ").append(target == null || target.isBlank() ? "<unknown>" : target).append('\n');
        sb.append("  Location: ").append(location == null || location.isBlank() ? "<unknown>" : location).append('\n');
        sb.append("  Reason: ").append(reason == null || reason.isBlank() ? "<unknown>" : reason).append('\n');
        sb.append("  Minecraft: ").append(minecraftVersion).append('\n');
        sb.append("  Patch revision: ").append(
            patchRevision == null || patchRevision.isBlank() ? "<unknown>" : patchRevision);
        return sb.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    /** Wraps this failure in the engine's exception type, message-only. */
    public PatchEngineException toException() {
        return new PatchEngineException(render());
    }
}
