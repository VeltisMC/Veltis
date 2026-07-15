package org.veltismc.veltis;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

@NullMarked
@ApiStatus.Internal
public final class VeltisServerBuildInfo implements ServerBuildInfo {

    private static final Key BRAND_ID = Key.key("veltismc", "veltis");
    private static final String BRAND_NAME = "VeltisMC";
    private static final String VERSION = "1.0.0-SNAPSHOT";
    private static final String MC_VERSION = "26.2";
    private static final String MC_VERSION_NAME = "1.21.4";
    private static final Instant BUILD_TIME = Instant.now();

    @Override
    public Key brandId() {
        return BRAND_ID;
    }

    @Override
    public boolean isBrandCompatible(Key brandId) {
        return BRAND_ID.equals(brandId) || brandId.equals(Key.key("papermc", "paper"));
    }

    @Override
    public String brandName() {
        return BRAND_NAME;
    }

    @Override
    public String minecraftVersionId() {
        return MC_VERSION;
    }

    @Override
    public String minecraftVersionName() {
        return MC_VERSION_NAME;
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.empty();
    }

    @Override
    public Instant buildTime() {
        return BUILD_TIME;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.of("main");
    }

    @Override
    public Optional<String> gitCommit() {
        try {
            var gitDir = new java.io.File(".git");
            if (gitDir.isDirectory()) {
                var head = new java.io.File(gitDir, "HEAD");
                if (head.isFile()) {
                    var ref = new String(java.nio.file.Files.readAllBytes(head.toPath())).trim();
                    if (ref.startsWith("ref: ")) {
                        var refFile = new java.io.File(gitDir, ref.substring(5));
                        if (refFile.isFile()) {
                            var hash = new String(java.nio.file.Files.readAllBytes(refFile.toPath())).trim();
                            return Optional.of(hash.substring(0, Math.min(7, hash.length())));
                        }
                    } else {
                        return Optional.of(ref.substring(0, Math.min(7, ref.length())));
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    @Override
    public String asString(StringRepresentation representation) {
        return switch (representation) {
            case VERSION_SIMPLE -> MC_VERSION + "-" + VERSION;
            case VERSION_FULL -> MC_VERSION + "-" + VERSION
                + " (" + BRAND_NAME + " " + gitCommit().map(c -> "@" + c).orElse("") + ")";
        };
    }
}
