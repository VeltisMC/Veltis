package org.bukkit.craftbukkit.command;

import org.veltismc.veltis.command.brigadier.bukkit.VeltisBrigForwardingMap;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;

public class CraftCommandMap extends SimpleCommandMap {

    public CraftCommandMap(Server server) {
        super(server, VeltisBrigForwardingMap.INSTANCE);
    }

    public Map<String, Command> getKnownCommands() {
        return this.knownCommands;
    }
}
