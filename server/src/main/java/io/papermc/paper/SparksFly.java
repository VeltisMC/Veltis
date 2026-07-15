package io.papermc.paper;

import io.papermc.paper.configuration.GlobalConfiguration;

import io.papermc.paper.util.MCUtil;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.lucko.spark.paper.api.Compatibility;
import me.lucko.spark.paper.api.PaperClassLookup;
import me.lucko.spark.paper.api.PaperScheduler;
import me.lucko.spark.paper.api.PaperSparkModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

// It's like electricity.
public final class SparksFly {
    public static final String ID = "spark";
    public static final String COMMAND_NAME = "spark";

    private static final String PREFER_SPARK_PLUGIN_PROPERTY = "veltis.preferSparkPlugin";

    private static final int SPARK_YELLOW = 0xffc93a;

    private final Logger logger;
    private final PaperSparkModule spark;
    private final ConcurrentLinkedQueue<Runnable> mainThreadTaskQueue;
    private MinecraftServer minecraftServer;

    private boolean enabled;
    private boolean disabledInConfigurationWarningLogged;

    public SparksFly(final Server server) {
        this.mainThreadTaskQueue = new ConcurrentLinkedQueue<>();
        this.logger = Logger.getLogger(ID);
        this.logger.log(Level.INFO, "This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling");
        this.spark = PaperSparkModule.create(Compatibility.VERSION_1_0, server, this.logger, new PaperScheduler() {
            @Override
            public void executeAsync(final Runnable runnable) {
                MCUtil.scheduleAsyncTask(this.catching(runnable, "asynchronous"));
            }

            @Override
            public void executeSync(final Runnable runnable) {
                if (server.isPrimaryThread()) {
                    this.catching(runnable, "synchronous").run();
                } else if (SparksFly.this.minecraftServer != null) {
                    SparksFly.this.minecraftServer.execute(this.catching(runnable, "synchronous"));
                } else {
                    SparksFly.this.mainThreadTaskQueue.offer(this.catching(runnable, "synchronous"));
                }
            }

            private Runnable catching(final Runnable runnable, final String type) {
                return () -> {
                    try {
                        runnable.run();
                    } catch (final Throwable t) {
                        SparksFly.this.logger.log(Level.SEVERE, "An exception was encountered while executing a " + type + " spark task", t);
                    }
                };
            }
        }, (PaperClassLookup) Class::forName);
    }

    public void executeMainThreadTasks() {
        Runnable task;
        while ((task = this.mainThreadTaskQueue.poll()) != null) {
            task.run();
        }
    }

    public void enableEarlyIfRequested() {
        if (!isPluginPreferred() && shouldEnableImmediately()) {
            this.enable();
        }
    }

    public void enableBeforePlugins() {
        if (!isPluginPreferred()) {
            this.enable();
        }
    }

    public void enableAfterPlugins(final Server server) {
        final boolean isPluginPreferred = isPluginPreferred();
        final boolean isPluginEnabled = isPluginEnabled(server);
        if (!isPluginPreferred || !isPluginEnabled) {
            if (isPluginPreferred && !this.enabled) {
                this.logger.log(Level.INFO, "The spark plugin has been preferred but was not loaded. The bundled spark profiler will enabled instead.");
            }
            this.enable();
        }
    }

    private void enable() {
        if (!this.enabled) {
            if (GlobalConfiguration.get().spark.enabled) {
                this.enabled = true;
                this.spark.enable();
            } else {
                if (!this.disabledInConfigurationWarningLogged) {
                    this.logger.log(Level.INFO, "The spark profiler will not be enabled because it is currently disabled in the configuration.");
                    this.disabledInConfigurationWarningLogged = true;
                }
            }
        }
    }

    public void disable() {
        if (this.enabled) {
            this.spark.disable();
            this.enabled = false;
        }
    }

    public void registerCommandBeforePlugins(final Server server) {
        if (!isPluginPreferred()) {
            this.registerCommand(server);
        }
    }

    public void registerCommandAfterPlugins(final Server server) {
        if ((!isPluginPreferred() || !isPluginEnabled(server)) && server.getCommandMap().getCommand(COMMAND_NAME) == null) {
            this.registerCommand(server);
        }
    }

    private void registerCommand(final Server server) {
        server.getCommandMap().register(COMMAND_NAME, "veltis", new CommandImpl(COMMAND_NAME, this.spark.getPermissions(), this));
    }

    public void setMinecraftServer(final MinecraftServer server) {
        this.minecraftServer = server;
    }

    public void tickStart() {
        this.spark.onServerTickStart();
    }

    public void tickEnd(final double duration) {
        this.spark.onServerTickEnd(duration);
    }

    void executeCommand(final CommandSender sender, final String[] args) {
        this.spark.executeCommand(sender, args);
    }

    List<String> tabComplete(final CommandSender sender, final String[] args) {
        return this.spark.tabComplete(sender, args);
    }

    public static boolean isPluginPreferred() {
        return Boolean.getBoolean(PREFER_SPARK_PLUGIN_PROPERTY);
    }

    private static boolean isPluginEnabled(final Server server) {
        return server.getPluginManager().isPluginEnabled(ID);
    }

    private static boolean shouldEnableImmediately() {
        return GlobalConfiguration.get().spark.enableImmediately;
    }

    public static final class CommandImpl extends Command {
        private final SparksFly spark;

        CommandImpl(final String name, final Collection<String> permissions, final SparksFly spark) {
            super(name);
            this.setPermission(String.join(";", permissions));
            this.spark = spark;
        }

        @Override
        public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
            if (spark.enabled) {
                spark.executeCommand(sender, args);
            } else {
                sender.sendMessage(Component.text("The spark profiler is currently disabled.", TextColor.color(SPARK_YELLOW)));
            }
            return true;
        }

        @Override
        public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) throws IllegalArgumentException {
            if (spark.enabled) {
                return spark.tabComplete(sender, args);
            }
            return List.of();
        }
    }
}
