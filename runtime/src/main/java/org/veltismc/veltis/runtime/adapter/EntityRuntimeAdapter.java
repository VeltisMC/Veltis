package org.veltismc.veltis.runtime.adapter;

import org.veltismc.veltis.server.model.entity.EntityIdentifier;
import org.veltismc.veltis.server.model.entity.EntityState;
import org.veltismc.veltis.server.model.entity.EntityType;
import org.veltismc.veltis.server.model.entity.InternalEntity;
import org.veltismc.veltis.server.model.world.InternalWorld;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts Mojang's {@code net.minecraft.world.entity.Entity}
 * to VeltisMC's {@link InternalEntity} model.
 *
 * <p>All Mojang class references are isolated inside this adapter
 * using reflection. No Mojang types appear in the public API.
 */
public final class EntityRuntimeAdapter {

    private static final Logger LOG = System.getLogger(EntityRuntimeAdapter.class.getName());

    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";

    private final ClassLoader classLoader;
    private final WorldRuntimeAdapter worldAdapter;

    /**
     * Creates a new entity adapter.
     *
     * @param worldAdapter the world adapter for resolving entity worlds
     */
    public EntityRuntimeAdapter(WorldRuntimeAdapter worldAdapter) {
        this.worldAdapter = Objects.requireNonNull(worldAdapter, "worldAdapter");
        this.classLoader = getClass().getClassLoader();
    }

    /**
     * Adapts a Mojang Entity to a VeltisMC InternalEntity.
     *
     * @param mojangEntity the Mojang Entity instance
     * @return the adapted entity model
     */
    public InternalEntity adapt(Object mojangEntity) {
        Objects.requireNonNull(mojangEntity, "mojangEntity");

        var identifier = extractIdentifier(mojangEntity);
        var type = extractType(mojangEntity);
        var world = extractWorld(mojangEntity);
        var position = extractPosition(mojangEntity);
        var onGround = extractOnGround(mojangEntity);

        return new AdaptedEntity(identifier, type, world, position, onGround, mojangEntity);
    }

    private EntityIdentifier extractIdentifier(Object mojangEntity) {
        try {
            var getId = mojangEntity.getClass().getMethod("getId");
            var id = (int) getId.invoke(mojangEntity);

            var getUuid = mojangEntity.getClass().getMethod("getUUID");
            var uuid = (UUID) getUuid.invoke(mojangEntity);

            return EntityIdentifier.of(id, uuid);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract entity identifier", e);
            return EntityIdentifier.fromUuid(UUID.randomUUID());
        }
    }

    private EntityType extractType(Object mojangEntity) {
        try {
            var getType = mojangEntity.getClass().getMethod("getType");
            var entityType = getType.invoke(mojangEntity);

            var builtInRegistryHolder = entityType.getClass().getMethod("builtInRegistryHolder");
            var holder = builtInRegistryHolder.invoke(entityType);

            var keyMethod = holder.getClass().getMethod("key");
            var resourceKey = keyMethod.invoke(holder);

            var locationMethod = resourceKey.getClass().getMethod("location");
            var resourceLocation = locationMethod.invoke(resourceKey);

            var pathMethod = resourceLocation.getClass().getMethod("getPath");
            var path = (String) pathMethod.invoke(resourceLocation);

            var namespaceMethod = resourceLocation.getClass().getMethod("getNamespace");
            var namespace = (String) namespaceMethod.invoke(resourceLocation);

            var name = namespace + ":" + path;
            var translationKey = "entity." + namespace + "." + path;

            var width = extractFloat(entityType, "getWidth", 0.6f);
            var height = extractFloat(entityType, "getHeight", 1.8f);

            return new EntityType(name, translationKey, 0, width, height, true);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract entity type, using default", e);
            return EntityType.named("minecraft:unknown");
        }
    }

    private float extractFloat(Object obj, String methodName, float defaultValue) {
        try {
            var method = obj.getClass().getMethod(methodName);
            return ((Number) method.invoke(obj)).floatValue();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private InternalWorld extractWorld(Object mojangEntity) {
        try {
            var levelMethod = mojangEntity.getClass().getMethod("level");
            var level = levelMethod.invoke(mojangEntity);
            if (level != null) {
                return worldAdapter.adapt(level);
            }
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract entity world");
        }
        return null;
    }

    private InternalEntity.Position extractPosition(Object mojangEntity) {
        try {
            var getX = mojangEntity.getClass().getMethod("getX");
            var getY = mojangEntity.getClass().getMethod("getY");
            var getZ = mojangEntity.getClass().getMethod("getZ");
            var getYaw = mojangEntity.getClass().getMethod("getYaw");
            var getPitch = mojangEntity.getClass().getMethod("getPitch");

            var x = (double) getX.invoke(mojangEntity);
            var y = (double) getY.invoke(mojangEntity);
            var z = (double) getZ.invoke(mojangEntity);
            var yaw = ((Number) getYaw.invoke(mojangEntity)).floatValue();
            var pitch = ((Number) getPitch.invoke(mojangEntity)).floatValue();

            return new InternalEntity.Position(x, y, z, yaw, pitch);
        } catch (Exception e) {
            LOG.log(Level.DEBUG, "Could not extract entity position");
            return InternalEntity.Position.ZERO;
        }
    }

    private boolean extractOnGround(Object mojangEntity) {
        try {
            var method = mojangEntity.getClass().getMethod("onGround");
            return (boolean) method.invoke(mojangEntity);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Internal adapted entity implementation.
     */
    private static final class AdaptedEntity implements InternalEntity {

        private final EntityIdentifier identifier;
        private final EntityType type;
        private final InternalWorld world;
        private final Object mojangEntity;

        private volatile Position position;
        private volatile boolean onGround;
        private volatile EntityState state;

        AdaptedEntity(
            EntityIdentifier identifier,
            EntityType type,
            InternalWorld world,
            Position position,
            boolean onGround,
            Object mojangEntity
        ) {
            this.identifier = identifier;
            this.type = type;
            this.world = world;
            this.position = position;
            this.onGround = onGround;
            this.mojangEntity = mojangEntity;
            this.state = EntityState.SPAWNED;
        }

        @Override
        public EntityIdentifier identifier() { return identifier; }

        @Override
        public int entityId() { return identifier.entityId(); }

        @Override
        public UUID uniqueId() { return identifier.uniqueId(); }

        @Override
        public EntityType type() { return type; }

        @Override
        public InternalWorld world() { return world; }

        @Override
        public Position position() { return position; }

        @Override
        public void position(Position position) { this.position = position; }

        @Override
        public boolean onGround() { return onGround; }

        @Override
        public void onGround(boolean onGround) { this.onGround = onGround; }

        @Override
        public boolean alive() { return state == EntityState.SPAWNED; }

        @Override
        public EntityState state() { return state; }

        @Override
        public void state(EntityState newState) { this.state = newState; }

        @Override
        public boolean isActive() { return state == EntityState.SPAWNED; }

        @Override
        public Optional<org.veltismc.veltis.server.internal.InternalEntity> serverEntity() {
            return Optional.empty();
        }
    }
}



