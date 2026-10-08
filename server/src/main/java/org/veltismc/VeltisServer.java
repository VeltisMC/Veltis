package org.veltismc;

import org.jspecify.annotations.Nullable;
import org.veltismc.api.Server;
import org.veltismc.runtime.VeltisBootstrap; 

public final class VeltisServer implements Server {

    /**
     * @return The server version. Null if invalid; its unlikely to happen
     */
    @Override
    public @Nullable String getVersion() {
        Object handle = VeltisBootstrap.MINECRAFT_SERVER;

        if (handle instanceof net.minecraft.server.MinecraftServer mcServer)
            return mcServer.getServerModName(); 

        return null;
    }

    @Override
    public void broadcast(String message) {
        // TODO
    }
}
