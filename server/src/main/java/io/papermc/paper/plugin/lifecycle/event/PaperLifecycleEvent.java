package io.papermc.paper.plugin.lifecycle.event;

public interface PaperLifecycleEvent extends LifecycleEvent {

    default void invalidate() {
    }
}
