package org.veltismc.runtime.api;

import java.util.Collection;
import java.util.UUID;

public interface WorldDefinition {

    UUID uniqueId();

    String name();

    Environment environment();

    long seed();

    int maxHeight();

    int minHeight();

    int logicalHeight();

    long time();

    long gameTime();

    boolean storm();

    boolean thundering();

    int raintime();

    int thunderTime();

    boolean pvpAllowed();

    boolean monstersAllowed();

    boolean animalsAllowed();

    Collection<? extends ChunkDefinition> loadedChunks();

    Collection<? extends EntityDefinition> entities();

    enum Environment {
        NORMAL,
        NETHER,
        THE_END,
        CUSTOM
    }
}


