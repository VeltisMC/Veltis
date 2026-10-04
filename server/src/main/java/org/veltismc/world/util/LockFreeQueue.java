package org.veltismc.world.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free multi-producer / single-consumer queue.
 *
 * <p>Used for region inboxes: many workers may post messages, exactly one worker
 * (the region owner) drains them. Enqueue is a single CAS; there is no blocking
 * anywhere in the queue itself.
 *
 * @param <T> element type
 */
public final class LockFreeQueue<T> {

    private static final class Node<T> {
        final T value;
        final AtomicReference<Node<T>> next = new AtomicReference<>();

        Node(T value) {
            this.value = value;
        }
    }

    private final Node<T> sentinel = new Node<>(null);
    private final AtomicReference<Node<T>> head = new AtomicReference<>(sentinel);
    private final AtomicReference<Node<T>> tail = new AtomicReference<>(sentinel);

    /** Producers only. Returns when the item is linked. */
    public void add(T value) {
        Node<T> node = new Node<>(value);
        while (true) {
            Node<T> t = tail.get();
            if (t.next.compareAndSet(null, node)) {
                tail.set(node);
                return;
            }
        }
    }

    /** Single consumer only. Returns the head item or {@code null} if empty. */
    public T poll() {
        while (true) {
            Node<T> h = head.get();
            Node<T> n = h.next.get();
            if (n == null) {
                return null;
            }
            if (head.compareAndSet(h, n)) {
                return n.value;
            }
        }
    }

    /** Non-destructive emptiness check. */
    public boolean hasItems() {
        return head.get().next.get() != null;
    }

    /** Approximate size; safe under concurrent producers. */
    public int size() {
        int count = 0;
        for (Node<T> n = head.get().next.get(); n != null && count < Integer.MAX_VALUE; n = n.next.get()) {
            count++;
        }
        return count;
    }
}
