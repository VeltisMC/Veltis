package org.veltismc.veltis.command.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandRegistrationFlag;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.bukkit.BukkitBrigForwardingMap;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.registrar.PaperRegistrar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public class VeltisCommandsRegistrar implements Commands, PaperRegistrar<LifecycleEventOwner> {

    private final CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher;
    private LifecycleEventOwner currentContext;

    public VeltisCommandsRegistrar(final CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        throw new UnsupportedOperationException("Paper CommandSourceStack dispatcher not available in VeltisMC");
    }

    @Override
    public void setCurrentContext(final @Nullable LifecycleEventOwner owner) {
        this.currentContext = owner;
    }

    @Override
    public void invalidate() {
        this.currentContext = null;
    }

    private PluginMeta resolveMeta() {
        if (this.currentContext != null) {
            return this.currentContext.getPluginMeta();
        }
        return null;
    }

    @Override
    public Set<String> register(final LiteralCommandNode<CommandSourceStack> node, final @Nullable String description, final Collection<String> aliases) {
        throw new UnsupportedOperationException(
            "LiteralCommandNode registration with Paper CommandSourceStack is not supported in VeltisMC. "
            + "Use register(String, BasicCommand) instead."
        );
    }

    @Override
    public Set<String> register(final PluginMeta pluginMeta, final LiteralCommandNode<CommandSourceStack> node, final @Nullable String description, final Collection<String> aliases) {
        throw new UnsupportedOperationException(
            "LiteralCommandNode registration with Paper CommandSourceStack is not supported in VeltisMC. "
            + "Use register(PluginMeta, String, BasicCommand) instead."
        );
    }

    @Override
    public Set<String> registerWithFlags(final PluginMeta pluginMeta, final LiteralCommandNode<CommandSourceStack> node, final @Nullable String description, final Collection<String> aliases, final Set<CommandRegistrationFlag> flags) {
        throw new UnsupportedOperationException(
            "LiteralCommandNode registration with Paper CommandSourceStack is not supported in VeltisMC. "
            + "Use register(PluginMeta, String, BasicCommand) instead."
        );
    }

    @Override
    public Set<String> register(final String label, final @Nullable String description, final Collection<String> aliases, final BasicCommand basicCommand) {
        return this.register(this.resolveMeta(), label, description, aliases, basicCommand);
    }

    @Override
    public Set<String> register(final PluginMeta pluginMeta, final String label, final @Nullable String description, final Collection<String> aliases, final BasicCommand basicCommand) {
        final Set<String> registered = new HashSet<>();

        final com.mojang.brigadier.Command<net.minecraft.commands.CommandSourceStack> brigCommand = ctx -> {
            final VeltisCommandSourceStack paperStack = new VeltisCommandSourceStack(ctx.getSource());
            basicCommand.execute(paperStack, getArgs(ctx));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        };

        final LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> builder =
            LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal(label)
            .requires(src -> {
                try {
                    return basicCommand.canUse(new VeltisCommandSourceStack(src).getSender());
                } catch (final Exception e) {
                    return false;
                }
            })
            .executes(brigCommand)
            .then(
                RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("args", StringArgumentType.greedyString())
                    .suggests((ctx, build) -> {
                        final Collection<String> suggestions = basicCommand.suggest(
                            new VeltisCommandSourceStack(ctx.getSource()), getArgs(ctx)
                        );
                        if (suggestions == null) return build.buildFuture();
                        for (final String s : suggestions) {
                            build.suggest(s);
                        }
                        return build.buildFuture();
                    })
                    .executes(brigCommand)
            );

        final LiteralCommandNode<net.minecraft.commands.CommandSourceStack> node = builder.build();
        BukkitBrigForwardingMap.INSTANCE.registerInDispatcher(node);
        registered.add(label);

        for (final String alias : aliases) {
            if (alias.equals(label)) continue;
            final LiteralCommandNode<net.minecraft.commands.CommandSourceStack> aliasNode = new LiteralCommandNode<>(
                alias, node.getCommand(), node.getRequirement(),
                node, node.getRedirectModifier(), node.isFork()
            );
            BukkitBrigForwardingMap.INSTANCE.registerInDispatcher(aliasNode);
            registered.add(alias);
        }

        return Collections.unmodifiableSet(registered);
    }

    private static String[] getArgs(final CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        try {
            final String args = ctx.getArgument("args", String.class);
            return args.split(" ", -1);
        } catch (final Exception e) {
            return new String[0];
        }
    }
}
