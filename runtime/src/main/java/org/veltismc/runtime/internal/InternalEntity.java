package org.veltismc.runtime.internal;

import org.veltismc.runtime.api.EntityDefinition;

import java.util.UUID;

public interface InternalEntity {

    EntityDefinition definition();

    int id();

    UUID uniqueId();

    Type type();

    InternalWorld world();

    Position position();

    boolean onGround();

    boolean alive();

    public record Type(
        String name,
        String translationKey,
        int typeId,
        float width,
        float height,
        boolean living
    ) {
    }

    record Position(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {

        public Position withX(double x) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withY(double y) {
            return new Position(x, y, z, yaw, pitch);
        }

        public Position withZ(double z) {
            return new Position(x, z, z, yaw, pitch);
        }

        public static Position at(double x, double y, double z) {
            return new Position(x, y, z, 0.0f, 0.0f);
        }
    }
}


