package io.papermc.paper.command.brigadier.bukkit;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.server.TabCompleteEvent;

public class BukkitCommandNode extends LiteralCommandNode<CommandSourceStack> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("VeltisMC.BukkitCommandNode");
    private final Command command;

    private BukkitCommandNode(String literal, Command command, BukkitBrigCommand bukkitBrigCommand) {
        super(
            literal, bukkitBrigCommand, source -> {
                CommandSender sender = resolveSender(source);
                return sender == null || command.testPermissionSilent(sender);
            },
            null, null, false
        );
        this.command = command;
    }

    public static BukkitCommandNode of(String name, Command command) {
        BukkitBrigCommand bukkitBrigCommand = new BukkitBrigCommand(command, name);
        BukkitCommandNode commandNode = new BukkitCommandNode(name, command, bukkitBrigCommand);
        commandNode.addChild(
            RequiredArgumentBuilder.<CommandSourceStack, String>argument("args", StringArgumentType.greedyString())
                .suggests(new BukkitBrigSuggestionProvider(command, name))
                .executes(bukkitBrigCommand).build()
        );
        return commandNode;
    }

    public Command getBukkitCommand() {
        return this.command;
    }

    static CommandSender resolveSender(CommandSourceStack source) {
        try {
            var entity = source.getEntity();
            if (entity instanceof ServerPlayer player) {
                var uuid = player.getUUID();
                var bukkitPlayer = Bukkit.getPlayer(uuid);
                if (bukkitPlayer != null) return bukkitPlayer;
                // Fallback: look up from server
                var server = Bukkit.getServer();
                try {
                    var getPlayer = server.getClass().getMethod("getPlayer", java.util.UUID.class);
                    var result = getPlayer.invoke(server, uuid);
                    if (result instanceof CommandSender cs) return cs;
                } catch (Exception ignored) {}
            }
            // If not a player, use console sender
            return Bukkit.getConsoleSender();
        } catch (Exception e) {
            return Bukkit.getConsoleSender();
        }
    }

    public static class BukkitBrigCommand implements com.mojang.brigadier.Command<CommandSourceStack> {

        private final Command command;
        private final String literal;

        BukkitBrigCommand(Command command, String literal) {
            this.command = command;
            this.literal = literal;
        }

        @Override
        public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            CommandSender sender = resolveSender(context.getSource());
            String content = context.getRange().get(context.getInput());
            String[] args = org.apache.commons.lang3.StringUtils.split(content, ' ');
            try {
                this.command.execute(sender, this.literal, Arrays.copyOfRange(args, 1, args.length));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Command /{0} threw: {1}", new Object[]{this.literal, e.getMessage()});
            }
            return 1;
        }
    }

    static class BukkitBrigSuggestionProvider implements SuggestionProvider<CommandSourceStack> {

        private final Command command;
        private final String literal;

        BukkitBrigSuggestionProvider(Command command, String literal) {
            this.command = command;
            this.literal = literal;
        }

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) throws CommandSyntaxException {
            CommandSender sender = resolveSender(context.getSource());
            String[] args = builder.getRemaining().split(" ", -1);

            List<String> results = null;
            Location pos = null;
            if (sender instanceof Player p) pos = p.getLocation();

            try {
                results = this.command.tabComplete(sender, this.literal, args, pos != null ? pos.clone() : null);
            } catch (Exception ex) {
                sender.sendMessage("An internal error occurred while attempting to tab-complete this command");
                LOG.log(Level.SEVERE, "Exception when " + sender.getName() + " attempted to tab complete " + builder.getRemaining(), ex);
            }

            if (sender instanceof Player player) {
                try {
                    TabCompleteEvent tabEvent = new org.bukkit.event.server.TabCompleteEvent(player, builder.getInput(), results != null ? results : new ArrayList<>(), true, pos);
                    if (!tabEvent.callEvent()) {
                        results = null;
                    } else {
                        results = tabEvent.getCompletions();
                    }
                } catch (Exception ignored) {}
            }

            if (results == null) {
                return builder.buildFuture();
            }

            builder = builder.createOffset(builder.getInput().lastIndexOf(' ') + 1);
            for (String s : results) {
                builder.suggest(s);
            }
            return builder.buildFuture();
        }
    }
}
