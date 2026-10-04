package org.veltismc.runtime.container;

import org.veltismc.runtime.internal.InternalWorld;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface WorldContainer {

    InternalWorld add(InternalWorld world);

    Optional<InternalWorld> remove(UUID uuid);

    Optional<InternalWorld> remove(String name);

    Optional<InternalWorld> byUuid(UUID uuid);

    Optional<InternalWorld> byName(String name);

    Optional<InternalWorld> byIndex(int index);

    Collection<InternalWorld> all();

    Stream<InternalWorld> stream();

    int count();

    boolean isEmpty();

    boolean contains(UUID uuid);

    boolean contains(String name);
}


