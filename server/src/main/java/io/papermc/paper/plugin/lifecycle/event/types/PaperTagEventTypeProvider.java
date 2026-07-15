package io.papermc.paper.plugin.lifecycle.event.types;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.tag.PostFlattenTagRegistrar;
import io.papermc.paper.tag.PreFlattenTagRegistrar;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

@DefaultQualifier(NonNull.class)
public class PaperTagEventTypeProvider implements TagEventTypeProvider {

    @Override
    public <T> PrioritizableLifecycleEventType.Simple<BootstrapContext, ReloadableRegistrarEvent<PreFlattenTagRegistrar<T>>> preFlatten(final RegistryKey<T> registryKey) {
        throw new UnsupportedOperationException("Tags lifecycle events not yet supported by VeltisMC");
    }

    @Override
    public <T> PrioritizableLifecycleEventType.Simple<BootstrapContext, ReloadableRegistrarEvent<PostFlattenTagRegistrar<T>>> postFlatten(final RegistryKey<T> registryKey) {
        throw new UnsupportedOperationException("Tags lifecycle events not yet supported by VeltisMC");
    }
}
