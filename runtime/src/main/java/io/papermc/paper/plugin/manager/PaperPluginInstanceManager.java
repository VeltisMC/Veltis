package io.papermc.paper.plugin.manager;

import com.google.common.base.Preconditions;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.loader.PaperClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage;
import io.papermc.paper.plugin.provider.entrypoint.DependencyContext;
import io.papermc.paper.plugin.provider.util.ProviderUtil;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommandYamlParser;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.PluginClassLoader;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;

class PaperPluginInstanceManager {

    private static final Pattern VALID_PLUGIN_NAME = Pattern.compile("^[A-Za-z0-9 _.-]+$");

    private static final DependencyContext NOOP_DEPENDENCY_CONTEXT = new DependencyContext() {
        @Override public boolean isTransitiveDependency(PluginMeta plugin, PluginMeta depend) { return true; }
        @Override public boolean hasDependency(String dependency) { return true; }
    };

    private final List<Plugin> plugins = new ArrayList<>();
    private final Map<String, Plugin> lookupNames = new HashMap<>();
    private final Map<String, Plugin> pluginProvidedNames = new HashMap<>();

    private final PluginManager pluginManager;
    private final CommandMap commandMap;
    private Server server;

    private MutableGraph<String> dependencyGraph = GraphBuilder.directed().build();

    public void setServer(Server server) {
        this.server = server;
    }

    public PaperPluginInstanceManager(PluginManager pluginManager, CommandMap commandMap, Server server) {
        this.commandMap = commandMap;
        this.server = server;
        this.pluginManager = pluginManager;
    }

    public @Nullable Plugin getPlugin(@NotNull String name) {
        Plugin plugin = this.lookupNames.get(name.replace(' ', '_').toLowerCase(Locale.ENGLISH));
        if (plugin == null) {
            plugin = this.pluginProvidedNames.get(name.toLowerCase(Locale.ENGLISH));
        }
        return plugin;
    }

    public @NotNull Plugin[] getPlugins() {
        return this.plugins.toArray(new Plugin[0]);
    }

    public boolean isPluginEnabled(@NotNull String name) {
        Plugin plugin = this.getPlugin(name);
        return this.isPluginEnabled(plugin);
    }

    public synchronized boolean isPluginEnabled(@Nullable Plugin plugin) {
        if ((plugin != null) && (this.plugins.contains(plugin))) {
            return plugin.isEnabled();
        } else {
            return false;
        }
    }

    public void loadPlugin(Plugin provided) {
        PluginMeta configuration = provided.getPluginMeta();
        this.plugins.add(provided);
        this.lookupNames.put(configuration.getName().toLowerCase(Locale.ENGLISH), provided);
        for (String providedPlugin : configuration.getProvidedPlugins()) {
            this.pluginProvidedNames.putIfAbsent(providedPlugin.toLowerCase(Locale.ENGLISH), provided);
        }
        this.dependencyGraph.addNode(configuration.getName());
    }

    public @Nullable Plugin loadPlugin(@NotNull Path path) throws InvalidPluginException {
        if (!Files.exists(path) || !path.toString().endsWith(".jar")) {
            return null;
        }

        JarFile jar = null;
        try {
            jar = new JarFile(path.toFile());
        } catch (IOException e) {
            throw new InvalidPluginException(e);
        }

        PluginDescriptionFile description;
        JarEntry pluginYmlEntry = jar.getJarEntry("paper-plugin.yml");
        if (pluginYmlEntry != null) {
            try (InputStream in = jar.getInputStream(pluginYmlEntry)) {
                description = parsePaperPluginYml(in);
            } catch (Exception e) {
                throw new InvalidPluginException("Failed to parse paper-plugin.yml", e);
            }
        } else {
            JarEntry legacyEntry = jar.getJarEntry("plugin.yml");
            if (legacyEntry == null) {
                try { jar.close(); } catch (IOException ignored) {}
                return null;
            }
            try (InputStream in = jar.getInputStream(legacyEntry)) {
                description = new PluginDescriptionFile(in);
            } catch (Exception e) {
                throw new InvalidPluginException("Failed to parse plugin.yml", e);
            }
        }

        String pluginName = description.getName();
        if (!VALID_PLUGIN_NAME.matcher(pluginName).matches()) {
            throw new InvalidPluginException("Plugin name '" + pluginName + "' does not match " + VALID_PLUGIN_NAME);
        }

        try {
            File file = path.toFile();
            File dataFolder = new File(file.getParentFile(), pluginName);
            ClassLoader parentLoader = this.getClass().getClassLoader();

            ClassLoader libraryLoader = null;
            String pluginLoaderClass = description.getPaperPluginLoader();
            if (pluginLoaderClass != null) {
                PaperClasspathBuilder classpathBuilder = new PaperClasspathBuilder();
                try (URLClassLoader tempLoader = new URLClassLoader(new URL[]{file.toURI().toURL()}, parentLoader)) {
                    PluginLoader loader = ProviderUtil.loadClass(pluginLoaderClass, PluginLoader.class, tempLoader);
                    loader.classloader(classpathBuilder);
                }
                List<Path> libPaths = classpathBuilder.buildLibraryPaths();
                if (!libPaths.isEmpty()) {
                    URL[] libUrls = new URL[libPaths.size()];
                    for (int i = 0; i < libPaths.size(); i++) {
                        libUrls[i] = libPaths.get(i).toUri().toURL();
                    }
                    libraryLoader = new URLClassLoader(libUrls, parentLoader);
                }
            }

            PluginClassLoader loader = new PluginClassLoader(
                parentLoader, description, dataFolder, file, libraryLoader, jar, NOOP_DEPENDENCY_CONTEXT
            );

            JavaPlugin plugin = loader.getPlugin();

            plugin.onLoad();

            plugins.add(plugin);
            lookupNames.put(pluginName.toLowerCase(Locale.ENGLISH).replace(' ', '_'), plugin);
            for (var provided : description.getProvidedPlugins()) {
                pluginProvidedNames.putIfAbsent(provided.toLowerCase(Locale.ENGLISH), plugin);
            }

            return plugin;
        } catch (Exception e) {
            try { jar.close(); } catch (IOException ignored) {}
            throw new InvalidPluginException("Failed to load plugin from " + path, e);
        }
    }

