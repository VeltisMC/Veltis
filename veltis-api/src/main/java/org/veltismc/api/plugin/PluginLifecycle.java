package org.veltismc.api.plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PluginLifecycle {
    private final List<Runnable> disableHooks = new CopyOnWriteArrayList<>();

    /**
     * Registers a callback to execute when the server stops or unloads this plugin
     */
    public void onDisable(java.util.function.Consumer<PluginLifecycle> callback) {
        // We pass 'this' back into the consumer so the developer can write: _ -> { ... }
        disableHooks.add(() -> callback.accept(this));
    }

    /**
     * INTERNAL: Executed by the server's PluginLoader during shutdown
     */
    public void executeShutdown() {
        for (Runnable hook : disableHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                // we dont want a broken plugin to stop the whole server from crashing,
                // so we catch failures silently per-hook.
                System.getLogger("VeltisLifecycle")
                    .log(System.Logger.Level.ERROR, "Error executing plugin shutdown hook", t);
            }
        }
        disableHooks.clear();
    }
}