package io.papermc.paper.command.brigadier.bukkit;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.Command;

public class BukkitBrigForwardingMap extends HashMap<String, Command> {

    public static final BukkitBrigForwardingMap INSTANCE = new BukkitBrigForwardingMap();

    private CommandDispatcher<CommandSourceStack> dispatcher;
    private boolean bridgeReady;
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("VeltisMC.BrigBridge");

    BukkitBrigForwardingMap() {
    }

    public void initBridge(CommandDispatcher<CommandSourceStack> nmsDispatcher) {
        if (nmsDispatcher == null || bridgeReady) return;
        this.dispatcher = nmsDispatcher;
        bridgeReady = true;
    }

    public void reSyncAll() {
        if (!bridgeReady || dispatcher == null) return;
        for (var entry : entrySet()) {
            var key = entry.getKey();
            if (!key.contains(":")) {
                registerInDispatcher(key, entry.getValue());
            }
        }
    }

    @Override
    public Command put(String key, Command value) {
        if (key == null) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        Command old = super.get(lower);
        super.put(lower, value);
        if (bridgeReady && dispatcher != null && !lower.contains(":")) {
            registerInDispatcher(lower, value);
        }
        return old;
    }

    @Override
    public void putAll(Map<? extends String, ? extends Command> m) {
        for (Entry<? extends String, ? extends Command> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    private void registerInDispatcher(String name, Command command) {
        try {
            var node = BukkitCommandNode.of(name, command);
            registerNode(node);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[VeltisMC] Failed to register /{0} in Brigadier", name);
        }
    }

    public void registerInDispatcher(com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> node) {
        registerNode(node);
    }

    private void registerNode(com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> node) {
        if (!bridgeReady || dispatcher == null) return;
        try {
            var root = dispatcher.getRoot();
            var addChild = root.getClass().getMethod("addChild", com.mojang.brigadier.tree.CommandNode.class);
            addChild.invoke(root, node);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[VeltisMC] Failed to register /{0} in Brigadier", node.getLiteral());
        }
    }

    @Override
    public Command get(Object key) {
        return super.get(key != null ? key.toString().toLowerCase(Locale.ROOT) : null);
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && super.containsKey(((String) key).toLowerCase(Locale.ROOT));
    }

    @Override
    public Command remove(Object key) {
        if (!(key instanceof String k)) return null;
        String lower = k.toLowerCase(Locale.ROOT);
        Command old = super.get(lower);
        super.remove(lower);
        if (bridgeReady && dispatcher != null) {
            try {
                var root = dispatcher.getRoot();
                var nodeClass = root.getClass().getSuperclass();
                var childrenField = nodeClass.getDeclaredField("children");
                var literalsField = nodeClass.getDeclaredField("literals");
                childrenField.setAccessible(true);
                literalsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var children = (java.util.Map<String, com.mojang.brigadier.tree.CommandNode<net.minecraft.commands.CommandSourceStack>>) childrenField.get(root);
                @SuppressWarnings("unchecked")
                var literals = (java.util.Map<String, com.mojang.brigadier.tree.LiteralCommandNode<net.minecraft.commands.CommandSourceStack>>) literalsField.get(root);
                children.remove(lower);
                literals.remove(lower);
            } catch (Exception ignored) {}
        }
        return old;
    }
}
