package org.veltismc.patchengine;

import java.util.Locale;

/**
 * The three patch categories, and nothing else.
 *
 * <p>Category decides two things: the order patches are applied in, and which
 * Veltis source set the patched output belongs to. It deliberately does
 * <em>not</em> change how a patch addresses its targets — every category emits
 * {@code +++ b/<path relative to the patched workspace root>}, so one patch
 * engine serves all three and there is a single path format to learn.
 *
 * <p>Order is {@code code} then {@code data} then {@code modules}: code changes
 * are authored against the decompiled server, data patches add or adjust
 * resources, and modules wire VeltisMC's own modules in, so they must see the
 * result of both.
 */
public enum PatchCategory {

    /** Changes to Minecraft's own source. */
    CODE("code", 1),
    /** Changes to Minecraft's resources (data packs, assets, metadata). */
    DATA("data", 2),
    /** Changes to VeltisMC's own module integration. */
    MODULES("modules", 3);

    private final String directoryName;
    private final int order;

    PatchCategory(String directoryName, int order) {
        this.directoryName = directoryName;
        this.order = order;
    }

    /** The directory under {@code server/Shulker/} that holds this category. */
    public String directoryName() {
        return directoryName;
    }

    /** Lower sorts first. */
    public int order() {
        return order;
    }

    /** The source set a patch in this category contributes to. */
    public String sourceSet() {
        return switch (this) {
            case CODE -> "minecraft";
            case DATA -> "minecraftResources";
            case MODULES -> "minecraftModules";
        };
    }

    public static PatchCategory ofDirectory(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        for (var category : values()) {
            if (category.directoryName.equals(normalized)) {
                return category;
            }
        }
        throw new PatchEngineException(
            "[VeltisPatch] Unknown patch category directory: " + name
                + "\n  Reason: expected one of code, data, modules");
    }
}
