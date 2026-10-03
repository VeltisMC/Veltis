package org.veltismc.runtime.container;

import org.veltismc.runtime.internal.InternalPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface PlayerContainer {

    InternalPlayer add(InternalPlayer player);

    Optional<InternalPlayer> remove(UUID uuid);

    Optional<InternalPlayer> remove(String username);

    Optional<InternalPlayer> byUuid(UUID uuid);

    Optional<InternalPlayer> byName(String username);

    Collection<InternalPlayer> all();

    Stream<InternalPlayer> stream();

    int count();

    boolean isEmpty();

    boolean contains(UUID uuid);

    boolean contains(String username);
}


