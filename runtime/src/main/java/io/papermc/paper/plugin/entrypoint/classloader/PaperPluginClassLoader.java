package io.papermc.paper.plugin.entrypoint.classloader;

/**
 * Stub class for spark-paper compatibility.
 * PaperPluginClassLoader is not used in VeltisMC's plugin system,
 * but spark-paper's PaperClassSourceLookup references it.
 */
public class PaperPluginClassLoader {
    public org.bukkit.plugin.java.JavaPlugin loadedJavaPlugin;
}
