package org.veltismc.world.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free object pool (Treiber stack) with a hard capacity.
 *
 * <p>Used for chunks, chunk sections, and save buffers to minimize allocation
 * and GC pressure. Release past capacity silently discards the object.
 *
 * @param <T> pooled type
 */
public final class ObjectPool<T> {

    @FunctionalInterface
    public interface Factory<T> {
        T create();
    }

    private static final class Node<T> {
        final T value;
        final AtomicReference<Node<T>> next = new AtomicReference<>();

        Node(T value) {
            this.value = value;
        }
    }

    private final Factory<T> factory;
    private final int maxSize;
    private final AtomicReference<Node<T>> top = new AtomicReference<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger inUse = new AtomicInteger();

    public ObjectPool(Factory<T> factory, int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0");
        }
        this.factory = factory;
        this.maxSize = maxSize;
    }

    /** Borrows an instance, creating a fresh one when the pool is empty. */
    public T borrow() {
        Node<T> t = top.get();
        while (t != null && !top.compareAndSet(t, t.next.get())) {
            t = top.get();
        }
        if (t == null) {
            created.incrementAndGet();
            inUse.incrementAndGet();
            return factory.create();
        }
        size.decrementAndGet();
        inUse.incrementAndGet();
        return t.value;
    }

    /** Returns an instance to the pool, or discards it if the pool is full. */
    public void release(T value) {
        int reservedSize;
        do {
            reservedSize = size.get();
            if (reservedSize >= maxSize) {
                inUse.decrementAndGet();
                return;
            }
        } while (!size.compareAndSet(reservedSize, reservedSize + 1));

        Node<T> node = new Node<>(value);
        Node<T> t;
        do {
            t = top.get();
            node.next.set(t);
        } while (!top.compareAndSet(t, node));
        inUse.decrementAndGet();
    }

    /** Total instances created since the pool was built. */
    public int created() {
        return created.get();
    }

    /** Instances currently borrowed. */
    public int inUse() {
        return inUse.get();
    }

    public int size() {
        return size.get();
    }
}
