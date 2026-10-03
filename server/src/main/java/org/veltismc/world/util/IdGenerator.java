package org.veltismc.world.util;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe monotonic id generator. */
public final class IdGenerator {

    private final AtomicLong next = new AtomicLong(1);

    public long next() {
        return next.getAndIncrement();
    }
}