    public @NotNull Plugin[] loadPlugins(@NotNull File[] files) {
        // Simple approach: load each plugin individually, skip batch dependency resolution
        List<Plugin> loaded = new ArrayList<>();
        for (var file : files) {
            try {
                var plugin = this.loadPlugin(file.toPath());
                if (plugin != null) loaded.add(plugin);
            } catch (Exception e) {
                if (this.server != null) {
                    this.server.getLogger().log(Level.SEVERE, "Could not load plugin " + file.getName(), e);
                }
            }
        }
        return loaded.toArray(new Plugin[0]);
    }

    public @NotNull Plugin[] loadPlugins(@NotNull Path directory) {
        Preconditions.checkArgument(Files.isDirectory(directory), "Directory must be a directory");
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) return new Plugin[0];
        return loadPlugins(files);
    }

    public void disablePlugins() {
        Plugin[] plugins = this.getPlugins();
        for (int i = plugins.length - 1; i >= 0; i--) {
            this.disablePlugin(plugins[i]);
        }
    }

    public void clearPlugins() {
        synchronized (this) {
            this.disablePlugins();
            this.plugins.clear();
            this.lookupNames.clear();
            this.pluginProvidedNames.clear();
            this.dependencyGraph = GraphBuilder.directed().build();
            HandlerList.unregisterAll();
        }
    }
    public synchronized void enablePlugin(@NotNull Plugin plugin) {
        if (plugin.isEnabled()) return;

        try {
            plugin.getLogger().info("Enabling " + plugin.getPluginMeta().getDisplayName());

            if (plugin.getClass().getClassLoader() instanceof ConfiguredPluginClassLoader classLoader) {
                if (PaperClassLoaderStorage.instance().registerUnsafePlugin(classLoader)) {
                    this.server.getLogger().log(Level.WARNING, "Enabled plugin with unregistered ConfiguredPluginClassLoader " + plugin.getPluginMeta().getDisplayName());
                }
            }

            // Register commands and permissions from plugin description
            if (plugin.getPluginMeta() instanceof PluginDescriptionFile desc) {
                List<Command> bukkitCommands = PluginCommandYamlParser.parse(plugin);
                if (!bukkitCommands.isEmpty()) {
                    this.commandMap.registerAll(plugin.getPluginMeta().getName(), bukkitCommands);
                }
                this.pluginManager.addPermissions(desc.getPermissions());
            }

            JavaPlugin jPlugin = (JavaPlugin) plugin;
            try {
                jPlugin.setEnabled(true);
            } catch (Throwable ex) {
                this.server.getLogger().log(Level.SEVERE, "Error occurred while enabling " + plugin.getPluginMeta().getDisplayName() + " (Is it up to date?)", ex);
                this.server.getPluginManager().disablePlugin(jPlugin);
                return;
            }

            this.server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
        } catch (Throwable ex) {
            this.server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while enabling " + plugin.getPluginMeta().getDisplayName() + " (Is it up to date?)", ex);
        }

        HandlerList.bakeAll();
    }

    public synchronized void disablePlugin(@NotNull Plugin plugin) {
        if (!(plugin instanceof JavaPlugin javaPlugin)) {
            throw new IllegalArgumentException("Only expects java plugins.");
        }
        if (!plugin.isEnabled()) return;

        String pluginName = plugin.getPluginMeta().getDisplayName();

        try {
            plugin.getLogger().info("Disabling " + pluginName);
            this.server.getPluginManager().callEvent(new PluginDisableEvent(plugin));
            try {
                javaPlugin.setEnabled(false);
            } catch (Throwable ex) {
                this.server.getLogger().log(Level.SEVERE, "Error occurred while disabling " + pluginName, ex);
            }

            ClassLoader classLoader = plugin.getClass().getClassLoader();
            if (classLoader instanceof ConfiguredPluginClassLoader configuredPluginClassLoader) {
                try {
                    configuredPluginClassLoader.close();
                } catch (IOException ex) {
                    this.server.getLogger().log(Level.WARNING, "Error closing the classloader for '" + pluginName + "'", ex);
                }
                PaperClassLoaderStorage.instance().unregisterClassloader(configuredPluginClassLoader);
            }
        } catch (Throwable ex) {
            this.server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while disabling " + pluginName + " (Is it up to date?)", ex);
        }

        try { this.server.getScheduler().cancelTasks(plugin); } catch (Throwable ignored) {}
        try { this.server.getServicesManager().unregisterAll(plugin); } catch (Throwable ignored) {}
        try { HandlerList.unregisterAll(plugin); } catch (Throwable ignored) {}
        try { this.server.getMessenger().unregisterIncomingPluginChannel(plugin); } catch (Throwable ignored) {}
        try { this.server.getMessenger().unregisterOutgoingPluginChannel(plugin); } catch (Throwable ignored) {}
        try {
            if (!this.server.isStopping()) {
                for (World world : this.server.getWorlds()) {
                    world.removePluginChunkTickets(plugin);
                }
            }
        } catch (Throwable ignored) {}
    }

    public boolean isTransitiveDepend(@NotNull PluginMeta plugin, @NotNull PluginMeta depend) {
        if (depend == null || plugin == null) return false;
        return dependencyGraph.hasEdgeConnecting(
            plugin.getName().toLowerCase(Locale.ENGLISH),
            depend.getName().toLowerCase(Locale.ENGLISH));
    }

    public boolean hasDependency(String pluginIdentifier) {
        return this.getPlugin(pluginIdentifier) != null;
    }

    @ApiStatus.Internal
    public MutableGraph<String> getDependencyGraph() {
        return this.dependencyGraph;
    }

    private List<String> resolveDependencyOrder(Set<String> pluginNames,
                                                 Map<String, Collection<String>> hardDeps,
                                                 Map<String, Collection<String>> softDeps) {
        MutableGraph<String> graph = GraphBuilder.directed().allowsSelfLoops(false).build();
        for (var name : pluginNames) graph.addNode(name);
        for (var entry : hardDeps.entrySet()) {
            var plugin = entry.getKey();
            for (var dep : entry.getValue()) {
                var depKey = dep.toLowerCase(Locale.ENGLISH);
                if (pluginNames.contains(depKey)) {
                    graph.putEdge(plugin, depKey);
                }
            }
        }
        for (var entry : softDeps.entrySet()) {
            var plugin = entry.getKey();
            for (var dep : entry.getValue()) {
                var depKey = dep.toLowerCase(Locale.ENGLISH);
                if (pluginNames.contains(depKey)) {
                    graph.putEdge(plugin, depKey);
                }
            }
        }

        var sorted = new ArrayList<String>();
        var visited = new HashSet<String>();
        var visiting = new HashSet<String>();

        for (var node : pluginNames) {
            if (!visited.contains(node)) {
                topologicalSort(graph, node, visited, visiting, sorted);
            }
        }

        return sorted;
    }

    private void topologicalSort(MutableGraph<String> graph, String node,
                                  Set<String> visited, Set<String> visiting,
                                  List<String> sorted) {
        if (visiting.contains(node) || visited.contains(node)) return;
        visiting.add(node);
        for (var successor : graph.successors(node)) {
            topologicalSort(graph, successor, visited, visiting, sorted);
        }
        visiting.remove(node);
        visited.add(node);
        sorted.add(node);
    }

    private static String str(Map<?, ?> data, String key, String def) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val) : def;
    }

    @SuppressWarnings("unchecked")
    private PluginDescriptionFile parsePaperPluginYml(InputStream in) throws Exception {
        var yaml = new org.yaml.snakeyaml.Yaml();
        var data = yaml.<Map<String, Object>>loadAs(in, Map.class);
        if (data == null) throw new Exception("Empty paper-plugin.yml");

        var name = str(data, "name", "");
        var main = str(data, "main", "");
        var version = str(data, "version", "1.0");
        var description = str(data, "description", "");
        var website = str(data, "website", null);
        var prefix = str(data, "prefix", null);
        var loadStr = str(data, "load", null);
        var loadOrder = "STARTUP".equalsIgnoreCase(loadStr) ? PluginLoadOrder.STARTUP : PluginLoadOrder.POSTWORLD;
        var apiVersion = str(data, "api-version", "1.19");

        List<String> authors = new ArrayList<>();
        var authorsRaw = data.get("authors");
        if (authorsRaw instanceof List<?> l) {
            for (var e : l) { if (e != null) authors.add(String.valueOf(e)); }
        } else if (authorsRaw != null) authors.add(String.valueOf(authorsRaw));

        List<String> contributors = new ArrayList<>();
        var contribRaw = data.get("contributors");
        if (contribRaw instanceof List<?> l) {
            for (var e : l) { if (e != null) contributors.add(String.valueOf(e)); }
        } else if (contribRaw != null) contributors.add(String.valueOf(contribRaw));

        List<String> depend = new ArrayList<>(), softDepend = new ArrayList<>(), loadBefore = new ArrayList<>(), provides = new ArrayList<>();
        var depsRaw = data.get("dependencies");
        if (depsRaw instanceof Map<?, ?> deps) {
            for (var entry : deps.entrySet()) {
                var depName = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> depCfg) {
                    var required = "true".equalsIgnoreCase(str(depCfg, "required", "false"));
                    var joinClasspath = "true".equalsIgnoreCase(str(depCfg, "join-classpath", "false"));
                    if (joinClasspath) {
                        if (required) depend.add(depName);
                        else softDepend.add(depName);
                    }
                    var loadDir = str(depCfg, "load", null);
                    if ("AFTER".equalsIgnoreCase(loadDir)) loadBefore.add(depName);
                } else {
                    depend.add(depName);
                }
            }
        }
        if (depsRaw == null) {
            var rawDepend = data.get("depend");
            if (rawDepend instanceof List<?> l) { for (var e : l) { if (e != null) depend.add(String.valueOf(e)); } }
            var rawSoft = data.get("softdepend");
            if (rawSoft instanceof List<?> l) { for (var e : l) { if (e != null) softDepend.add(String.valueOf(e)); } }
            var rawLoadBefore = data.get("loadbefore");
            if (rawLoadBefore instanceof List<?> l) { for (var e : l) { if (e != null) loadBefore.add(String.valueOf(e)); } }
        }

        var rawProvides = data.get("provides");
        if (rawProvides instanceof List<?> l) { for (var e : l) { if (e != null) provides.add(String.valueOf(e)); } }

        // Parse commands section
        Map<String, Map<String, Object>> commands = new HashMap<>();
        var rawCommands = data.get("commands");
        if (rawCommands instanceof Map<?, ?> cmdMap) {
            for (var cmdEntry : cmdMap.entrySet()) {
                var cmdName = String.valueOf(cmdEntry.getKey());
                Map<String, Object> cmdConfig = new HashMap<>();
                if (cmdEntry.getValue() instanceof Map<?, ?> m) {
                    for (var propEntry : m.entrySet()) {
                        cmdConfig.put(String.valueOf(propEntry.getKey()), propEntry.getValue());
                    }
                }
                commands.put(cmdName, cmdConfig);
            }
        }

        // Parse permissions section
        List<Permission> permissionsList = new ArrayList<>();
        var rawPermissions = data.get("permissions");
        if (rawPermissions instanceof Map<?, ?> permMap) {
            for (var permEntry : permMap.entrySet()) {
                var permName = String.valueOf(permEntry.getKey());
                var permDefault = PermissionDefault.OP;
                Map<String, Boolean> permChildren = new HashMap<>();
                if (permEntry.getValue() instanceof Map<?, ?> m) {
                    var defaultVal = m.get("default");
                    if (defaultVal != null) {
                        try { permDefault = PermissionDefault.valueOf(String.valueOf(defaultVal).toUpperCase()); } catch (Exception ignored) {}
                    }
                    if (m.get("children") instanceof Map<?, ?> children) {
                        for (var child : children.entrySet()) {
                            permChildren.put(String.valueOf(child.getKey()),
                                child.getValue() instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(child.getValue())));
                        }
                    }
                }
                permissionsList.add(new Permission(permName, permDefault, permChildren));
            }
        }

        var descriptionFile = new PluginDescriptionFile(name, name, provides, main, "",
            depend, softDepend, loadBefore, version, commands,
            description, authors, contributors, website, prefix, loadOrder,
            permissionsList, PermissionDefault.OP,
            java.util.Set.of(), apiVersion, List.of());

        // Set plugin loader class from paper-plugin.yml's "loader" field
        var loaderClass = str(data, "loader", null);
        if (loaderClass != null && !loaderClass.isEmpty()) {
            try {
                var field = PluginDescriptionFile.class.getDeclaredField("paperPluginLoader");
                field.setAccessible(true);
                field.set(descriptionFile, loaderClass);
            } catch (Exception ignored) {}
        }

        return descriptionFile;
    }
}
