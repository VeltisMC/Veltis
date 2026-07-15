package org.veltismc.veltis.inventory;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;
import org.veltismc.veltis.VeltisPlayerSender;
import org.veltismc.veltis.util.NmsReflection;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VeltisEntityProxy {

    private static final System.Logger LOG = System.getLogger(VeltisEntityProxy.class.getName());

    private VeltisEntityProxy() {}

    private static final NmsReflection.ClassEntry ENTITY;
    private static final NmsReflection.ClassEntry ENTITY_TYPES;
    private static final NmsReflection.ClassEntry SPAWN_REASON;
    private static final NmsReflection.ClassEntry LEVEL;
    private static final NmsReflection.ClassEntry ITEM_STACK_NMS;
    private static final NmsReflection.ClassEntry ITEM_ENTITY;
    private static final NmsReflection.ClassEntry VEC3;
    private static final NmsReflection.ClassEntry AABB;
    private static final NmsReflection.ClassEntry COMPONENT;
    private static final NmsReflection.ClassEntry REMOVAL_REASON;
    private static final NmsReflection.ClassEntry EXPLOSION_INTERACTION;

    private static final Map<EntityType, Object> nmsEntityTypeCache = new ConcurrentHashMap<>();
    private static final Map<String, Class<?>> NAME_TO_BUKKIT_INTERFACE = new HashMap<>();
    private static final Map<Class<?>, EntityType> BUKKIT_CLASS_TO_TYPE = new HashMap<>();
    private static final Map<EntityType, String> TYPE_TO_NMS_NAME = new EnumMap<>(EntityType.class);

    private static final MethodHandle SPAWN_REASON_COMMAND;
    private static final MethodHandle COMPONENT_LITERAL;

    static {
        NmsReflection.ClassEntry entityBase;
        NmsReflection.ClassEntry entityTypes;
        NmsReflection.ClassEntry spawnReason;
        NmsReflection.ClassEntry level;
        NmsReflection.ClassEntry itemStackNms;
        NmsReflection.ClassEntry itemEntity;
        NmsReflection.ClassEntry vec3;
        NmsReflection.ClassEntry aabb;
        NmsReflection.ClassEntry component;
        NmsReflection.ClassEntry removalReason;
        NmsReflection.ClassEntry explosionInteraction;
        MethodHandle spawnReasonCmd;
        MethodHandle componentLiteral;
        try {
            entityBase = NmsReflection.ofName("net.minecraft.world.entity.Entity");
            entityTypes = NmsReflection.ofName("net.minecraft.world.entity.EntityTypes");
            spawnReason = NmsReflection.ofName("net.minecraft.world.entity.EntitySpawnReason");
            level = NmsReflection.ofName("net.minecraft.world.level.Level");
            itemStackNms = NmsReflection.ofName("net.minecraft.world.item.ItemStack");
            itemEntity = NmsReflection.ofName("net.minecraft.world.entity.item.ItemEntity");
            vec3 = NmsReflection.ofName("net.minecraft.world.phys.Vec3");
            aabb = NmsReflection.ofName("net.minecraft.world.phys.AABB");
            component = NmsReflection.ofName("net.minecraft.network.chat.Component");
            removalReason = NmsReflection.ofName("net.minecraft.world.entity.Entity$RemovalReason");
            explosionInteraction = NmsReflection.ofName("net.minecraft.world.level.Level$ExplosionInteraction");
            spawnReasonCmd = spawnReason.staticField("COMMAND");
            componentLiteral = component.staticMethod("literal", component.get(), String.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError("NMS reflection init failed: " + e.getMessage());
        }
        ENTITY = entityBase;
        ENTITY_TYPES = entityTypes;
        SPAWN_REASON = spawnReason;
        LEVEL = level;
        ITEM_STACK_NMS = itemStackNms;
        ITEM_ENTITY = itemEntity;
        VEC3 = vec3;
        AABB = aabb;
        COMPONENT = component;
        REMOVAL_REASON = removalReason;
        EXPLOSION_INTERACTION = explosionInteraction;
        SPAWN_REASON_COMMAND = spawnReasonCmd;
        COMPONENT_LITERAL = componentLiteral;

        // Map Bukkit EntityType to NMS EntityTypes field name
        TYPE_TO_NMS_NAME.put(EntityType.ZOMBIE, "ZOMBIE");
        TYPE_TO_NMS_NAME.put(EntityType.SKELETON, "SKELETON");
        TYPE_TO_NMS_NAME.put(EntityType.CREEPER, "CREEPER");
        TYPE_TO_NMS_NAME.put(EntityType.VILLAGER, "VILLAGER");
        TYPE_TO_NMS_NAME.put(EntityType.ARMOR_STAND, "ARMOR_STAND");
        TYPE_TO_NMS_NAME.put(EntityType.ITEM, "ITEM");
        TYPE_TO_NMS_NAME.put(EntityType.ARROW, "ARROW");
        TYPE_TO_NMS_NAME.put(EntityType.EXPERIENCE_ORB, "EXPERIENCE_ORB");
        TYPE_TO_NMS_NAME.put(EntityType.LIGHTNING_BOLT, "LIGHTNING_BOLT");
        TYPE_TO_NMS_NAME.put(EntityType.PLAYER, "PLAYER");
        TYPE_TO_NMS_NAME.put(EntityType.COW, "COW");
        TYPE_TO_NMS_NAME.put(EntityType.PIG, "PIG");
        TYPE_TO_NMS_NAME.put(EntityType.SHEEP, "SHEEP");
        TYPE_TO_NMS_NAME.put(EntityType.CHICKEN, "CHICKEN");
        TYPE_TO_NMS_NAME.put(EntityType.WOLF, "WOLF");
        TYPE_TO_NMS_NAME.put(EntityType.OCELOT, "OCELOT");
        TYPE_TO_NMS_NAME.put(EntityType.CAT, "CAT");
        TYPE_TO_NMS_NAME.put(EntityType.HORSE, "HORSE");
        TYPE_TO_NMS_NAME.put(EntityType.FOX, "FOX");
        TYPE_TO_NMS_NAME.put(EntityType.BEE, "BEE");
        TYPE_TO_NMS_NAME.put(EntityType.GOAT, "GOAT");
        TYPE_TO_NMS_NAME.put(EntityType.ALLAY, "ALLAY");
        TYPE_TO_NMS_NAME.put(EntityType.CAMEL, "CAMEL");
        TYPE_TO_NMS_NAME.put(EntityType.SPIDER, "SPIDER");
        TYPE_TO_NMS_NAME.put(EntityType.ENDERMAN, "ENDERMAN");
        TYPE_TO_NMS_NAME.put(EntityType.BLAZE, "BLAZE");
        TYPE_TO_NMS_NAME.put(EntityType.GHAST, "GHAST");
        TYPE_TO_NMS_NAME.put(EntityType.MAGMA_CUBE, "MAGMA_CUBE");
        TYPE_TO_NMS_NAME.put(EntityType.SLIME, "SLIME");
        TYPE_TO_NMS_NAME.put(EntityType.WITHER_SKELETON, "WITHER_SKELETON");
        TYPE_TO_NMS_NAME.put(EntityType.WITHER, "WITHER");
        TYPE_TO_NMS_NAME.put(EntityType.ENDER_DRAGON, "ENDER_DRAGON");
        TYPE_TO_NMS_NAME.put(EntityType.SNOWBALL, "SNOWBALL");
        TYPE_TO_NMS_NAME.put(EntityType.SNOW_GOLEM, "SNOW_GOLEM");
        TYPE_TO_NMS_NAME.put(EntityType.IRON_GOLEM, "IRON_GOLEM");
        TYPE_TO_NMS_NAME.put(EntityType.RABBIT, "RABBIT");
        TYPE_TO_NMS_NAME.put(EntityType.OAK_BOAT, "OAK_BOAT");
        TYPE_TO_NMS_NAME.put(EntityType.MINECART, "MINECART");
        TYPE_TO_NMS_NAME.put(EntityType.TRIDENT, "TRIDENT");
        TYPE_TO_NMS_NAME.put(EntityType.FIREBALL, "FIREBALL");
        TYPE_TO_NMS_NAME.put(EntityType.SMALL_FIREBALL, "SMALL_FIREBALL");
        TYPE_TO_NMS_NAME.put(EntityType.DRAGON_FIREBALL, "DRAGON_FIREBALL");
        TYPE_TO_NMS_NAME.put(EntityType.WITHER_SKULL, "WITHER_SKULL");
        TYPE_TO_NMS_NAME.put(EntityType.SHULKER_BULLET, "SHULKER_BULLET");
        TYPE_TO_NMS_NAME.put(EntityType.LLAMA_SPIT, "LLAMA_SPIT");
        TYPE_TO_NMS_NAME.put(EntityType.EGG, "EGG");
        TYPE_TO_NMS_NAME.put(EntityType.ENDER_PEARL, "ENDER_PEARL");
        TYPE_TO_NMS_NAME.put(EntityType.EYE_OF_ENDER, "EYE_OF_ENDER");
        TYPE_TO_NMS_NAME.put(EntityType.SPLASH_POTION, "SPLASH_POTION");
        TYPE_TO_NMS_NAME.put(EntityType.LINGERING_POTION, "LINGERING_POTION");
        TYPE_TO_NMS_NAME.put(EntityType.FISHING_BOBBER, "FISHING_BOBBER");
        TYPE_TO_NMS_NAME.put(EntityType.EXPERIENCE_BOTTLE, "EXPERIENCE_BOTTLE");
        TYPE_TO_NMS_NAME.put(EntityType.TNT, "TNT");
        TYPE_TO_NMS_NAME.put(EntityType.FALLING_BLOCK, "FALLING_BLOCK");
        TYPE_TO_NMS_NAME.put(EntityType.AREA_EFFECT_CLOUD, "AREA_EFFECT_CLOUD");
        TYPE_TO_NMS_NAME.put(EntityType.LEASH_KNOT, "LEASH_KNOT");
        TYPE_TO_NMS_NAME.put(EntityType.PAINTING, "PAINTING");
        TYPE_TO_NMS_NAME.put(EntityType.ITEM_FRAME, "ITEM_FRAME");
        TYPE_TO_NMS_NAME.put(EntityType.GLOW_ITEM_FRAME, "GLOW_ITEM_FRAME");
        TYPE_TO_NMS_NAME.put(EntityType.END_CRYSTAL, "END_CRYSTAL");
        TYPE_TO_NMS_NAME.put(EntityType.EVOKER_FANGS, "EVOKER_FANGS");
        TYPE_TO_NMS_NAME.put(EntityType.MARKER, "MARKER");
        TYPE_TO_NMS_NAME.put(EntityType.INTERACTION, "INTERACTION");
        TYPE_TO_NMS_NAME.put(EntityType.BLOCK_DISPLAY, "BLOCK_DISPLAY");
        TYPE_TO_NMS_NAME.put(EntityType.ITEM_DISPLAY, "ITEM_DISPLAY");
        TYPE_TO_NMS_NAME.put(EntityType.TEXT_DISPLAY, "TEXT_DISPLAY");
        TYPE_TO_NMS_NAME.put(EntityType.DONKEY, "DONKEY");
        TYPE_TO_NMS_NAME.put(EntityType.MULE, "MULE");
        TYPE_TO_NMS_NAME.put(EntityType.ZOMBIE_HORSE, "ZOMBIE_HORSE");
        TYPE_TO_NMS_NAME.put(EntityType.SKELETON_HORSE, "SKELETON_HORSE");
        TYPE_TO_NMS_NAME.put(EntityType.PARROT, "PARROT");
        TYPE_TO_NMS_NAME.put(EntityType.DOLPHIN, "DOLPHIN");
        TYPE_TO_NMS_NAME.put(EntityType.PUFFERFISH, "PUFFERFISH");
        TYPE_TO_NMS_NAME.put(EntityType.SQUID, "SQUID");
        TYPE_TO_NMS_NAME.put(EntityType.GLOW_SQUID, "GLOW_SQUID");
        TYPE_TO_NMS_NAME.put(EntityType.TURTLE, "TURTLE");
        TYPE_TO_NMS_NAME.put(EntityType.PANDA, "PANDA");
        TYPE_TO_NMS_NAME.put(EntityType.POLAR_BEAR, "POLAR_BEAR");
        TYPE_TO_NMS_NAME.put(EntityType.LLAMA, "LLAMA");
        TYPE_TO_NMS_NAME.put(EntityType.TRADER_LLAMA, "TRADER_LLAMA");
        TYPE_TO_NMS_NAME.put(EntityType.WANDERING_TRADER, "WANDERING_TRADER");
        TYPE_TO_NMS_NAME.put(EntityType.HUSK, "HUSK");
        TYPE_TO_NMS_NAME.put(EntityType.STRAY, "STRAY");
        TYPE_TO_NMS_NAME.put(EntityType.DROWNED, "DROWNED");
        TYPE_TO_NMS_NAME.put(EntityType.PHANTOM, "PHANTOM");
        TYPE_TO_NMS_NAME.put(EntityType.VEX, "VEX");
        TYPE_TO_NMS_NAME.put(EntityType.PILLAGER, "PILLAGER");
        TYPE_TO_NMS_NAME.put(EntityType.VINDICATOR, "VINDICATOR");
        TYPE_TO_NMS_NAME.put(EntityType.EVOKER, "EVOKER");
        TYPE_TO_NMS_NAME.put(EntityType.RAVAGER, "RAVAGER");
        TYPE_TO_NMS_NAME.put(EntityType.ILLUSIONER, "ILLUSIONER");
        TYPE_TO_NMS_NAME.put(EntityType.GUARDIAN, "GUARDIAN");
        TYPE_TO_NMS_NAME.put(EntityType.ELDER_GUARDIAN, "ELDER_GUARDIAN");
        TYPE_TO_NMS_NAME.put(EntityType.SHULKER, "SHULKER");
        TYPE_TO_NMS_NAME.put(EntityType.WITCH, "WITCH");
        TYPE_TO_NMS_NAME.put(EntityType.AXOLOTL, "AXOLOTL");
        TYPE_TO_NMS_NAME.put(EntityType.FROG, "FROG");
        TYPE_TO_NMS_NAME.put(EntityType.TADPOLE, "TADPOLE");
        TYPE_TO_NMS_NAME.put(EntityType.WARDEN, "WARDEN");
        TYPE_TO_NMS_NAME.put(EntityType.SNIFFER, "SNIFFER");
        TYPE_TO_NMS_NAME.put(EntityType.BREEZE, "BREEZE");
        TYPE_TO_NMS_NAME.put(EntityType.BOGGED, "BOGGED");
        TYPE_TO_NMS_NAME.put(EntityType.CREAKING, "CREAKING");

        // Map NMS class names to Bukkit entity interfaces
        var iface = new HashMap<String, Class<?>>();
        iface.put("net.minecraft.world.entity.monster.Zombie", org.bukkit.entity.Zombie.class);
        iface.put("net.minecraft.world.entity.monster.Skeleton", org.bukkit.entity.Skeleton.class);
        iface.put("net.minecraft.world.entity.monster.Creeper", org.bukkit.entity.Creeper.class);
        iface.put("net.minecraft.world.entity.npc.Villager", org.bukkit.entity.Villager.class);
        iface.put("net.minecraft.world.entity.decoration.ArmorStand", org.bukkit.entity.ArmorStand.class);
        iface.put("net.minecraft.world.entity.item.ItemEntity", org.bukkit.entity.Item.class);
        iface.put("net.minecraft.world.entity.projectile.Arrow", org.bukkit.entity.Arrow.class);
        iface.put("net.minecraft.world.entity.ExperienceOrb", org.bukkit.entity.ExperienceOrb.class);
        iface.put("net.minecraft.world.entity.LightningBolt", org.bukkit.entity.LightningStrike.class);
        iface.put("net.minecraft.world.entity.vehicle.Boat", org.bukkit.entity.Boat.class);
        iface.put("net.minecraft.world.entity.vehicle.MinecartAbstract", org.bukkit.entity.Minecart.class);
        iface.put("net.minecraft.world.entity.animal.IronGolem", org.bukkit.entity.IronGolem.class);
        iface.put("net.minecraft.world.entity.animal.SnowGolem", org.bukkit.entity.Snowman.class);
        iface.put("net.minecraft.world.entity.animal.Wolf", org.bukkit.entity.Wolf.class);
        iface.put("net.minecraft.world.entity.animal.Ocelot", org.bukkit.entity.Ocelot.class);
        iface.put("net.minecraft.world.entity.animal.Cat", org.bukkit.entity.Cat.class);
        iface.put("net.minecraft.world.entity.animal.horse.Horse", org.bukkit.entity.Horse.class);
        iface.put("net.minecraft.world.entity.animal.Chicken", org.bukkit.entity.Chicken.class);
        iface.put("net.minecraft.world.entity.animal.Cow", org.bukkit.entity.Cow.class);
        iface.put("net.minecraft.world.entity.animal.Sheep", org.bukkit.entity.Sheep.class);
        iface.put("net.minecraft.world.entity.animal.Pig", org.bukkit.entity.Pig.class);
        iface.put("net.minecraft.world.entity.animal.Rabbit", org.bukkit.entity.Rabbit.class);
        iface.put("net.minecraft.world.entity.animal.Fox", org.bukkit.entity.Fox.class);
        iface.put("net.minecraft.world.entity.animal.Bee", org.bukkit.entity.Bee.class);
        iface.put("net.minecraft.world.entity.animal.Goat", org.bukkit.entity.Goat.class);
        iface.put("net.minecraft.world.entity.animal.allay.Allay", org.bukkit.entity.Allay.class);
        iface.put("net.minecraft.world.entity.animal.camel.Camel", org.bukkit.entity.Camel.class);
        iface.put("net.minecraft.world.entity.monster.Spider", org.bukkit.entity.Spider.class);
        iface.put("net.minecraft.world.entity.monster.Enderman", org.bukkit.entity.Enderman.class);
        iface.put("net.minecraft.world.entity.monster.Blaze", org.bukkit.entity.Blaze.class);
        iface.put("net.minecraft.world.entity.monster.Ghast", org.bukkit.entity.Ghast.class);
        iface.put("net.minecraft.world.entity.monster.MagmaCube", org.bukkit.entity.MagmaCube.class);
        iface.put("net.minecraft.world.entity.monster.Slime", org.bukkit.entity.Slime.class);
        iface.put("net.minecraft.world.entity.monster.WitherSkeleton", org.bukkit.entity.WitherSkeleton.class);
        iface.put("net.minecraft.world.entity.boss.wither.WitherBoss", org.bukkit.entity.Wither.class);
        iface.put("net.minecraft.world.entity.boss.enderdragon.EnderDragon", org.bukkit.entity.EnderDragon.class);
        iface.put("net.minecraft.world.entity.projectile.ThrownPotion", org.bukkit.entity.ThrownPotion.class);
        iface.put("net.minecraft.world.entity.projectile.Snowball", org.bukkit.entity.Snowball.class);
        iface.put("net.minecraft.world.entity.player.Player", Player.class);
        iface.put("net.minecraft.world.entity.LivingEntity", LivingEntity.class);
        iface.put("net.minecraft.world.entity.Mob", org.bukkit.entity.Mob.class);
        iface.put("net.minecraft.world.entity.animal.Animal", org.bukkit.entity.Animals.class);
        iface.put("net.minecraft.world.entity.monster.Monster", org.bukkit.entity.Monster.class);
        iface.put("net.minecraft.world.entity.ambient.AmbientCreature", org.bukkit.entity.Ambient.class);
        iface.put("net.minecraft.world.entity.animal.WaterAnimal", org.bukkit.entity.WaterMob.class);
        iface.put("net.minecraft.world.entity.npc.AbstractVillager", org.bukkit.entity.AbstractVillager.class);
        iface.put("net.minecraft.world.entity.projectile.Projectile", org.bukkit.entity.Projectile.class);
        iface.put("net.minecraft.world.entity.projectile.AbstractArrow", org.bukkit.entity.AbstractArrow.class);
        iface.put("net.minecraft.world.entity.vehicle.AbstractMinecart", org.bukkit.entity.Minecart.class);
        iface.put("net.minecraft.world.entity.decoration.ItemFrame", org.bukkit.entity.ItemFrame.class);
        iface.put("net.minecraft.world.entity.decoration.GlowItemFrame", org.bukkit.entity.GlowItemFrame.class);
        iface.put("net.minecraft.world.entity.Display$BlockDisplay", org.bukkit.entity.BlockDisplay.class);
        iface.put("net.minecraft.world.entity.Display$ItemDisplay", org.bukkit.entity.ItemDisplay.class);
        iface.put("net.minecraft.world.entity.Display$TextDisplay", org.bukkit.entity.TextDisplay.class);
        iface.put("net.minecraft.world.entity.Display", org.bukkit.entity.Display.class);
        iface.put("net.minecraft.world.entity.Interaction", org.bukkit.entity.Interaction.class);
        NAME_TO_BUKKIT_INTERFACE.putAll(iface);

        // Map Bukkit entity classes to EntityType for spawn(Class)
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Zombie.class, EntityType.ZOMBIE);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Skeleton.class, EntityType.SKELETON);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Creeper.class, EntityType.CREEPER);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Villager.class, EntityType.VILLAGER);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.ArmorStand.class, EntityType.ARMOR_STAND);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Item.class, EntityType.ITEM);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Arrow.class, EntityType.ARROW);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.ExperienceOrb.class, EntityType.EXPERIENCE_ORB);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.LightningStrike.class, EntityType.LIGHTNING_BOLT);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Player.class, EntityType.PLAYER);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Cow.class, EntityType.COW);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Pig.class, EntityType.PIG);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Sheep.class, EntityType.SHEEP);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Chicken.class, EntityType.CHICKEN);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Wolf.class, EntityType.WOLF);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Cat.class, EntityType.CAT);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Horse.class, EntityType.HORSE);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Spider.class, EntityType.SPIDER);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Enderman.class, EntityType.ENDERMAN);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Slime.class, EntityType.SLIME);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.MagmaCube.class, EntityType.MAGMA_CUBE);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Blaze.class, EntityType.BLAZE);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Ghast.class, EntityType.GHAST);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Wither.class, EntityType.WITHER);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.EnderDragon.class, EntityType.ENDER_DRAGON);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Snowball.class, EntityType.SNOWBALL);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Snowman.class, EntityType.SNOW_GOLEM);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.IronGolem.class, EntityType.IRON_GOLEM);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Rabbit.class, EntityType.RABBIT);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.boat.OakBoat.class, EntityType.OAK_BOAT);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Minecart.class, EntityType.MINECART);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Fox.class, EntityType.FOX);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Bee.class, EntityType.BEE);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Goat.class, EntityType.GOAT);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Allay.class, EntityType.ALLAY);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Camel.class, EntityType.CAMEL);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.ThrownPotion.class, EntityType.SPLASH_POTION);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Projectile.class, EntityType.ARROW);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.WitherSkeleton.class, EntityType.WITHER_SKELETON);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Display.class, EntityType.BLOCK_DISPLAY);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.BlockDisplay.class, EntityType.BLOCK_DISPLAY);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.ItemDisplay.class, EntityType.ITEM_DISPLAY);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.TextDisplay.class, EntityType.TEXT_DISPLAY);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.Interaction.class, EntityType.INTERACTION);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.ItemFrame.class, EntityType.ITEM_FRAME);
        BUKKIT_CLASS_TO_TYPE.put(org.bukkit.entity.GlowItemFrame.class, EntityType.GLOW_ITEM_FRAME);
    }

    public static Entity adapt(Object nmsEntity, Server server) {
        if (nmsEntity == null) return null;
        var entityClass = nmsEntity.getClass();
        try {
            if (entityClass.getName().equals("net.minecraft.server.level.ServerPlayer")) {
                var e = NmsReflection.of(entityClass);
                var uuid = (UUID) e.methodExact(UUID.class, "getUUID").invoke(nmsEntity);
                var name = String.valueOf(e.methodExact(String.class, "getScoreboardName").invoke(nmsEntity));
                return new VeltisPlayerSender(nmsEntity, uuid, name, server);
            }
        } catch (Throwable ignored) {}

        var bukkitInterface = resolveBukkitInterface(entityClass);
        if (bukkitInterface == null) {
            LOG.log(System.Logger.Level.DEBUG, "No Bukkit interface for NMS entity: " + entityClass.getName());
            return null;
        }

        var metadataMap = new ConcurrentHashMap<String, List<MetadataValue>>();
        var pdc = new org.veltismc.veltis.VeltisPersistentDataContainer();
        return (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(),
            new Class<?>[]{bukkitInterface},
            new EntityHandler(nmsEntity, server, metadataMap, pdc)
        );
    }

    private static Class<?> resolveBukkitInterface(Class<?> nmsClass) {
        var name = nmsClass.getName();
        var direct = NAME_TO_BUKKIT_INTERFACE.get(name);
        if (direct != null) return direct;
        var parent = nmsClass.getSuperclass();
        while (parent != null) {
            var mapped = NAME_TO_BUKKIT_INTERFACE.get(parent.getName());
            if (mapped != null) return mapped;
            parent = parent.getSuperclass();
        }
        return LivingEntity.class;
    }

    public static EntityType resolveBukkitEntityType(Object nmsEntity) {
        try {
            var e = NmsReflection.of(nmsEntity.getClass());
            var nmsType = e.method("getType").invoke(nmsEntity);
            var builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            var entityRegistry = builtIn.getField("ENTITY_TYPE").get(null);
            var key = builtIn.getMethod("getKey", entityRegistry.getClass(), nmsType.getClass()).invoke(null, entityRegistry, nmsType);
            if (key != null) {
                var path = (String) key.getClass().getMethod("getPath").invoke(key);
                return EntityType.valueOf(path.toUpperCase(java.util.Locale.ROOT));
            }
        } catch (Throwable ignored) {}
        return EntityType.UNKNOWN;
    }

    private static final class EntityHandler implements InvocationHandler {
        private final Object nmsEntity;
        private final Server server;
        private final Map<String, List<MetadataValue>> metadataMap;
        private final PersistentDataContainer pdc;
        private final NmsReflection.ClassEntry entityClass;

        EntityHandler(Object nmsEntity, Server server,
            Map<String, List<MetadataValue>> metadataMap,
            PersistentDataContainer pdc) {
            this.nmsEntity = nmsEntity;
            this.server = server;
            this.metadataMap = metadataMap;
            this.pdc = pdc;
            this.entityClass = NmsReflection.of(nmsEntity.getClass());
        }

        private MethodHandle m(String name, Class<?>... params) {
            return entityClass.method(name, params);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            var name = method.getName();
            try {
                return switch (name) {
                    case "getEntityId" -> m("getId").invoke(nmsEntity);
                    case "getUniqueId" -> m("getUUID").invoke(nmsEntity);
                    case "getLocation" -> extractLocation(proxy);
                    case "getWorld" -> resolveWorld();
                    case "isDead" -> m("isRemoved").invoke(nmsEntity);
                    case "isValid" -> m("isRemoved").invoke(nmsEntity);
                    case "isAlive" -> !(boolean) m("isRemoved").invoke(nmsEntity);
                    case "remove" -> { remove(); yield null; }
                    case "teleport" -> teleport(args);
                    case "getVelocity" -> extractVelocity();
                    case "setVelocity" -> { setVelocity(args); yield null; }
                    case "isOnGround" -> m("onGround").invoke(nmsEntity);
                    case "isInWater" -> m("isInWater").invoke(nmsEntity);
                    case "isInLava" -> m("isInLava").invoke(nmsEntity);
                    case "getFallDistance" -> m("fallDistance").invoke(nmsEntity);
                    case "setFallDistance" -> { m("setFallDistance", float.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getTicksLived" -> m("tickCount").invoke(nmsEntity);
                    case "setTicksLived" -> { m("tickCount").invoke(nmsEntity, args[0]); yield null; }
                    case "getFireTicks" -> m("getRemainingFireTicks").invoke(nmsEntity);
                    case "setFireTicks" -> { m("setRemainingFireTicks", int.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getMaxFireTicks" -> m("getMaxFireTicks").invoke(nmsEntity);
                    case "getScoreboardTags" -> {
                        var tags = (Set<?>) m("getTags").invoke(nmsEntity);
                        yield new HashSet<>((Set<String>) tags);
                    }
                    case "addScoreboardTag" -> m("addTag", String.class).invoke(nmsEntity, args[0]);
                    case "removeScoreboardTag" -> m("removeTag", String.class).invoke(nmsEntity, args[0]);
                    case "getPassengers" -> extractPassengers();
                    case "getVehicle" -> {
                        var vehicle = m("getVehicle").invoke(nmsEntity);
                        yield vehicle != null ? adapt(vehicle, server) : null;
                    }
                    case "isInsideVehicle" -> m("isPassenger").invoke(nmsEntity);
                    case "addPassenger" -> { addPassenger(args); yield null; }
                    case "removePassenger" -> { m("stopRiding").invoke(nmsEntity); yield null; }
                    case "getType" -> resolveBukkitEntityType(nmsEntity);
                    case "setCustomName" -> { setCustomName(args); yield null; }
                    case "getCustomName" -> getCustomName();
                    case "isCustomNameVisible" -> m("isCustomNameVisible").invoke(nmsEntity);
                    case "setCustomNameVisible" -> { m("setCustomNameVisible", boolean.class).invoke(nmsEntity, args[0]); yield null; }
                    case "isGlowing" -> m("isGlowing").invoke(nmsEntity);
                    case "setGlowing" -> { m("setGlowingTag", boolean.class).invoke(nmsEntity, args[0]); yield null; }
                    case "hasGravity" -> !(boolean) m("isNoGravity").invoke(nmsEntity);
                    case "setGravity" -> { m("setNoGravity", boolean.class).invoke(nmsEntity, !(boolean) args[0]); yield null; }
                    case "isInvulnerable" -> m("isInvulnerable").invoke(nmsEntity);
                    case "setInvulnerable" -> { m("setInvulnerable", boolean.class).invoke(nmsEntity, args[0]); yield null; }
                    case "isSilent" -> m("isSilent").invoke(nmsEntity);
                    case "setSilent" -> { m("setSilent", boolean.class).invoke(nmsEntity, args[0]); yield null; }
                    case "isPersistent" -> !(boolean) m("shouldBeSaved").invoke(nmsEntity);
                    case "setPersistent" -> { m("setShouldBeSaved", boolean.class).invoke(nmsEntity, !(boolean) args[0]); yield null; }
                    case "setRotation" -> { setRotation(args); yield null; }
                    case "getNearbyEntities" -> getNearbyEntities(args);
                    case "getBoundingBox" -> extractBoundingBox();
                    case "getServer" -> server;
                    case "isOp" -> false;
                    case "isEmpty" -> false;
                    case "getHealth" -> m("getHealth").invoke(nmsEntity);
                    case "setHealth" -> { m("setHealth", float.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getMaxHealth" -> m("getMaxHealth").invoke(nmsEntity);
                    case "setMaxHealth" -> { m("setMaxHealth", double.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getEyeHeight" -> m("getEyeHeight").invoke(nmsEntity);
                    case "getEyeLocation" -> getEyeLocation(proxy);
                    case "getLastDamage" -> m("getLastDamage").invoke(nmsEntity);
                    case "setLastDamage" -> { m("setLastDamage", float.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getNoDamageTicks" -> m("invulnerableTime").invoke(nmsEntity);
                    case "setNoDamageTicks" -> { m("setInvulnerableTime", int.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getNoActionTicks" -> m("getStuckArrowTicks").invoke(nmsEntity);
                    case "setNoActionTicks" -> { m("setStuckArrowTicks", int.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getKiller" -> {
                        var killer = m("getKiller").invoke(nmsEntity);
                        yield adapt(killer, server);
                    }
                    case "getRemoveWhenFarAway" -> !(boolean) m("isPersistenceRequired").invoke(nmsEntity);
                    case "setRemoveWhenFarAway" -> { m("setPersistenceRequired", boolean.class).invoke(nmsEntity, !(boolean) args[0]); yield null; }
                    case "getCanPickupItems" -> m("canPickUpLoot").invoke(nmsEntity);
                    case "setCanPickupItems" -> { m("setCanPickUpLoot", boolean.class).invoke(nmsEntity, args[0]); yield null; }
                    case "isLeashed" -> m("isLeashed").invoke(nmsEntity);
                    case "getLeashHolder" -> {
                        var holder = m("getLeashHolder").invoke(nmsEntity);
                        yield adapt(holder, server);
                    }
                    case "setLeashHolder" -> { handleLeash(args); yield null; }
                    case "getFacing" -> getFacing();
                    case "getPitch" -> m("getXRot").invoke(nmsEntity);
                    case "setPitch" -> { m("setXRot", float.class).invoke(nmsEntity, args[0]); yield null; }
                    case "getMetadata" -> {
                        if (args == null || args.length == 0) yield List.of();
                        yield metadataMap.getOrDefault(String.valueOf(args[0]), List.of());
                    }
                    case "setMetadata" -> {
                        if (args != null && args.length >= 2 && args[0] instanceof String k && args[1] instanceof MetadataValue v) {
                            metadataMap.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                        }
                        yield null;
                    }
                    case "hasMetadata" -> {
                        if (args == null || args.length == 0) yield false;
                        var entries = metadataMap.get(String.valueOf(args[0]));
                        yield entries != null && !entries.isEmpty();
                    }
                    case "removeMetadata" -> {
                        if (args != null && args.length >= 2 && args[0] instanceof String k && args[1] instanceof Plugin p) {
                            metadataMap.computeIfPresent(k, (key, list) -> { list.removeIf(mv -> mv.getOwningPlugin() == p); return list.isEmpty() ? null : list; });
                        }
                        yield null;
                    }
                    case "getPersistentDataContainer" -> pdc;
                    case "sendMessage" -> null;
                    case "toString" -> "VeltisEntity{" + nmsEntity.getClass().getSimpleName() + "}";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length > 0 && proxy == args[0];
                    default -> {
                        if (args == null || args.length == 0) {
                            yield m(name).invoke(nmsEntity);
                        }
                        yield m(name, argTypes(args)).invoke(nmsEntity, args);
                    }
                };
            } catch (Throwable e) {
                return defaultReturn(method.getReturnType());
            }
        }

        private void remove() throws Throwable {
            var discarded = REMOVAL_REASON.staticField("DISCARDED").invoke();
            m("remove", Object.class).invoke(nmsEntity, discarded);
        }

        private Location extractLocation(Object proxy) throws Throwable {
            var world = resolveWorld();
            var x = (double) m("getX").invoke(nmsEntity);
            var y = (double) m("getY").invoke(nmsEntity);
            var z = (double) m("getZ").invoke(nmsEntity);
            var yaw = (float) m("getYRot").invoke(nmsEntity);
            var pitch = (float) m("getXRot").invoke(nmsEntity);
            return new Location(world, x, y, z, yaw, pitch);
        }

        private World resolveWorld() {
            try {
                var level = m("level").invoke(nmsEntity);
                return org.veltismc.veltis.VeltisWorldProxy.create(level, server);
            } catch (Throwable e) {
                return null;
            }
        }

        private boolean teleport(Object[] args) throws Throwable {
            if (args == null || args.length == 0 || !(args[0] instanceof Location loc)) return false;
            m("teleportTo", double.class, double.class, double.class).invoke(nmsEntity, loc.getX(), loc.getY(), loc.getZ());
            m("setYRot", float.class).invoke(nmsEntity, loc.getYaw());
            m("setXRot", float.class).invoke(nmsEntity, loc.getPitch());
            return true;
        }

        private Vector extractVelocity() throws Throwable {
            var vel = m("getDeltaMovement").invoke(nmsEntity);
            var vClass = NmsReflection.of(vel.getClass());
            var x = (double) vClass.methodExact(double.class, "x").invoke(vel);
            var y = (double) vClass.methodExact(double.class, "y").invoke(vel);
            var z = (double) vClass.methodExact(double.class, "z").invoke(vel);
            return new Vector(x, y, z);
        }

        private void setVelocity(Object[] args) throws Throwable {
            if (args == null || args.length == 0 || !(args[0] instanceof Vector v)) return;
            var vec3 = VEC3.get().getConstructor(double.class, double.class, double.class)
                .newInstance(v.getX(), v.getY(), v.getZ());
            m("setDeltaMovement", Object.class).invoke(nmsEntity, vec3);
            m("hasImpulse", boolean.class).invoke(nmsEntity, true);
        }

        private List<Entity> extractPassengers() throws Throwable {
            var passengers = m("getPassengers").invoke(nmsEntity);
            if (passengers instanceof List<?> list) {
                var result = new ArrayList<Entity>();
                for (var p : list) result.add(adapt(p, server));
                return result;
            }
            return List.of();
        }

        private void addPassenger(Object[] args) throws Throwable {
            if (args == null || args.length == 0 || !(args[0] instanceof Entity e)) return;
            var handler = Proxy.getInvocationHandler(e);
            var field = handler.getClass().getDeclaredField("nmsEntity");
            field.setAccessible(true);
            var passengerNms = field.get(handler);
            var startRiding = NmsReflection.of(passengerNms.getClass())
                .method("startRiding", nmsEntity.getClass(), boolean.class);
            startRiding.invoke(passengerNms, nmsEntity, true);
        }

        private void setCustomName(Object[] args) throws Throwable {
            if (args == null || args.length == 0 || args[0] == null) {
                m("setCustomName", Object.class).invoke(nmsEntity, (Object) null);
            } else {
                var name = COMPONENT_LITERAL.invoke(args[0].toString());
                m("setCustomName", Object.class).invoke(nmsEntity, name);
            }
        }

        private Object getCustomName() throws Throwable {
            var name = m("getCustomName").invoke(nmsEntity);
            if (name == null) return null;
            var c = NmsReflection.of(name.getClass());
            return c.methodExact(String.class, "getString").invoke(name);
        }

        private void setRotation(Object[] args) throws Throwable {
            if (args == null || args.length < 2) return;
            m("setYRot", float.class).invoke(nmsEntity, args[0]);
            m("setXRot", float.class).invoke(nmsEntity, args[1]);
        }

        private List<Entity> getNearbyEntities(Object[] args) throws Throwable {
            if (args == null || args.length < 3) return List.of();
            var x = (double) args[0];
            var y = (double) args[1];
            var z = (double) args[2];
            var aabb = AABB.get().getConstructor(double.class, double.class, double.class, double.class, double.class, double.class)
                .newInstance(-x, -y, -z, x, y, z);
            var level = m("level").invoke(nmsEntity);
            var getEntities = NmsReflection.of(level.getClass())
                .method("getEntities", nmsEntity.getClass(), aabb.getClass());
            var entities = getEntities.invoke(level, null, aabb);
            if (entities instanceof List<?> list) {
                var result = new ArrayList<Entity>();
                for (var e : list) result.add(adapt(e, server));
                return result;
            }
            return List.of();
        }

        private BoundingBox extractBoundingBox() throws Throwable {
            var bb = m("getBoundingBox").invoke(nmsEntity);
            var b = NmsReflection.of(bb.getClass());
            var minX = (double) b.methodExact(double.class, "minX").invoke(bb);
            var minY = (double) b.methodExact(double.class, "minY").invoke(bb);
            var minZ = (double) b.methodExact(double.class, "minZ").invoke(bb);
            var maxX = (double) b.methodExact(double.class, "maxX").invoke(bb);
            var maxY = (double) b.methodExact(double.class, "maxY").invoke(bb);
            var maxZ = (double) b.methodExact(double.class, "maxZ").invoke(bb);
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private Location getEyeLocation(Object proxy) throws Throwable {
            var loc = extractLocation(proxy);
            var eyeH = (double) m("getEyeHeight").invoke(nmsEntity);
            loc.setY(loc.getY() + eyeH);
            return loc;
        }

        private void handleLeash(Object[] args) throws Throwable {
            if (args == null || args.length == 0) return;
            if (args[0] == null) {
                m("setLeashHolder", Object.class, boolean.class).invoke(nmsEntity, (Object) null, true);
            } else if (args[0] instanceof Entity holder) {
                var handler = Proxy.getInvocationHandler(holder);
                var field = handler.getClass().getDeclaredField("nmsEntity");
                field.setAccessible(true);
                var holderNms = field.get(handler);
                m("setLeashHolder", Object.class, boolean.class).invoke(nmsEntity, holderNms, true);
            }
        }

        private BlockFace getFacing() throws Throwable {
            var yaw = (float) m("getYRot").invoke(nmsEntity);
            var facing = NumberConversions.round(yaw / 90.0F) * 90 == 0 ? "SOUTH" :
                Math.abs(yaw - 90) < 45 ? "WEST" :
                Math.abs(yaw - 180) < 45 ? "NORTH" : "EAST";
            return BlockFace.valueOf(facing);
        }

        private static Class<?>[] argTypes(Object[] args) {
            var types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
            return types;
        }

        private static Object defaultReturn(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == char.class) return '\0';
            return null;
        }
    }

    // === World spawn helpers ===

    private static Object resolveNmsEntityType(EntityType type) {
        return nmsEntityTypeCache.computeIfAbsent(type, t -> {
            var name = TYPE_TO_NMS_NAME.get(t);
            if (name == null) throw new IllegalArgumentException("Unknown EntityType: " + t);
            try {
                return ENTITY_TYPES.staticField(name).invoke();
            } catch (Throwable e) {
                throw new RuntimeException("Failed to resolve NMS EntityType for " + t, e);
            }
        });
    }

    public static Entity spawnEntity(Object nmsLevel, Location loc, EntityType type, Server server) {
        try {
            var nmsEntityType = resolveNmsEntityType(type);
            var entity = ENTITY_TYPES.method("create", nmsEntityType.getClass(), LEVEL.get(), SPAWN_REASON.get())
                .invoke(nmsEntityType, nmsLevel, SPAWN_REASON_COMMAND.invoke());
            if (entity == null) {
                LOG.log(System.Logger.Level.WARNING, "EntityTypes.{0}.create() returned null", type);
                return null;
            }

            var eClass = NmsReflection.of(entity.getClass());
            eClass.method("setYRot", float.class).invoke(entity, loc.getYaw());
            eClass.method("setXRot", float.class).invoke(entity, loc.getPitch());
            eClass.method("setPos", double.class, double.class, double.class).invoke(entity, loc.getX(), loc.getY(), loc.getZ());

            var addFresh = NmsReflection.of(nmsLevel.getClass()).method("addFreshEntity", entity.getClass());
            addFresh.invoke(nmsLevel, entity);

            return adapt(entity, server);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to spawn entity type " + type, e);
            return null;
        }
    }

    public static Entity spawnEntity(Object nmsLevel, Location loc, Class<? extends Entity> clazz, Server server) {
        var type = BUKKIT_CLASS_TO_TYPE.get(clazz);
        if (type == null) {
            for (var entry : BUKKIT_CLASS_TO_TYPE.entrySet()) {
                if (entry.getKey().getName().equals(clazz.getName())) {
                    type = entry.getValue();
                    break;
                }
            }
            if (type == null) {
                LOG.log(System.Logger.Level.WARNING, "Unknown entity class: " + clazz.getName());
                return null;
            }
        }
        return spawnEntity(nmsLevel, loc, type, server);
    }

    public static Entity dropItem(Object nmsLevel, Location loc, ItemStack item, Server server) {
        try {
            var nmsItem = VeltisItemStackBridge.toNms(item);
            var ctor = ITEM_ENTITY.get().getConstructor(LEVEL.get(), double.class, double.class, double.class, ITEM_STACK_NMS.get());
            var itemEntity = ctor.newInstance(nmsLevel, loc.getX(), loc.getY(), loc.getZ(), nmsItem);
            NmsReflection.of(nmsLevel.getClass()).method("addFreshEntity", itemEntity.getClass())
                .invoke(nmsLevel, itemEntity);
            return adapt(itemEntity, server);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to drop item", e);
            return null;
        }
    }

    public static Entity dropItemNaturally(Object nmsLevel, Location loc, ItemStack item, Server server) {
        try {
            var nmsItem = VeltisItemStackBridge.toNms(item);
            var ctor = ITEM_ENTITY.get().getConstructor(LEVEL.get(), double.class, double.class, double.class, ITEM_STACK_NMS.get());
            var random = new Random();
            var offsetX = random.nextDouble() * 0.5 - 0.25;
            var offsetY = random.nextDouble() * 0.2 + 0.1;
            var offsetZ = random.nextDouble() * 0.5 - 0.25;

            var itemEntity = ctor.newInstance(nmsLevel,
                loc.getX() + offsetX, loc.getY() + offsetY, loc.getZ() + offsetZ,
                nmsItem);
            NmsReflection.of(nmsLevel.getClass()).method("addFreshEntity", itemEntity.getClass())
                .invoke(nmsLevel, itemEntity);

            var vel = itemEntity.getClass().getMethod("getDeltaMovement").invoke(itemEntity);
            vel.getClass().getMethod("add", double.class, double.class, double.class)
                .invoke(vel, random.nextGaussian() * 0.05, 0.2, random.nextGaussian() * 0.05);
            return adapt(itemEntity, server);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to drop item naturally", e);
            return null;
        }
    }

    public static Object spawnLightning(Object nmsLevel, Location loc, boolean effectOnly, Server server) {
        try {
            var nmsType = resolveNmsEntityType(EntityType.LIGHTNING_BOLT);
            var entity = ENTITY_TYPES.method("create", nmsType.getClass(), LEVEL.get(), SPAWN_REASON.get())
                .invoke(nmsType, nmsLevel, SPAWN_REASON_COMMAND.invoke());
            if (entity == null) return null;

            var eClass = NmsReflection.of(entity.getClass());
            eClass.method("setYRot", float.class).invoke(entity, loc.getYaw());
            eClass.method("setXRot", float.class).invoke(entity, loc.getPitch());
            eClass.method("setPos", double.class, double.class, double.class).invoke(entity, loc.getX(), loc.getY(), loc.getZ());
            if (effectOnly) {
                try {
                    eClass.method("setVisualOnly", boolean.class).invoke(entity, true);
                } catch (Exception ignored) {}
            }

            NmsReflection.of(nmsLevel.getClass()).method("addFreshEntity", entity.getClass())
                .invoke(nmsLevel, entity);

            return adapt(entity, server);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to strike lightning", e);
            return null;
        }
    }

    public static boolean createExplosion(Object nmsLevel, Location loc, float power, boolean setFire, boolean breakBlocks, Server server) {
        try {
            var interaction = breakBlocks
                ? EXPLOSION_INTERACTION.staticField("STANDARD").invoke()
                : EXPLOSION_INTERACTION.staticField("NONE").invoke();
            LEVEL.method("explode", ENTITY.get(), double.class, double.class, double.class, float.class, EXPLOSION_INTERACTION.get())
                .invoke(nmsLevel, null, loc.getX(), loc.getY(), loc.getZ(), power, interaction);
            return true;
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to create explosion", e);
            return false;
        }
    }
}
