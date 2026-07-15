package org.veltismc.veltis.plugin.lifecycle;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.AbstractLifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class VeltisLifecycleEventManager implements LifecycleEventManager<Plugin> {

    private final JavaPlugin owner;
    private final BooleanSupplier registrationCheck;

    public VeltisLifecycleEventManager(final JavaPlugin owner, final BooleanSupplier registrationCheck) {
        this.owner = owner;
        this.registrationCheck = registrationCheck;
    }

    @Override
    public void registerEventHandler(final LifecycleEventHandlerConfiguration<? super Plugin> handlerConfiguration) {
        if (!this.registrationCheck.getAsBoolean()) {
            return;
        }
        final AbstractLifecycleEventHandlerConfiguration config = (AbstractLifecycleEventHandlerConfiguration) handlerConfiguration;
        config.registerFrom((LifecycleEventOwner) this.owner);
    }
}
