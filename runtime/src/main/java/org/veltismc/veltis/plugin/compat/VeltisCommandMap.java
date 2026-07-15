package org.veltismc.veltis.plugin.compat;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.command.CraftCommandMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VeltisCommandMap extends CraftCommandMap {

    public VeltisCommandMap(Server server) {
        super(server);
    }

    public void registerKnownCommand(String name, Command command) {
        knownCommands.put(name.toLowerCase(Locale.ROOT), command);
    }

    public void removeKnownCommand(String name) {
        if (name != null) {
            knownCommands.remove(name.toLowerCase(Locale.ROOT));
        }
    }
}
