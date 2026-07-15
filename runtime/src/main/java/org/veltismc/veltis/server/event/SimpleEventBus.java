package org.veltismc.veltis.server.event;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class SimpleEventBus implements EventBus {

    private static final Logger LOG = System.getLogger(SimpleEventBus.class.getName());

    private final ConcurrentHashMap<Class<? extends Event>, CopyOnWriteArrayList<ListenerHandle>> registry;
    private final ConcurrentHashMap<Class<? extends Event>, ListenerHandle[]> handlerCache;
    private final AtomicInteger registrationCounter;

    public SimpleEventBus() {
        this.registry = new ConcurrentHashMap<>();
        this.handlerCache = new ConcurrentHashMap<>();
        this.registrationCounter = new AtomicInteger(0);
    }

    @Override
    public <E extends Event> ListenerRegistration subscribe(Class<E> eventType, EventListener<E> listener) {
        var handle = new ListenerHandle(eventType, listener);
        registry.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handle);
        handlerCache.remove(eventType);
        return handle;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> void publish(E event) {
        var eventClass = (Class<? extends Event>) event.getClass();
        var cached = handlerCache.get(eventClass);
        if (cached != null) {
            for (var handle : cached) {
                try {
                    ((EventListener<E>) handle.listener()).onEvent(event);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "Listener threw during event dispatch", e);
                }
            }
            return;
        }
        var listeners = registry.get(eventClass);
        if (listeners == null || listeners.isEmpty()) return;
        var snapshot = listeners.toArray(new ListenerHandle[0]);
        handlerCache.put(eventClass, snapshot);
        for (var handle : snapshot) {
            try {
                ((EventListener<E>) handle.listener()).onEvent(event);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "Listener threw during event dispatch", e);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Event> void publishOrThrow(E event) {
        var eventClass = (Class<? extends Event>) event.getClass();
        var cached = handlerCache.get(eventClass);
        if (cached != null) {
            for (var handle : cached) {
                ((EventListener<E>) handle.listener()).onEvent(event);
            }
            return;
        }
        var listeners = registry.get(eventClass);
        if (listeners == null || listeners.isEmpty()) return;
        var snapshot = listeners.toArray(new ListenerHandle[0]);
        handlerCache.put(eventClass, snapshot);
        for (var handle : snapshot) {
            ((EventListener<E>) handle.listener()).onEvent(event);
        }
    }

    @Override
    public int listenerCount() {
        int count = 0;
        for (var list : registry.values()) {
            count += list.size();
        }
        return count;
    }

    @Override
    public int listenerCount(Class<? extends Event> eventType) {
        var listeners = registry.get(eventType);
        return listeners != null ? listeners.size() : 0;
    }

    @Override
    public void clear() {
        registry.clear();
        handlerCache.clear();
    }

    @Override
    public boolean hasListeners(Class<? extends Event> eventType) {
        var listeners = registry.get(eventType);
        return listeners != null && !listeners.isEmpty();
    }

    private final class ListenerHandle implements ListenerRegistration {
        private final Class<? extends Event> eventType;
        private final EventListener<?> listener;
        private final int id;
        private volatile boolean active;

        ListenerHandle(Class<? extends Event> eventType, EventListener<?> listener) {
            this.eventType = eventType;
            this.listener = listener;
            this.id = registrationCounter.incrementAndGet();
            this.active = true;
        }

        EventListener<?> listener() {
            return listener;
        }

        @Override
        public boolean unsubscribe() {
            if (!active) return false;
            active = false;
            var listeners = registry.get(eventType);
            if (listeners != null) {
                var removed = listeners.remove(this);
                if (listeners.isEmpty()) {
                    registry.remove(eventType, listeners);
                }
                handlerCache.remove(eventType);
                return removed;
            }
            return false;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
