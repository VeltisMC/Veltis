package org.veltismc.veltis.api.definition;

import java.util.UUID;

public interface EntityDefinition {

    int id();

    UUID uniqueId();

    EntityType type();

    WorldDefinition world();

    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    boolean onGround();

    boolean alive();

    interface EntityType {

        String name();

        String translationKey();

        int typeId();

        float width();

        float height();

        boolean living();
    }
}


