package org.veltismc.veltis.runtime.provision;

import java.time.Instant;

public record MinecraftVersionManifest(
    String version,
    String downloadUrl,
    String sha256,
    long fileSize,
    Instant releaseDate
) {

    public static MinecraftVersionManifest forVersion26_2() {
        var url = System.getProperty("VeltisMC.download-url",
            "https://piston-data.mojang.com/v1/objects/0000000000000000000000000000000000000000/server.jar");
        var hash = System.getProperty("VeltisMC.server-sha256",
            "0000000000000000000000000000000000000000000000000000000000000000");
        return new MinecraftVersionManifest("26.2", url, hash, 0, Instant.parse("2026-05-15T00:00:00Z"));
    }
}



