package org.veltismc.veltis;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;

import java.util.Collection;
import java.util.Collections;

public final class VeltisContainerHandle implements IContainerHandle {

    @Override
    public String getId() {
        return "VeltisMC";
    }

    @Override
    public String getDescription() {
        return "VeltisMC Server Container";
    }

    @Override
    public String getAttribute(String name) {
        return null;
    }

    @Override
    public Collection<IContainerHandle> getNestedContainers() {
        return Collections.emptyList();
    }
}
