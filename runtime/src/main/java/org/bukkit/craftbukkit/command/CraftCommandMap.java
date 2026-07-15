package org.bukkit.craftbukkit.command;

import io.papermc.paper.command.brigadier.bukkit.BukkitBrigForwardingMap;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;

public class CraftCommandMap extends SimpleCommandMap {

    public CraftCommandMap(Server server) {
        super(server, BukkitBrigForwardingMap.INSTANCE);
    }

    public Map<String, Command> getKnownCommands() {
        return this.knownCommands;
    }
}
