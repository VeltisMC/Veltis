package org.veltismc.veltis;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.util.TriState;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.conversations.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.*;
import org.bukkit.map.MapView;
import org.veltismc.veltis.inventory.VeltisItemStackBridge;
import org.veltismc.veltis.inventory.VeltisPlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.sign.Side;
import java.net.InetAddress;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageRecipient;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import com.destroystokyo.paper.ClientOption;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.datacomponent.DataComponentBuilder;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.entity.PlayerGiveResult;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.world.damagesource.CombatTracker;
import io.papermc.paper.math.Position;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VeltisPlayerSender implements Player {

    private static final System.Logger LOG = System.getLogger("VeltisPlayerSender");
    private final Object mcPlayer;
    private final UUID uuid;
    private final String name;
    private final Server server;
    private final PermissibleBase permBase;
    private final Map<String, List<MetadataValue>> metadataMap = new HashMap<>();
    private final VeltisPersistentDataContainer pdc = new VeltisPersistentDataContainer();
    private volatile VeltisEntityScheduler entityScheduler;
    private Scoreboard scoreboard;

    private final java.util.Set<java.util.UUID> hiddenPlayers = new java.util.HashSet<>();

    public VeltisPlayerSender(Object mcPlayer, UUID uuid, String name, Server server) {
        this.mcPlayer = mcPlayer;
        this.uuid = uuid;
        this.name = name;
        this.server = server;
        this.permBase = new PermissibleBase(this);
    }

    @SuppressWarnings("unchecked")
    private <T> T mcCall(String method, Class<?>[] paramTypes, Object[] args, T fallback) {
        if (mcPlayer == null) return fallback;
        try { var m = mcPlayer.getClass().getMethod(method, paramTypes); return (T) m.invoke(mcPlayer, args); }
        catch (Exception e) { return fallback; }
    }
    private <T> T mcCall(String method, T fallback) { return mcCall(method, new Class<?>[0], new Object[0], fallback); }

    private Object mcField(String name) {
        if (mcPlayer == null) return null;
        try {
            try { var f = mcPlayer.getClass().getField(name); return f.get(mcPlayer); }
            catch (NoSuchFieldException e) {
                for (var f : mcPlayer.getClass().getDeclaredFields()) {
                    if (f.getName().equals(name)) { f.setAccessible(true); return f.get(mcPlayer); }
                }
                return null;
            }
        } catch (Exception e) { return null; }
    }

    private Object mcInvoke(Object target, String method, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        try { return target.getClass().getMethod(method, paramTypes).invoke(target, args); }
        catch (Exception e) { return null; }
    }
    private Object mcInvoke(Object target, String method) { return mcInvoke(target, method, new Class<?>[0], new Object[0]); }

    private Object abilities() {
        if (mcPlayer == null) return null;
        try { return mcPlayer.getClass().getMethod("getAbilities").invoke(mcPlayer); }
        catch (Exception e) { return null; }
    }

    private void setField(Object target, String name, Object value) {
        if (target == null) return;
        try {
            try { var f = target.getClass().getField(name); f.set(target, value); return; }
            catch (NoSuchFieldException e) {
                for (var f : target.getClass().getDeclaredFields()) {
                    if (f.getName().equals(name)) { f.setAccessible(true); f.set(target, value); return; }
                }
            }
        } catch (Exception ignored) {}
    }

    private Object getField(Object target, String name) {
        if (target == null) return null;
        try {
            try { var f = target.getClass().getField(name); return f.get(target); }
            catch (NoSuchFieldException e) {
                for (var f : target.getClass().getDeclaredFields()) {
                    if (f.getName().equals(name)) { f.setAccessible(true); return f.get(target); }
                }
                return null;
            }
        } catch (Exception e) { return null; }
    }

    private void sendComponentToPlayer(Component component) {
        if (mcPlayer == null) { LOG.log(System.Logger.Level.INFO, component.toString()); return; }
        sendLegacyMessage(LegacyComponentSerializer.legacySection().serialize(component));
    }

    private void sendLegacyMessage(String msg) {
        if (mcPlayer != null) {
            if (sendSystemMessage(msg)) return;
            if (sendChatPacket(msg)) return;
        }
        LOG.log(System.Logger.Level.INFO, "[{0}] {1}", name, msg);
    }

    private boolean sendSystemMessage(String msg) {
        try {
            var componentClass = tryLoadNmsComponent();
            if (componentClass == null) {
                LOG.log(System.Logger.Level.DEBUG, "sendSystemMessage: could not load NMS Component class");
                return false;
            }
            var component = tryCreateNmsComponent(componentClass, msg);
            if (component == null) return false;
            try {
                mcPlayer.getClass().getMethod("sendSystemMessage", componentClass).invoke(mcPlayer, component);
            } catch (NoSuchMethodException e1) {
                LOG.log(System.Logger.Level.DEBUG, "sendSystemMessage: no sendSystemMessage method, trying sendChat");
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "sendSystemMessage failed: {0}", e.getMessage());
            return false;
        }
    }

    private boolean sendChatPacket(String msg) {
        try {
            var componentClass = tryLoadNmsComponent();
            if (componentClass == null) {
                LOG.log(System.Logger.Level.DEBUG, "sendChatPacket: could not load NMS Component class");
                return false;
            }
            var component = tryCreateNmsComponent(componentClass, msg);
            if (component == null) return false;
            var connection = getConnectionField();
            if (connection == null) {
                LOG.log(System.Logger.Level.DEBUG, "sendChatPacket: connection field not found");
                return false;
            }
            var packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
            var packet = packetClass.getConstructor(componentClass, boolean.class).newInstance(component, false);
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
            return true;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "sendChatPacket failed: {0}", e.getMessage());
            return false;
        }
    }

    private Object tryCreateNmsComponent(Class<?> componentClass, String msg) {
        try {
            var literal = componentClass.getMethod("literal", String.class);
            return literal.invoke(null, msg);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "tryCreateNmsComponent: literal failed, trying constructor");
        }
        try {
            var serializerClass = Class.forName("net.minecraft.network.chat.Component$Serializer");
            var fromJson = serializerClass.getMethod("fromJson", String.class);
            var json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(
                net.kyori.adventure.text.Component.text(msg));
            return fromJson.invoke(null, json);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "tryCreateNmsComponent: serializer fallback failed: {0}", e.getMessage());
        }
        return null;
    }

    private Class<?> tryLoadNmsComponent() {
        try {
            return mcPlayer.getClass().getClassLoader().loadClass("net.minecraft.network.chat.Component");
        } catch (Exception e1) {
            try {
                return Class.forName("net.minecraft.network.chat.Component");
            } catch (Exception e2) {
                try {
                    return Class.forName("net.minecraft.network.chat.Component", true, Thread.currentThread().getContextClassLoader());
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private Object getConnectionField() {
        try {
            var f = mcPlayer.getClass().getField("connection");
            return f.get(mcPlayer);
        } catch (Exception e1) {
            try {
                for (var f : mcPlayer.getClass().getDeclaredFields()) {
                    if (f.getName().equals("connection")) {
                        f.setAccessible(true);
                        return f.get(mcPlayer);
                    }
                }
            } catch (Exception e2) {}
        }
        return null;
    }

    private void sendPacket(String packetClassName, Object... constructorArgs) {
        try {
            var connection = getConnectionField();
            if (connection == null) return;
            var argTypes = new Class<?>[constructorArgs.length];
            for (int i = 0; i < constructorArgs.length; i++) argTypes[i] = constructorArgs[i].getClass();
            var packetClass = Class.forName(packetClassName);
            var packet = packetClass.getConstructor(argTypes).newInstance(constructorArgs);
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
        } catch (Exception ignored) {}
    }

    private void sendTitlePacket(String title) {
        try {
            var componentClass = Class.forName("net.minecraft.network.chat.Component");
            var cmp = componentClass.getMethod("literal", String.class).invoke(null, title);
            var packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket");
            var packet = packetClass.getConstructor(componentClass).newInstance(cmp);
            var connection = getConnectionField();
            if (connection != null) {
                connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void sendMessage(String p0) {
        sendLegacyMessage(p0);
    }

    @Override
    public void sendMessage(UUID p0, String p1) {
        sendLegacyMessage(p1);
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isConversing() {
        return false;
    }

    @Override
    public void acceptConversationInput(String p0) {
    }

    @Override
    public boolean beginConversation(Conversation p0) {
        return false;
    }

    @Override
    public void abandonConversation(Conversation p0) {
    }

    @Override
    public void abandonConversation(Conversation p0, ConversationAbandonedEvent p1) {
    }

    @Override
    public void sendRawMessage(String p0) {
        sendLegacyMessage(p0);
    }

    @Override
    public void sendRawMessage(UUID p0, String p1) {
        sendLegacyMessage(p1);
    }

    @Override
    public boolean isPermissionSet(String p0) {
        return permBase.isPermissionSet(p0);
    }

    @Override
    public boolean hasPermission(String p0) {
        if (isOp()) return true;
        if (mcPlayer != null) {
            // Auto-op first player if no ops configured (like Paper/singleplayer behavior)
            try {
                var srv = resolveServer();
                if (srv != null) {
                    var playerList = srv.getClass().getMethod("getPlayerList").invoke(srv);
                    if (playerList != null) {
                        var ops = playerList.getClass().getMethod("getOps").invoke(playerList);
                        if (ops != null) {
                            var isEmpty = ops.getClass().getMethod("isEmpty");
                            if ((boolean) isEmpty.invoke(ops)) {
                                // No ops exist — treat this player as op
                                // No ops exist — treat this player as op
                                return true;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return permBase.hasPermission(p0);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin p0, String p1, boolean p2) {
        return permBase.addAttachment(p0, p1, p2);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin p0) {
        return permBase.addAttachment(p0);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin p0, String p1, boolean p2, int p3) {
        return permBase.addAttachment(p0, p1, p2, p3);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin p0, int p1) {
        return permBase.addAttachment(p0, p1);
    }

    @Override
    public void removeAttachment(PermissionAttachment p0) {
        permBase.removeAttachment(p0);
    }

    @Override
    public void recalculatePermissions() {
        permBase.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return permBase.getEffectivePermissions();
    }

    private Object resolveServer() {
        if (mcPlayer == null) return null;
        try {
            // ServerPlayer doesn't have getServer() — go through level().getServer()
            var level = mcPlayer.getClass().getMethod("level").invoke(mcPlayer);
            if (level == null) return null;
            return level.getClass().getMethod("getServer").invoke(level);
        } catch (Exception ignored) { return null; }
    }

    @Override
    public boolean isOp() {
        if (mcPlayer == null) return false;
        try {
            var srv = resolveServer();
            if (srv == null) return false;
            var playerList = srv.getClass().getMethod("getPlayerList").invoke(srv);
            if (playerList == null) return false;
            var nameAndId = mcPlayer.getClass().getMethod("nameAndId").invoke(mcPlayer);
            if (nameAndId == null) return false;
            return (boolean) playerList.getClass().getMethod("isOp", nameAndId.getClass()).invoke(playerList, nameAndId);
        } catch (Exception e) { return false; }
    }

    @Override
    public void setOp(boolean p0) {
        if (mcPlayer == null) return;
        try {
            var srv = resolveServer();
            if (srv == null) return;
            var playerList = srv.getClass().getMethod("getPlayerList").invoke(srv);
            if (playerList == null) return;
            var nameAndId = mcPlayer.getClass().getMethod("nameAndId").invoke(mcPlayer);
            if (nameAndId == null) return;
            if (p0) {
                playerList.getClass().getMethod("op", nameAndId.getClass()).invoke(playerList, nameAndId);
            } else {
                playerList.getClass().getMethod("deop", nameAndId.getClass()).invoke(playerList, nameAndId);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public Location getLocation() {
        if (mcPlayer == null) return new Location(null, 0, 0, 0);
        try {
            var p = mcPlayer.getClass().getMethod("position").invoke(mcPlayer);
            var x = (double)p.getClass().getMethod("x").invoke(p);
            var y = (double)p.getClass().getMethod("y").invoke(p);
            var z = (double)p.getClass().getMethod("z").invoke(p);
            return new Location(getWorld(), x, y, z, mcCall("getYRot", 0f), mcCall("getXRot", 0f));
        } catch (Exception e) {
            return new Location(getWorld(), 0, 0, 0);
        }
    }

    @Override
    public Location getLocation(Location p0) {
        if (p0 == null) return getLocation(); var l = getLocation(); p0.setWorld(l.getWorld()); p0.setX(l.getX()); p0.setY(l.getY()); p0.setZ(l.getZ()); p0.setYaw(l.getYaw()); p0.setPitch(l.getPitch()); return p0;
    }

    @Override
    public void setVelocity(Vector vel) {
        if (mcPlayer == null || vel == null) return;
        try {
            var vec3 = Class.forName("net.minecraft.world.phys.Vec3").getConstructor(double.class, double.class, double.class).newInstance(vel.getX(), vel.getY(), vel.getZ());
            mcPlayer.getClass().getMethod("setDeltaMovement", vec3.getClass()).invoke(mcPlayer, vec3);
        } catch (Exception ignored) {}
    }

    @Override
    public Vector getVelocity() {
        if (mcPlayer == null) return new Vector(0, 0, 0);
        try {
            var vel = mcPlayer.getClass().getMethod("getDeltaMovement").invoke(mcPlayer);
            if (vel == null) return new Vector(0, 0, 0);
            return new Vector((double)vel.getClass().getMethod("x").invoke(vel), (double)vel.getClass().getMethod("y").invoke(vel), (double)vel.getClass().getMethod("z").invoke(vel));
        } catch (Exception e) { return new Vector(0, 0, 0); }
    }

    @Override
    public double getHeight() {
        return mcCall("getBbHeight", 1.8);
    }

    @Override
    public double getWidth() {
        return mcCall("getBbWidth", 0.6);
    }

    @Override
    public BoundingBox getBoundingBox() {
        if (mcPlayer == null) return new BoundingBox(0,0,0,0,0,0);
        try {
            var bb = mcPlayer.getClass().getMethod("getBoundingBox").invoke(mcPlayer);
            if (bb == null) return new BoundingBox(0,0,0,0,0,0);
            var minX = (double)bb.getClass().getMethod("minX").invoke(bb);
            var minY = (double)bb.getClass().getMethod("minY").invoke(bb);
            var minZ = (double)bb.getClass().getMethod("minZ").invoke(bb);
            var maxX = (double)bb.getClass().getMethod("maxX").invoke(bb);
            var maxY = (double)bb.getClass().getMethod("maxY").invoke(bb);
            var maxZ = (double)bb.getClass().getMethod("maxZ").invoke(bb);
            return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Exception e) { return new BoundingBox(0,0,0,0,0,0); }
    }

    @Override
    public boolean isOnGround() {
        return mcCall("onGround", false);
    }

    @Override
    public boolean isInWater() {
        return mcCall("isInWater", false);
    }

    @Override
    public World getWorld() {
        if (mcPlayer == null) return null;
        try {
            var level = mcPlayer.getClass().getMethod("level").invoke(mcPlayer);
            if (level != null) {
                return VeltisWorldProxy.create(level, server);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setYRot", float.class).invoke(mcPlayer, yaw); } catch (Exception ignored) {}
        try { mcPlayer.getClass().getMethod("setXRot", float.class).invoke(mcPlayer, pitch); } catch (Exception ignored) {}
    }

    @Override
    public boolean teleport(Location p0) {
        return teleportAsync(p0, PlayerTeleportEvent.TeleportCause.PLUGIN).join();
    }

    @Override
    public boolean teleport(Location p0, PlayerTeleportEvent.TeleportCause p1) {
        return teleportAsync(p0, p1).join();
    }

    @Override
    public List<org.bukkit.entity.Entity> getNearbyEntities(double p0, double p1, double p2) {
        return List.of();
    }

    @Override
    public int getEntityId() {
        return mcCall("getId", 0);
    }

    @Override
    public int getFireTicks() {
        return mcCall("getRemainingFireTicks", 0);
    }

    @Override
    public int getMaxFireTicks() {
        return 0;
    }

    @Override
    public void setFireTicks(int p0) {
        mcCall("setRemainingFireTicks", new Class<?>[]{int.class}, new Object[]{p0}, null);
    }

    @Override
    public void remove() {
        if (mcPlayer == null) return;
        try {
            var removalReason = Class.forName("net.minecraft.world.entity.Entity$RemovalReason");
            var discard = removalReason.getField("DISCARDED").get(null);
            mcPlayer.getClass().getMethod("remove", removalReason).invoke(mcPlayer, discard);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean isDead() {
        return !mcCall("isAlive", true);
    }

    @Override
    public boolean isValid() {
        return mcCall("isAlive", true);
    }

    @Override
    public boolean isPersistent() {
        if (mcPlayer == null) return true;
        try { return !(boolean) mcPlayer.getClass().getMethod("shouldBeSaved").invoke(mcPlayer); } catch (Exception e) { return true; }
    }

    @Override
    public void setPersistent(boolean persistent) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setShouldBeSaved", boolean.class).invoke(mcPlayer, !persistent); } catch (Exception ignored) {}
    }

    @Override
    public Entity getPassenger() {
        var passengers = getPassengers();
        return passengers.isEmpty() ? null : passengers.get(0);
    }

    @Override
    public boolean setPassenger(Entity passenger) {
        eject();
        return addPassenger(passenger);
    }

    @Override
    public List<Entity> getPassengers() {
        if (mcPlayer == null) return List.of();
        try {
            var passengers = mcPlayer.getClass().getMethod("getPassengers").invoke(mcPlayer);
            if (passengers instanceof List<?> list) {
                var result = new ArrayList<Entity>();
                for (var p : list) {
                    var adapted = adaptEntity(p);
                    if (adapted != null) result.add(adapted);
                }
                return result;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    @Override
    public boolean addPassenger(Entity passenger) {
        if (mcPlayer == null || passenger == null) return false;
        try {
            var handler = java.lang.reflect.Proxy.getInvocationHandler(passenger);
            var field = handler.getClass().getDeclaredField("nmsEntity");
            field.setAccessible(true);
            var passengerNms = field.get(handler);
            passengerNms.getClass().getMethod("startRiding", mcPlayer.getClass(), boolean.class).invoke(passengerNms, mcPlayer, true);
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override
    public boolean removePassenger(Entity passenger) {
        eject();
        return true;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean eject() {
        if (mcPlayer == null) return false;
        try {
            mcPlayer.getClass().getMethod("ejectPassengers").invoke(mcPlayer);
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override
    public float getFallDistance() {
        return mcCall("fallDistance", 0f);
    }

    @Override
    public void setFallDistance(float dist) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setFallDistance", float.class).invoke(mcPlayer, dist); } catch (Exception ignored) {}
    }

    private volatile EntityDamageEvent lastDamageCause;

    @Override
    public void setLastDamageCause(EntityDamageEvent cause) {
        this.lastDamageCause = cause;
    }

    @Override
    public EntityDamageEvent getLastDamageCause() {
        return lastDamageCause;
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public int getTicksLived() {
        return mcCall("tickCount", 0);
    }

    @Override
    public void setTicksLived(int p0) {
    }

    @Override
    public void playEffect(EntityEffect p0) {
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public Sound getSwimSound() {
        return Sound.ENTITY_PLAYER_SWIM;
    }

    @Override
    public Sound getSwimSplashSound() {
        return Sound.ENTITY_PLAYER_SPLASH;
    }

    @Override
    public Sound getSwimHighSpeedSplashSound() {
        return Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED;
    }

    @Override
    public boolean isInsideVehicle() {
        return false;
    }

    @Override
    public boolean leaveVehicle() {
        return false;
    }

    @Override
    public Entity getVehicle() {
        return null;
    }

    @Override
    public void setCustomNameVisible(boolean visible) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setCustomNameVisible", boolean.class).invoke(mcPlayer, visible); } catch (Exception ignored) {}
    }

    @Override
    public boolean isCustomNameVisible() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("isCustomNameVisible").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public void setVisibleByDefault(boolean p0) {
    }

    @Override
    public boolean isVisibleByDefault() {
        return false;
    }

    @Override
    public void setInvulnerable(boolean invuln) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setInvulnerable", boolean.class).invoke(mcPlayer, invuln); } catch (Exception ignored) {}
    }

    @Override
    public boolean isInvulnerable() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("isInvulnerable").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public boolean isSilent() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("isSilent").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public void setSilent(boolean silent) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setSilent", boolean.class).invoke(mcPlayer, silent); } catch (Exception ignored) {}
    }

    @Override
    public double getEyeHeight() {
        return mcCall("getEyeHeight", 1.62);
    }

    @Override
    public double getEyeHeight(boolean p0) {
        return mcCall("getEyeHeight", 1.62);
    }

    @Override
    public Location getEyeLocation() {
        var loc = getLocation();
        if (loc == null) return null;
        loc.setY(loc.getY() + getEyeHeight());
        return loc;
    }

    @Override
    public List<Block> getLineOfSight(Set<Material> transparent, int maxDistance) {
        var block = getTargetBlock(transparent, maxDistance);
        return block != null ? List.of(block) : List.of();
    }

    @Override
    public Block getTargetBlock(Set<Material> transparent, int maxDistance) {
        var loc = getEyeLocation();
        if (loc == null) return null;
        try {
            var world = loc.getWorld();
            if (world == null) return null;
            var dir = loc.getDirection();
            for (int i = 0; i < maxDistance; i++) {
                var check = loc.clone().add(dir.clone().multiply(i));
                var block = world.getBlockAt(check.getBlockX(), check.getBlockY(), check.getBlockZ());
                if (transparent == null || !transparent.contains(block.getType())) {
                    return block.getType().isAir() ? null : block;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public org.bukkit.block.BlockFace getTargetBlockFace(int p0, com.destroystokyo.paper.block.TargetBlockInfo.FluidMode p1) {
        return null;
    }

    @Override
    public com.destroystokyo.paper.block.TargetBlockInfo getTargetBlockInfo(int p0, com.destroystokyo.paper.block.TargetBlockInfo.FluidMode p1) {
        return null;
    }

    @Override
    public Entity getTargetEntity(int p0, boolean p1) {
        return null;
    }

    @Override
    public com.destroystokyo.paper.entity.TargetEntityInfo getTargetEntityInfo(int p0, boolean p1) {
        return null;
    }

    @Override
    public List<Block> getLastTwoTargetBlocks(Set<Material> p0, int p1) {
        return List.of();
    }

    @Override
    public Block getTargetBlockExact(int p0, FluidCollisionMode p1) {
        return null;
    }

    @Override
    public RayTraceResult rayTraceBlocks(double p0, FluidCollisionMode p1) {
        return null;
    }

    @Override
    public int getRemainingAir() {
        return mcCall("getAirSupply", 300);
    }

    @Override
    public void setRemainingAir(int p0) {
        mcCall("setAirSupply", new Class<?>[]{int.class}, new Object[]{p0}, null);
    }

    @Override
    public int getMaximumAir() {
        return mcCall("getMaxAirSupply", 300);
    }

    @Override
    public void setMaximumAir(int p0) {
    }

    @Override
    public ItemStack getItemInUse() {
        return null;
    }

    @Override
    public int getItemInUseTicks() {
        return 0;
    }

    @Override
    public void setItemInUseTicks(int p0) {
    }

    @Override
    public void setArrowCooldown(int p0) {
    }

    @Override
    public void setBeeStingerCooldown(int p0) {
    }

    @Override
    public void setBeeStingersInBody(int p0) {
    }

    @Override
    public int getMaximumNoDamageTicks() {
        return 0;
    }

    @Override
    public void setMaximumNoDamageTicks(int p0) {
    }

    @Override
    public double getLastDamage() {
        return 0.0;
    }

    @Override
    public void setLastDamage(double p0) {
    }

    @Override
    public int getNoDamageTicks() {
        return 0;
    }

    @Override
    public void setNoDamageTicks(int p0) {
    }

    @Override
    public int getNoActionTicks() {
        return 0;
    }

    @Override
    public void setNoActionTicks(int p0) {
    }

    @Override
    public Player getKiller() {
        return null;
    }

    @Override
    public void setKiller(Player p0) {
    }

    @Override
    public boolean addPotionEffect(PotionEffect effect, boolean force) {
        if (mcPlayer == null || effect == null) return false;
        try {
            var nmsEffect = Class.forName("net.minecraft.world.effect.MobEffect");
            var effectType = Class.forName("net.minecraft.world.effect.MobEffect");
            var mobEffectList = Class.forName("net.minecraft.world.effect.MobEffects");
            var byId = mobEffectList.getMethod("byId", int.class).invoke(null, effect.getType().getId());
            var mobEffect = Class.forName("net.minecraft.world.effect.MobEffectInstance").getConstructor(
                effectType, int.class, int.class, boolean.class, boolean.class, boolean.class
            ).newInstance(byId, effect.getDuration(), effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
            if (force) {
                mcPlayer.getClass().getMethod("forceAddEffect", mobEffect.getClass()).invoke(mcPlayer, mobEffect);
            } else {
                mcPlayer.getClass().getMethod("addEffect", mobEffect.getClass()).invoke(mcPlayer, mobEffect);
            }
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override
    public boolean addPotionEffects(Collection<PotionEffect> effects) {
        boolean all = true;
        for (var e : effects) { if (!addPotionEffect(e, false)) all = false; }
        return all;
    }

    @Override
    public boolean hasPotionEffect(PotionEffectType type) {
        if (mcPlayer == null || type == null) return false;
        try {
            var mobEffectList = Class.forName("net.minecraft.world.effect.MobEffects");
            var byId = mobEffectList.getMethod("byId", int.class).invoke(null, type.getId());
            return (boolean) mcPlayer.getClass().getMethod("hasEffect", byId.getClass()).invoke(mcPlayer, byId);
        } catch (Exception e) { return false; }
    }

    @Override
    public PotionEffect getPotionEffect(PotionEffectType type) {
        if (mcPlayer == null || type == null) return null;
        try {
            var mobEffectList = Class.forName("net.minecraft.world.effect.MobEffects");
            var byId = mobEffectList.getMethod("byId", int.class).invoke(null, type.getId());
            var instance = mcPlayer.getClass().getMethod("getEffect", byId.getClass()).invoke(mcPlayer, byId);
            if (instance == null) return null;
            var duration = (int) instance.getClass().getMethod("getDuration").invoke(instance);
            var amplifier = (int) instance.getClass().getMethod("getAmplifier").invoke(instance);
            var ambient = (boolean) instance.getClass().getMethod("isAmbient").invoke(instance);
            var particles = (boolean) instance.getClass().getMethod("isVisible").invoke(instance);
            return new PotionEffect(type, duration, amplifier, ambient, particles);
        } catch (Exception e) { return null; }
    }

    @Override
    public void removePotionEffect(PotionEffectType type) {
        if (mcPlayer == null || type == null) return;
        try {
            var mobEffectList = Class.forName("net.minecraft.world.effect.MobEffects");
            var byId = mobEffectList.getMethod("byId", int.class).invoke(null, type.getId());
            mcPlayer.getClass().getMethod("removeEffect", byId.getClass()).invoke(mcPlayer, byId);
        } catch (Exception ignored) {}
    }

    @Override
    public Collection<PotionEffect> getActivePotionEffects() {
        if (mcPlayer == null) return List.of();
        try {
            var effects = mcPlayer.getClass().getMethod("getActiveEffects").invoke(mcPlayer);
            if (effects instanceof java.util.Collection<?> col) {
                var result = new ArrayList<PotionEffect>();
                for (var inst : col) {
                    try {
                        var effectTypeField = inst.getClass().getMethod("getEffect").invoke(inst);
                        var reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("MOB_EFFECT").get(null);
                        var id = reg.getClass().getMethod("getId", reg.getClass().getInterfaces()[0]).invoke(reg, effectTypeField);
                        var type = PotionEffectType.getById((int) id);
                        if (type == null) continue;
                        var duration = (int) inst.getClass().getMethod("getDuration").invoke(inst);
                        var amplifier = (int) inst.getClass().getMethod("getAmplifier").invoke(inst);
                        var ambient = (boolean) inst.getClass().getMethod("isAmbient").invoke(inst);
                        var particles = (boolean) inst.getClass().getMethod("isVisible").invoke(inst);
                        result.add(new PotionEffect(type, duration, amplifier, ambient, particles));
                    } catch (Exception ignored) {}
                }
                return result;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    @Override
    public boolean hasLineOfSight(Entity other) {
        if (mcPlayer == null || other == null) return false;
        try {
            var handler = java.lang.reflect.Proxy.getInvocationHandler(other);
            var field = handler.getClass().getDeclaredField("nmsEntity");
            field.setAccessible(true);
            var otherNms = field.get(handler);
            return (boolean) mcPlayer.getClass().getMethod("hasLineOfSight", otherNms.getClass()).invoke(mcPlayer, otherNms);
        } catch (Exception e) { return false; }
    }

    @Override
    public boolean getRemoveWhenFarAway() {
        if (mcPlayer == null) return false;
        try { return !(boolean) mcPlayer.getClass().getMethod("isPersistenceRequired").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public void setRemoveWhenFarAway(boolean remove) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setPersistenceRequired", boolean.class).invoke(mcPlayer, !remove); } catch (Exception ignored) {}
    }

    @Override
    public EntityEquipment getEquipment() {
        if (mcPlayer == null) return null;
        try {
            var nmsEquip = mcInvoke(mcPlayer, "getEquipment");
            if (nmsEquip == null) return null;
            if (nmsEquip instanceof EntityEquipment e) return e;
            return (EntityEquipment) java.lang.reflect.Proxy.newProxyInstance(
                EntityEquipment.class.getClassLoader(),
                new Class<?>[]{EntityEquipment.class},
                (p, m, a) -> {
                    if (m.getReturnType() == void.class) return null;
                    return switch (m.getName()) {
                        case "getItemInMainHand" -> {
                            var held = mcInvoke(nmsEquip, "getItemInMainHand");
                            yield held instanceof ItemStack is ? is : ItemStack.empty();
                        }
                        case "getItemInOffHand" -> {
                            var held = mcInvoke(nmsEquip, "getItemInOffHand");
                            yield held instanceof ItemStack is ? is : ItemStack.empty();
                        }
                        case "getHelmet", "getChestplate", "getLeggings", "getBoots" -> {
                            var slot = m.getName().substring(3).toUpperCase();
                            try {
                                var getSlot = nmsEquip.getClass().getMethod("getItem", java.lang.reflect.Field.class);
                                var equipmentSlotClass = Class.forName("net.minecraft.world.entity.EquipmentSlot");
                                var valueOf = equipmentSlotClass.getMethod("valueOf", String.class);
                                var slotEnum = valueOf.invoke(null, slot);
                                var item = getSlot.invoke(nmsEquip, slotEnum);
                                yield item instanceof ItemStack is ? is : ItemStack.empty();
                            } catch (Exception ex) { yield ItemStack.empty(); }
                        }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "VeltisEntityEquipment";
                        default -> null;
                    };
                });
        } catch (Exception e) { return null; }
    }

    @Override
    public void setCanPickupItems(boolean pickup) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setCanPickUpLoot", boolean.class).invoke(mcPlayer, pickup); } catch (Exception ignored) {}
    }

    @Override
    public boolean getCanPickupItems() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("canPickUpLoot").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public boolean isLeashed() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("isLeashed").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public boolean setLeashHolder(Entity holder) {
        if (mcPlayer == null || holder == null) return false;
        try {
            var handler = java.lang.reflect.Proxy.getInvocationHandler(holder);
            var field = handler.getClass().getDeclaredField("nmsEntity");
            field.setAccessible(true);
            var holderNms = field.get(handler);
            mcPlayer.getClass().getMethod("setLeashHolder", holderNms.getClass(), boolean.class).invoke(mcPlayer, holderNms, true);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override
    public boolean isGliding() {
        return false;
    }

    @Override
    public void setGliding(boolean p0) {
    }

    @Override
    public boolean isSwimming() {
        return mcCall("isSwimming", false);
    }

    @Override
    public void setSwimming(boolean p0) {
    }

    @Override
    public boolean isRiptiding() {
        return false;
    }

    @Override
    public void setRiptiding(boolean p0) {
    }

    @Override
    public boolean isSleeping() {
        return false;
    }

    @Override
    public boolean isClimbing() {
        return false;
    }

    @Override
    public void attack(Entity p0) {
    }

    @Override
    public void swingMainHand() {
    }

    @Override
    public void swingOffHand() {
    }

    @Override
    public void playHurtAnimation(float p0) {
    }

    @Override
    public Sound getHurtSound() {
        return null;
    }

    @Override
    public Sound getDeathSound() {
        return null;
    }

    @Override
    public Sound getFallDamageSound(int p0) {
        return null;
    }

    @Override
    public Sound getFallDamageSoundSmall() {
        return null;
    }

    @Override
    public Sound getFallDamageSoundBig() {
        return null;
    }

    @Override
    public Sound getDrinkingSound(ItemStack p0) {
        return null;
    }

    @Override
    public Sound getEatingSound(ItemStack p0) {
        return null;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return false;
    }

    @Override
    public EntityCategory getCategory() {
        return null;
    }

    private volatile VeltisPlayerInventory playerInventory;
    private volatile Inventory enderChest;

    @Override
    public PlayerInventory getInventory() {
        if (playerInventory == null) {
            synchronized (this) {
                if (playerInventory == null) {
                    playerInventory = new VeltisPlayerInventory(mcPlayer, this);
                }
            }
        }
        return playerInventory;
    }

    @Override
    public Inventory getEnderChest() {
        if (enderChest == null) {
            synchronized (this) {
                if (enderChest == null) {
                    enderChest = createEnderChestInventory();
                }
            }
        }
        return enderChest;
    }

    private Inventory createEnderChestInventory() {
        try {
            if (mcPlayer == null) return VeltisInventoryProxy.createInventory(null, 27, "container.enderchest");
            var enderChestInv = mcPlayer.getClass().getMethod("getEnderChestInventory").invoke(mcPlayer);
            if (enderChestInv != null) {
                VeltisItemStackBridge.initialize();
                return VeltisInventoryProxy.wrapNmsContainer(enderChestInv, null, InventoryType.ENDER_CHEST, "container.enderchest");
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "Failed to create ender chest inventory", e);
        }
        return VeltisInventoryProxy.createInventory(null, 27, "container.enderchest");
    }

    @Override
    public MainHand getMainHand() {
        try {
            if (mcPlayer == null) return MainHand.RIGHT;
            var mainHand = mcPlayer.getClass().getMethod("getMainArm").invoke(mcPlayer);
            if (mainHand != null) {
                var name = mainHand.getClass().getMethod("name").invoke(mainHand).toString();
                return "RIGHT".equals(name) ? MainHand.RIGHT : MainHand.LEFT;
            }
        } catch (Exception ignored) {}
        return MainHand.RIGHT;
    }

    @Override
    public boolean setWindowProperty(InventoryView.Property p0, int p1) {
        return false;
    }

    @Override
    public int getEnchantmentSeed() {
        return 0;
    }

    @Override
    public void setEnchantmentSeed(int p0) {
    }

    private volatile InventoryView currentOpenInventory;

    @Override
    public InventoryView getOpenInventory() {
        var view = currentOpenInventory;
        if (view != null) return view;
        return createDefaultInventoryView();
    }

    private InventoryView createDefaultInventoryView() {
        var inv = getInventory();
        return new InventoryView() {
            @Override public @NotNull Inventory getTopInventory() { return inv; }
            @Override public @NotNull Inventory getBottomInventory() { return inv; }
            @Override public @NotNull HumanEntity getPlayer() { return VeltisPlayerSender.this; }
            @Override public @NotNull InventoryType getType() { return InventoryType.PLAYER; }
            @Override public void setCursor(@Nullable ItemStack item) { VeltisPlayerSender.this.setItemOnCursor(item); }
            @Override public @Nullable ItemStack getCursor() { return VeltisPlayerSender.this.getItemOnCursor(); }
            @Override public @NotNull String getTitle() { return "container.inventory"; }
            @Override public @NotNull String getOriginalTitle() { return "container.inventory"; }
            @Override public void setTitle(@NotNull String title) {}
            @Override public @Nullable Inventory getInventory(int rawSlot) {
                if (rawSlot < 0) return null;
                if (rawSlot < 41) return inv;
                return inv;
            }
            @Override public int convertSlot(int rawSlot) { return rawSlot; }
            @Override public @NotNull InventoryType.SlotType getSlotType(int slot) { return InventoryType.SlotType.CONTAINER; }
            @Override public void open() { VeltisPlayerSender.this.openInventory(this); }
            @Override public void close() { VeltisPlayerSender.this.closeInventory(); }
            @Override public int countSlots() { return inv.getSize(); }
            @Override public boolean setProperty(@NotNull Property prop, int value) { return false; }
            @Override public void setItem(int slot, @Nullable ItemStack item) { inv.setItem(slot, item); }
            @Override public @Nullable ItemStack getItem(int slot) { return inv.getItem(slot); }
            @Override public @Nullable MenuType getMenuType() { return null; }
        };
    }

    @Override
    public InventoryView openInventory(Inventory inventory) {
        if (inventory == null) return createDefaultInventoryView();
        var view = createInventoryView(null, inventory);
        currentOpenInventory = view;
        return view;
    }

    private InventoryView createInventoryView(Object containerMenu, Inventory inventory) {
        var inv = inventory != null ? inventory : (Inventory) getInventory();
        return new InventoryView() {
            @Override public @NotNull Inventory getTopInventory() { return inv; }
            @Override public @NotNull Inventory getBottomInventory() { return VeltisPlayerSender.this.getInventory(); }
            @Override public @NotNull HumanEntity getPlayer() { return VeltisPlayerSender.this; }
            @Override public @NotNull InventoryType getType() { return inv.getType(); }
            @Override public void setCursor(@Nullable ItemStack item) { VeltisPlayerSender.this.setItemOnCursor(item); }
            @Override public @Nullable ItemStack getCursor() { return VeltisPlayerSender.this.getItemOnCursor(); }
            @Override public @NotNull String getTitle() { return "container.inventory"; }
            @Override public @NotNull String getOriginalTitle() { return "container.inventory"; }
            @Override public void setTitle(@NotNull String title) {}
            @Override public @Nullable Inventory getInventory(int rawSlot) { return rawSlot < 0 ? null : inv; }
            @Override public int convertSlot(int rawSlot) { return rawSlot; }
            @Override public @NotNull InventoryType.SlotType getSlotType(int slot) { return InventoryType.SlotType.CONTAINER; }
            @Override public void open() {}
            @Override public void close() { currentOpenInventory = null; }
            @Override public int countSlots() { return inv.getSize(); }
            @Override public boolean setProperty(@NotNull Property prop, int value) { return false; }
            @Override public void setItem(int slot, @Nullable ItemStack item) { inv.setItem(slot, item); }
            @Override public @Nullable ItemStack getItem(int slot) { return inv.getItem(slot); }
            @Override public @Nullable MenuType getMenuType() { return null; }
        };
    }

    @Override
    public InventoryView openWorkbench(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openEnchanting(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openMerchant(Merchant p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openAnvil(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openCartographyTable(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openGrindstone(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openLoom(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openSmithingTable(Location p0, boolean p1) {
        return null;
    }

    @Override
    public InventoryView openStonecutter(Location p0, boolean p1) {
        return null;
    }

    private volatile ItemStack itemOnCursor;

    @Override
    public ItemStack getItemInHand() {
        return getInventory().getItemInHand();
    }

    @Override
    public void setItemInHand(ItemStack p0) {
        getInventory().setItemInHand(p0);
    }

    @Override
    public ItemStack getItemOnCursor() {
        var held = itemOnCursor;
        return held != null ? held : ItemStack.empty();
    }

    @Override
    public void setItemOnCursor(ItemStack item) {
        this.itemOnCursor = item != null ? item.clone() : ItemStack.empty();
        if (mcPlayer != null) {
            try {
                var container = mcCall("containerMenu", (Object) null);
                if (container != null) {
                    var nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                    var nms = VeltisItemStackBridge.toNms(item);
                    container.getClass().getMethod("setCarried", nmsItemStackClass).invoke(container, nms);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean hasCooldown(Material p0) {
        return false;
    }

    @Override
    public int getCooldown(Material p0) {
        return 0;
    }

    @Override
    public boolean isDeeplySleeping() {
        return false;
    }

    @Override
    public void setCooldown(ItemStack p0, int p1) {
    }

    @Override
    public int getSleepTicks() {
        return 0;
    }

    @Override
    public boolean sleep(Location p0, boolean p1) {
        return false;
    }

    @Override
    public void wakeup(boolean p0) {
    }

    @Override
    public void startRiptideAttack(int p0, float p1, ItemStack p2) {
    }

    @Override
    public Location getBedLocation() {
        return null;
    }

    @Override
    public GameMode getGameMode() {
        if (mcPlayer == null) return GameMode.SURVIVAL;
        try {
            Object gt = mcInvoke(mcPlayer, "getGameType");
            if (gt == null) {
                var gm = mcField("gameMode");
                if (gm != null) gt = mcInvoke(gm, "getGameModeForPlayer");
            }
            if (gt == null) return GameMode.SURVIVAL;
            return switch (gt.toString()) {
                case "SURVIVAL" -> GameMode.SURVIVAL;
                case "CREATIVE" -> GameMode.CREATIVE;
                case "ADVENTURE" -> GameMode.ADVENTURE;
                case "SPECTATOR" -> GameMode.SPECTATOR;
                default -> GameMode.SURVIVAL;
            };
        } catch (Exception e) { return GameMode.SURVIVAL; }
    }

    @Override
    public void setGameMode(GameMode p0) {
        if (mcPlayer == null) { LOG.log(System.Logger.Level.WARNING, "setGameMode: mcPlayer is null"); return; }
        try {
            var gameType = Class.forName("net.minecraft.world.level.GameType");
            var valueOf = gameType.getMethod("valueOf", String.class);
            var gt = valueOf.invoke(null, p0.name());
            try {
                mcPlayer.getClass().getMethod("setGameMode", gameType).invoke(mcPlayer, gt);
                return;
            } catch (NoSuchMethodException e1) {
                LOG.log(System.Logger.Level.DEBUG, "setGameMode: direct call failed, trying PlayerList");
            }
            var srv = mcField("server");
            if (srv == null) { LOG.log(System.Logger.Level.WARNING, "setGameMode: server field is null"); return; }
            var playerList = mcInvoke(srv, "getPlayerList");
            if (playerList == null) { LOG.log(System.Logger.Level.WARNING, "setGameMode: playerList is null"); return; }
            playerList.getClass().getMethod("setGameMode", mcPlayer.getClass(), gameType).invoke(playerList, mcPlayer, gt);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "setGameMode failed for {0}: {1}", p0, e.getMessage());
        }
    }

    @Override
    public boolean isBlocking() {
        return false;
    }

    @Override
    public boolean isHandRaised() {
        return false;
    }

    @Override
    public int getExpToLevel() {
        return 0;
    }

    @Override
    public Entity releaseLeftShoulderEntity() {
        return null;
    }

    @Override
    public Entity releaseRightShoulderEntity() {
        return null;
    }

    @Override
    public float getAttackCooldown() {
        return 0f;
    }

    @Override
    public int discoverRecipes(Collection<NamespacedKey> p0) {
        return 0;
    }

    @Override
    public int undiscoverRecipes(Collection<NamespacedKey> p0) {
        return 0;
    }

    @Override
    public boolean hasDiscoveredRecipe(NamespacedKey p0) {
        return false;
    }

    @Override
    public Set<NamespacedKey> getDiscoveredRecipes() {
        return Set.of();
    }

    @Override
    public Entity getShoulderEntityLeft() {
        return null;
    }

    @Override
    public void setShoulderEntityLeft(Entity p0) {
    }

    @Override
    public Entity getShoulderEntityRight() {
        return null;
    }

    @Override
    public void setShoulderEntityRight(Entity p0) {
    }

    @Override
    public float getExhaustion() {
        return 0f;
    }

    @Override
    public void setExhaustion(float p0) {
        if (mcPlayer == null) return;
        try {
            var fd = mcPlayer.getClass().getMethod("getFoodData").invoke(mcPlayer);
            if (fd != null) setField(fd, "exhaustion", p0);
        } catch (Exception ignored) {}
    }

    @Override
    public float getSaturation() {
        return mcCall("getSaturation", 5.0f);
    }

    @Override
    public void setSaturation(float p0) {
        if (mcPlayer == null) return;
        try {
            var fd = mcPlayer.getClass().getMethod("getFoodData").invoke(mcPlayer);
            if (fd != null) setField(fd, "saturation", p0);
        } catch (Exception ignored) {}
    }

    @Override
    public int getFoodLevel() {
        return mcCall("getFoodLevel", 20);
    }

    @Override
    public void setFoodLevel(int p0) {
        if (mcPlayer == null) return;
        try {
            var fd = mcPlayer.getClass().getMethod("getFoodData").invoke(mcPlayer);
            if (fd != null) setField(fd, "foodLevel", p0);
        } catch (Exception ignored) {}
    }

    @Override
    public int getSaturatedRegenRate() {
        return 0;
    }

    @Override
    public void setSaturatedRegenRate(int p0) {
    }

    @Override
    public int getUnsaturatedRegenRate() {
        return 0;
    }

    @Override
    public void setUnsaturatedRegenRate(int p0) {
    }

    @Override
    public int getStarvationRate() {
        return 0;
    }

    @Override
    public void setStarvationRate(int p0) {
    }

    @Override
    public Location getLastDeathLocation() {
        return null;
    }

    @Override
    public void setLastDeathLocation(Location p0) {
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public void setDisplayName(String p0) {
    }

    @Override
    public String getPlayerListName() {
        return name;
    }

    @Override
    public void setPlayerListName(String p0) {
    }

    @Override
    public int getPlayerListOrder() {
        return 0;
    }

    @Override
    public void setPlayerListOrder(int p0) {
    }

    @Override
    public String getPlayerListHeader() {
        return "";
    }

    @Override
    public String getPlayerListFooter() {
        return "";
    }

    @Override
    public void setPlayerListHeader(String p0) {
    }

    @Override
    public void setPlayerListFooter(String p0) {
    }

    @Override
    public void setPlayerListHeaderFooter(String p0, String p1) {
    }

    @Override
    public void setPlayerListHeaderFooter(net.md_5.bungee.api.chat.@Nullable BaseComponent header, net.md_5.bungee.api.chat.@Nullable BaseComponent footer) {
    }

    @Override
    public void setPlayerListHeaderFooter(net.md_5.bungee.api.chat.BaseComponent @Nullable [] header, net.md_5.bungee.api.chat.BaseComponent @Nullable [] footer) {
    }

    private Location compassTarget;

    @Override
    public void setCompassTarget(Location loc) {
        this.compassTarget = loc;
    }

    @Override
    public Location getCompassTarget() {
        if (compassTarget != null) return compassTarget;
        try {
            var loc = mcCall("getRespawnPosition", (Location) null);
            if (loc != null) return loc;
        } catch (Exception ignored) {}
        try {
            var loc = getLocation();
            if (loc != null) return loc;
        } catch (Exception ignored) {}
        return new Location(null, 0, 0, 0);
    }

    @Override
    public InetSocketAddress getAddress() {
        try {
            var conn = mcField("connection");
            if (conn == null) return null;
            var network = mcInvoke(conn, "getRemoteAddress");
            if (network == null) return null;
            if (network instanceof InetSocketAddress addr) return addr;
            return InetSocketAddress.createUnresolved(network.toString(), 0);
        } catch (Exception e) { return null; }
    }

    @Override
    public InetSocketAddress getHAProxyAddress() {
        return getAddress();
    }

    @Override
    public boolean isTransferred() {
        return false;
    }

    @Override
    public void kickPlayer(String reason) {
        try {
            var connection = getConnectionField();
            if (connection == null) return;
            var componentClass = Class.forName("net.minecraft.network.chat.Component");
            var literal = componentClass.getMethod("literal", String.class);
            var component = literal.invoke(null, reason != null ? reason : "");
            connection.getClass().getMethod("disconnect", componentClass).invoke(connection, component);
        } catch (Exception ignored) {}
    }

    @Override
    public BanEntry<InetAddress> banIp(String p0, Date p1, String p2, boolean p3) {
        return null;
    }

    @Override
    public BanEntry<InetAddress> banIp(@Nullable String reason, @Nullable Instant expires, @Nullable String source, boolean kickPlayer) {
        return null;
    }

    @Override
    public BanEntry<InetAddress> banIp(@Nullable String reason, @Nullable Duration duration, @Nullable String source, boolean kickPlayer) {
        return null;
    }

    @Override
    public void chat(String p0) {
    }

    @Override
    public boolean isSneaking() {
        return mcCall("isShiftKeyDown", false);
    }

    @Override
    public void setSneaking(boolean p0) {
    }

    @Override
    public boolean isSprinting() {
        return mcCall("isSprinting", false);
    }

    @Override
    public void setSprinting(boolean p0) {
    }

    @Override
    public void saveData() {
    }

    @Override
    public void loadData() {
    }

    @Override
    public void setSleepingIgnored(boolean p0) {
    }

    @Override
    public boolean isSleepingIgnored() {
        return false;
    }

    @Override
    public void setRespawnLocation(Location p0, boolean p1) {
    }

    @Override
    public Collection<EnderPearl> getEnderPearls() {
        return List.of();
    }

    @Override
    public Input getCurrentInput() {
        return null;
    }

    @Override
    public void playNote(Location p0, Instrument p1, Note p2) {
    }

    @Override
    public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch) {
        if (mcPlayer == null || loc == null || sound == null) return;
        try {
            var key = sound.key();
            var levelObj = mcPlayer.getClass().getMethod("level").invoke(mcPlayer);
            if (levelObj == null) return;
            var rl = Class.forName("net.minecraft.resources.ResourceLocation")
                .getConstructor(String.class, String.class).newInstance(key.namespace(), key.value());
            var se = Class.forName("net.minecraft.sounds.SoundEvent")
                .getMethod("createVariableRangeEvent", rl.getClass()).invoke(null, rl);
            var seClass = se.getClass().getSuperclass();
            levelObj.getClass().getMethod("playSeededSound", mcPlayer.getClass(), double.class, double.class, double.class, seClass, seClass, float.class, float.class, long.class)
                .invoke(levelObj, mcPlayer, loc.getX(), loc.getY(), loc.getZ(), se, seClass.cast(se), volume, pitch, 0L);
        } catch (Exception e) { LOG.log(System.Logger.Level.DEBUG, "playSound failed: {0}", e.getMessage()); }
    }

    @Override
    public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch) {
        if (entity != null) playSound(entity.getLocation(), sound, category, volume, pitch);
    }

    @Override
    public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch) {
        if (entity != null) playSound(entity.getLocation(), sound, category, volume, pitch);
    }

    @Override
    public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
        playSound(entity, sound, category, volume, pitch);
    }

    @Override
    public void playSound(Location loc, Sound sound, SoundCategory category, float volume, float pitch, long seed) {
        playSound(loc, sound, category, volume, pitch);
    }

    @Override
    public void playSound(Location location, String sound, SoundCategory category, float volume, float pitch) {
        if (location != null && sound != null) { try { playSound(location, Sound.valueOf(sound.toUpperCase()), category, volume, pitch); } catch (Exception ignored) {} }
    }

    @Override
    public void playSound(Location location, String sound, SoundCategory category, float volume, float pitch, long seed) {
        playSound(location, sound, category, volume, pitch);
    }

    @Override
    public void playSound(Entity entity, String sound, SoundCategory category, float volume, float pitch, long seed) {
        playSound(entity, sound, category, volume, pitch);
    }

    @Override
    public void stopSound(String p0, SoundCategory p1) {
    }

    @Override
    public void stopSound(SoundCategory p0) {
    }

    @Override
    public void stopAllSounds() {
    }

    @Override
    public void playEffect(Location p0, Effect p1, int p2) {
    }

    @Override
    public <T> void playEffect(Location loc, Effect effect, @Nullable T data) {
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Date expires, @Nullable String source, boolean kickPlayer) {
        return null;
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Instant expires, @Nullable String source, boolean kickPlayer) {
        return null;
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Duration duration, @Nullable String source, boolean kickPlayer) {
        return null;
    }

    @Override
    public void kick(final net.kyori.adventure.text.@Nullable Component message, org.bukkit.event.player.PlayerKickEvent.Cause cause) {
    }

    @Override
    public void sendMessage(final net.kyori.adventure.text.@NotNull Component message) {
        sendLegacyMessage(LegacyComponentSerializer.legacySection().serialize(message));
    }

    @Override
    public void sendMessage(final net.kyori.adventure.text.@NotNull Component message, final net.kyori.adventure.audience.@NotNull MessageType type) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(final net.kyori.adventure.identity.@NotNull Identity identity, final net.kyori.adventure.text.@NotNull Component message, final net.kyori.adventure.audience.@NotNull MessageType type) {
        sendMessage(message);
    }

    @Override
    public net.kyori.adventure.text.@Nullable Component playerListHeader() {
        return null;
    }

    @Override
    public void openInventory(InventoryView inventory) {
    }

    @Override
    public void setHurtDirection(float hurtDirection) {
    }

    @Override
    public boolean hasCooldown(ItemStack item) {
        return false;
    }

    @Override
    public int getCooldown(ItemStack item) {
        return 0;
    }

    @Override
    public int getCooldown(Key key) {
        return 0;
    }

    @Override
    public void setCooldown(Key key, int ticks) {
    }

    @Override
    public @Nullable Location getPotentialRespawnLocation() {
        return null;
    }

    @Override
    public @Nullable FishHook getFishHook() {
        return null;
    }

    @Override
    public @NotNull CombatTracker getCombatTracker() {
        return null;
    }

    @Override
    public @NotNull Key getWaypointStyle() {
        return Key.key("minecraft:arrow");
    }

    @Override
    public void setWaypointStyle(@Nullable Key key) {
    }

    @Override
    public void setWaypointColor(@Nullable Color color) {
    }

    @Override
    public void broadcastSlotBreak(@NotNull EquipmentSlot slot) {
    }

    @Override
    public void broadcastSlotBreak(@NotNull EquipmentSlot slot, @NotNull Collection<Player> players) {
    }

    @Override
    public void knockback(double strength, double directionX, double directionZ) {
    }

    @Override
    public float getHurtDirection() {
        return 0;
    }

    @Override
    public void playPickupItemAnimation(@NotNull Item item, int quantity) {
    }

    @Override
    public boolean isJumping() {
        return false;
    }

    @Override
    public void setJumping(boolean jumping) {
    }

    @Override
    public void startUsingItem(@NotNull EquipmentSlot hand) {
    }

    @Override
    public void completeUsingActiveItem() {
    }

    @Override
    public @NotNull ItemStack getActiveItem() {
        return ItemStack.empty();
    }

    @Override
    public void clearActiveItem() {
    }

    @Override
    public int getActiveItemRemainingTime() {
        return 0;
    }

    @Override
    public void setActiveItemRemainingTime(int ticks) {
    }

    @Override
    public boolean hasActiveItem() {
        return false;
    }

    @Override
    public int getActiveItemUsedTime() {
        return 0;
    }

    @Override
    public @NotNull EquipmentSlot getActiveItemHand() {
        return EquipmentSlot.HAND;
    }

    @Override
    public float getSidewaysMovement() {
        return 0;
    }

    @Override
    public float getUpwardsMovement() {
        return 0;
    }

    @Override
    public float getForwardsMovement() {
        return 0;
    }

    @Override
    public int getArrowCooldown() {
        return 0;
    }

    @Override
    public int getArrowsInBody() {
        return 0;
    }

    @Override
    public void setArrowsInBody(int count, boolean fireEvent) {
    }

    @Override
    public int getBeeStingerCooldown() {
        return 0;
    }

    @Override
    public int getBeeStingersInBody() {
        return 0;
    }

    @Override
    public void registerAttribute(@NotNull Attribute attribute) {
    }

    @Override
    public void damage(double amount) {
        if (mcPlayer == null) return;
        try {
            var damageSources = mcPlayer.getClass().getMethod("damageSources").invoke(mcPlayer);
            var generic = damageSources.getClass().getMethod("generic").invoke(damageSources);
            mcPlayer.getClass().getMethod("hurt", generic.getClass(), float.class).invoke(mcPlayer, generic, (float) amount);
        } catch (Exception ignored) {}
    }

    @Override
    public int getPortalCooldown() {
        return 0;
    }

    @Override
    public void setPortalCooldown(int cooldown) {
    }

    @Override
    public boolean hasGravity() {
        if (mcPlayer == null) return true;
        try { return !(boolean) mcPlayer.getClass().getMethod("isNoGravity").invoke(mcPlayer); } catch (Exception e) { return true; }
    }

    @Override
    public void setGravity(boolean gravity) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setNoGravity", boolean.class).invoke(mcPlayer, !gravity); } catch (Exception ignored) {}
    }

    @Override
    public boolean isGlowing() {
        if (mcPlayer == null) return false;
        try { return (boolean) mcPlayer.getClass().getMethod("isGlowing").invoke(mcPlayer); } catch (Exception e) { return false; }
    }

    @Override
    public void setGlowing(boolean flag) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setGlowingTag", boolean.class).invoke(mcPlayer, flag); } catch (Exception ignored) {}
    }

    @Override
    public @NotNull Set<Player> getTrackedBy() {
        return Set.of();
    }

    @Override
    public boolean isTrackedBy(@NotNull Player player) {
        return false;
    }

    @Override
    public @NotNull ItemStack getPickItemStack() {
        return ItemStack.empty();
    }

    @Override
    public boolean hasNoPhysics() {
        return false;
    }

    @Override
    public void setNoPhysics(boolean noPhysics) {
    }

    @Override
    public boolean isInvisible() {
        return false;
    }

    @Override
    public void setInvisible(boolean invisible) {
    }

    @Override
    public boolean isFreezeTickingLocked() {
        return false;
    }

    @Override
    public void lockFreezeTicks(boolean locked) {
    }

    @Override
    public boolean isFrozen() {
        return false;
    }

    @Override
    public int getFreezeTicks() {
        return 0;
    }

    @Override
    public int getMaxFreezeTicks() {
        return 0;
    }

    @Override
    public void setFreezeTicks(int ticks) {
    }

    @Override
    public @NotNull TriState getVisualFire() {
        return TriState.FALSE;
    }

    @Override
    public void setVisualFire(@NotNull TriState fire) {
    }

    @Override
    public boolean isVisualFire() {
        return false;
    }

    @Override
    public void setVisualFire(boolean fire) {
    }

    @Override
    public @NotNull TriState getFrictionState() {
        return TriState.FALSE;
    }

    @Override
    public void setFrictionState(@NotNull TriState state) {
    }

    @Override
    public @NotNull Set<String> getScoreboardTags() {
        return Set.of();
    }

    @Override
    public boolean addScoreboardTag(@NotNull String tag) {
        return false;
    }

    @Override
    public boolean removeScoreboardTag(@NotNull String tag) {
        return false;
    }

    @Override
    public @NotNull Pose getPose() {
        return Pose.STANDING;
    }

    @Override
    public @NotNull BlockFace getFacing() {
        return BlockFace.SOUTH;
    }

    @Override
    public @NotNull PistonMoveReaction getPistonMoveReaction() {
        return PistonMoveReaction.IGNORE;
    }

    @Override
    public boolean isInWorld() {
        return true;
    }

    @Override
    public @NotNull SpawnCategory getSpawnCategory() {
        return SpawnCategory.MISC;
    }

    @Override
    public boolean hasFixedPose() {
        return false;
    }

    @Override
    public void setPose(@NotNull Pose pose, boolean fixed) {
    }

    @Override
    public @Nullable String getAsString() {
        return null;
    }

    @Override
    public @Nullable EntitySnapshot createSnapshot() {
        return null;
    }

    @Override
    public @NotNull Entity copy() {
        return null;
    }

    @Override
    public @NotNull Entity copy(@NotNull Location to) {
        return null;
    }

    @Override
    public @Nullable Location getOrigin() {
        return null;
    }

    @Override
    public @NotNull Component teamDisplayName() {
        return Component.empty();
    }

    @Override
    public @NotNull CreatureSpawnEvent.SpawnReason getEntitySpawnReason() {
        return CreatureSpawnEvent.SpawnReason.DEFAULT;
    }

    @Override
    public boolean fromMobSpawner() {
        return false;
    }

    @Override
    public boolean isUnderWater() {
        return false;
    }

    @Override
    public boolean isInRain() {
        return false;
    }

    @Override
    public boolean isInLava() {
        return false;
    }

    @Override
    public boolean isTicking() {
        return true;
    }

    @Override
    public @NotNull Set<Player> getTrackedPlayers() {
        return Set.of();
    }

    @Override
    public boolean spawnAt(@NotNull Location location, @NotNull CreatureSpawnEvent.SpawnReason reason) {
        return false;
    }

    @Override
    public boolean isInPowderedSnow() {
        return false;
    }

    @Override
    public double getX() {
        if (mcPlayer == null) return 0;
        try { return (double) mcPlayer.getClass().getMethod("getX").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public double getY() {
        if (mcPlayer == null) return 0;
        try { return (double) mcPlayer.getClass().getMethod("getY").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public double getZ() {
        if (mcPlayer == null) return 0;
        try { return (double) mcPlayer.getClass().getMethod("getZ").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public float getPitch() {
        if (mcPlayer == null) return 0;
        try { return (float) mcPlayer.getClass().getMethod("getXRot").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public float getYaw() {
        if (mcPlayer == null) return 0;
        try { return (float) mcPlayer.getClass().getMethod("getYRot").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public net.kyori.adventure.text.@NotNull Component name() {
        return Component.empty();
    }

    @Override
    public int getProtocolVersion() {
        return 0;
    }

    @Override
    public @Nullable InetSocketAddress getVirtualHost() {
        return null;
    }

    @Override
    public @NotNull Map<String, Object> serialize() {
        return Map.of();
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Date expires, @Nullable String source) {
        return null;
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Instant expires, @Nullable String source) {
        return null;
    }

    @Override
    public <E extends BanEntry<? super com.destroystokyo.paper.profile.PlayerProfile>> E ban(@Nullable String reason, @Nullable Duration duration, @Nullable String source) {
        return null;
    }

    @Override
    public @Nullable Location getRespawnLocation(boolean loadLocationAndValidate) {
        return null;
    }

    @Override
    public int getStatistic(Statistic statistic) throws IllegalArgumentException {
        return 0;
    }

    @Override
    public int getStatistic(Statistic statistic, Material material) throws IllegalArgumentException {
        return 0;
    }

    @Override
    public int getStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
        return 0;
    }

    @Override
    public void incrementStatistic(Statistic statistic) throws IllegalArgumentException {
    }

    @Override
    public void decrementStatistic(Statistic statistic) throws IllegalArgumentException {
    }

    @Override
    public void incrementStatistic(Statistic statistic, int amount) throws IllegalArgumentException {
    }

    @Override
    public void decrementStatistic(Statistic statistic, int amount) throws IllegalArgumentException {
    }

    @Override
    public void setStatistic(Statistic statistic, int newValue) throws IllegalArgumentException {
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material) throws IllegalArgumentException {
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material) throws IllegalArgumentException {
    }

    @Override
    public void incrementStatistic(Statistic statistic, Material material, int amount) throws IllegalArgumentException {
    }

    @Override
    public void decrementStatistic(Statistic statistic, Material material, int amount) throws IllegalArgumentException {
    }

    @Override
    public void setStatistic(Statistic statistic, Material material, int newValue) throws IllegalArgumentException {
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
    }

    @Override
    public void decrementStatistic(Statistic statistic, EntityType entityType) throws IllegalArgumentException {
    }

    @Override
    public void incrementStatistic(Statistic statistic, EntityType entityType, int amount) throws IllegalArgumentException {
    }

    @Override
    public <T extends Projectile> @NotNull T launchProjectile(@NotNull Class<? extends T> projectile, @Nullable Vector velocity, @Nullable Consumer<? super T> function) {
        return null;
    }

    @Override
    public <T> @Nullable T getData(final DataComponentType.Valued<T> type) {
        return null;
    }

    @Override
    public <T> @Nullable T getDataOrDefault(final DataComponentType.Valued<? extends T> type, final @Nullable T fallback) {
        return null;
    }

    @Override
    public boolean hasData(final DataComponentType type) {
        return false;
    }

    @Override
    public @NotNull PersistentDataContainer getPersistentDataContainer() {
        return pdc;
    }

    @Override
    public net.kyori.adventure.text.@Nullable Component customName() {
        return null;
    }

    @Override
    public void customName(final net.kyori.adventure.text.@Nullable Component customName) {
    }

    @Override
    public boolean isPermissionSet(@NotNull Permission perm) {
        return permBase.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(@NotNull Permission permission) {
        if (isOp()) return true;
        return permBase.hasPermission(permission);
    }

    @Override
    public void sendMessage(@NotNull String... messages) {
        for (var msg : messages) sendLegacyMessage(msg);
    }

    @Override
    public void sendMessage(@Nullable UUID sender, @NotNull String... messages) {
        for (var msg : messages) sendLegacyMessage(msg);
    }

    @Override
    public boolean teleport(@NotNull Location location, @NotNull TeleportCause cause, @NotNull io.papermc.paper.entity.TeleportFlag @NotNull... teleportFlags) {
        return teleportAsync(location, cause, teleportFlags).join();
    }

    @Override
    public void lookAt(double x, double y, double z, @NotNull LookAnchor entityAnchor) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("lookAt", double.class, double.class, double.class).invoke(mcPlayer, x, y, z); } catch (Exception ignored) {}
    }

    @Override
    public boolean teleport(@NotNull Entity destination) {
        if (destination == null) return false;
        return teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(@NotNull Entity destination, @NotNull TeleportCause cause) {
        if (destination == null) return false;
        return teleport(destination.getLocation(), cause);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> teleportAsync(@NotNull Location loc, @NotNull TeleportCause cause, @NotNull io.papermc.paper.entity.TeleportFlag @NotNull... teleportFlags) {
        if (mcPlayer == null) return CompletableFuture.completedFuture(false);
        try {
            Object targetLevel = mcInvoke(mcPlayer, "level");
            if (loc.getWorld() != null) {
                var worldName = loc.getWorld().getName();
                try {
                    var srv = mcField("server");
                    if (srv != null) {
                        var levelMap = mcInvoke(srv, "levels");
                        if (levelMap instanceof java.util.Map<?,?> map) {
                            for (var entry : map.entrySet()) {
                                if (entry.getKey().toString().contains(worldName)) {
                                    targetLevel = entry.getValue();
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.DEBUG, "teleportAsync: world lookup failed: {0}", e.getMessage());
                }
            }
            if (targetLevel == null) {
                LOG.log(System.Logger.Level.WARNING, "teleportAsync: target level not found");
                return CompletableFuture.completedFuture(false);
            }
            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
            float yaw = loc.getYaw(), pitch = loc.getPitch();
            try {
                var method = mcPlayer.getClass().getMethod("teleportTo",
                    targetLevel.getClass(), double.class, double.class, double.class,
                    java.util.Set.class, float.class, float.class, boolean.class);
                method.invoke(mcPlayer, targetLevel, x, y, z, java.util.Collections.emptySet(), yaw, pitch, false);
            } catch (NoSuchMethodException e) {
                try {
                    var method = mcPlayer.getClass().getMethod("teleportTo",
                        targetLevel.getClass(), double.class, double.class, double.class, float.class, float.class);
                    method.invoke(mcPlayer, targetLevel, x, y, z, yaw, pitch);
                } catch (NoSuchMethodException e2) {
                    try {
                        var method = mcPlayer.getClass().getMethod("teleportTo",
                            targetLevel.getClass(), double.class, double.class, double.class, java.util.Set.class, float.class, float.class);
                        method.invoke(mcPlayer, targetLevel, x, y, z, java.util.Collections.emptySet(), yaw, pitch);
                    } catch (NoSuchMethodException e3) {
                        LOG.log(System.Logger.Level.WARNING, "teleportAsync: no teleportTo method found, trying ServerPlayer#connection#teleport");
                        try {
                            var connection = mcPlayer.getClass().getMethod("getConnection").invoke(mcPlayer);
                            connection.getClass().getMethod("teleport", double.class, double.class, double.class, float.class, float.class)
                                .invoke(connection, x, y, z, yaw, pitch);
                        } catch (Exception e4) {
                            LOG.log(System.Logger.Level.WARNING, "teleportAsync: all teleport approaches failed: {0}", e4.getMessage());
                            return CompletableFuture.completedFuture(false);
                        }
                    }
                }
            }
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "teleportAsync failed: {0}", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public boolean wouldCollideUsing(@NotNull BoundingBox boundingBox) {
        return false;
    }

    @Override
    public boolean collidesAt(@NotNull Location location) {
        return false;
    }

    @Override
    public @NotNull EntityScheduler getScheduler() {
        if (entityScheduler == null) {
            entityScheduler = new VeltisEntityScheduler(org.veltismc.veltis.VeltisBootstrap.scheduler(), mcPlayer);
        }
        return entityScheduler;
    }

    @Override
    public @NotNull String getScoreboardEntryName() {
        return "";
    }

    @Override
    public void broadcastHurtAnimation(@NotNull Collection<Player> players) {
    }

    @Override
    public void damage(double amount, @Nullable Entity source) {
        if (mcPlayer == null) return;
        try {
            var src = source != null ? java.lang.reflect.Proxy.getInvocationHandler(source).getClass().getDeclaredField("nmsEntity") : null;
            Object nmsSource = src != null ? src.get(java.lang.reflect.Proxy.getInvocationHandler(source)) : null;
            if (nmsSource != null) {
                var srcClass = nmsSource.getClass();
                try {
                    var damageSources = mcPlayer.getClass().getMethod("damageSources").invoke(mcPlayer);
                    var mobAttack = damageSources.getClass().getMethod("mobAttack", srcClass).invoke(damageSources, nmsSource);
                    mcPlayer.getClass().getMethod("hurt", mobAttack.getClass(), float.class).invoke(mcPlayer, mobAttack, (float) amount);
                } catch (Exception e2) {
                    mcPlayer.getClass().getMethod("hurt", Class.forName("net.minecraft.world.damagesource.DamageSource"), float.class).invoke(mcPlayer, Class.forName("net.minecraft.world.damagesource.DamageSources").getMethod("generic").invoke(mcPlayer.getClass().getMethod("damageSources").invoke(mcPlayer)), (float) amount);
                }
            } else {
                damage(amount);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void damage(double amount, DamageSource damageSource) {
        damage(amount);
    }

    @Override
    public double getHealth() {
        if (mcPlayer == null) return 20;
        try { return (float) mcPlayer.getClass().getMethod("getHealth").invoke(mcPlayer); } catch (Exception e) { return 20; }
    }

    @Override
    public void setHealth(double health) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setHealth", float.class).invoke(mcPlayer, (float) health); } catch (Exception ignored) {}
    }

    @Override
    public double getAbsorptionAmount() {
        if (mcPlayer == null) return 0;
        try { return (float) mcPlayer.getClass().getMethod("getAbsorptionAmount").invoke(mcPlayer); } catch (Exception e) { return 0; }
    }

    @Override
    public void setAbsorptionAmount(double amount) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("setAbsorptionAmount", float.class).invoke(mcPlayer, (float) amount); } catch (Exception ignored) {}
    }

    @Override
    public double getMaxHealth() {
        if (mcPlayer == null) return 20;
        try { return (double) mcPlayer.getClass().getMethod("getMaxHealth").invoke(mcPlayer); } catch (Exception e) { return 20; }
    }

    @Override
    public void setMaxHealth(double health) {
        if (mcPlayer == null) return;
        try {
            var attribute = mcPlayer.getClass().getMethod("getAttribute", Class.forName("net.minecraft.world.entity.ai.attributes.Attribute")).invoke(mcPlayer, Class.forName("net.minecraft.world.entity.ai.attributes.Attributes").getField("MAX_HEALTH").get(null));
            if (attribute != null) attribute.getClass().getMethod("setBaseValue", double.class).invoke(attribute, health);
        } catch (Exception ignored) {}
    }

    @Override
    public void resetMaxHealth() {
        setMaxHealth(20);
    }

    @Override
    public void heal(double amount, EntityRegainHealthEvent.RegainReason reason) {
        if (mcPlayer == null) return;
        try { mcPlayer.getClass().getMethod("heal", float.class).invoke(mcPlayer, (float) amount); } catch (Exception ignored) {}
    }

    @Override
    public void kill(DamageSource damageSource) {
        setHealth(0);
    }

    @Override
    public Block getTargetBlock(int maxDistance, @NotNull com.destroystokyo.paper.block.TargetBlockInfo.FluidMode fluidMode) {
        return null;
    }

    @Override
    public @Nullable BlockFace getTargetBlockFace(int maxDistance, @NotNull FluidCollisionMode fluidMode) {
        return null;
    }

    @Override
    public @Nullable RayTraceResult rayTraceEntities(int maxDistance, boolean ignoreBlocks) {
        return null;
    }

    @Override
    public boolean clearActivePotionEffects() {
        if (mcPlayer == null) return false;
        try { mcPlayer.getClass().getMethod("removeAllEffects").invoke(mcPlayer); return true; } catch (Exception e) { return false; }
    }

    @Override
    public boolean hasLineOfSight(@NotNull Location location) {
        return false;
    }

    @Override
    public @NotNull Entity getLeashHolder() throws IllegalStateException {
        return null;
    }

    @Override
    public boolean hasAI() {
        return false;
    }

    @Override
    public void setAI(boolean ai) {
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public void setCollidable(boolean collidable) {
    }

    @Override
    public @NotNull Set<UUID> getCollidableExemptions() {
        return Set.of();
    }

    @Override
    public @Nullable <T> T getMemory(@NotNull MemoryKey<T> memoryKey) {
        return null;
    }

    @Override
    public <T> void setMemory(@NotNull MemoryKey<T> memoryKey, @Nullable T memoryValue) {
    }

    @Override
    public @NotNull ItemStack damageItemStack(@NotNull ItemStack stack, int amount) {
        return null;
    }

    @Override
    public void damageItemStack(@NotNull EquipmentSlot slot, int amount) {
    }

    @Override
    public float getBodyYaw() {
        return 0;
    }

    @Override
    public void setBodyYaw(float bodyYaw) {
    }

    @Override
    public boolean canUseEquipmentSlot(@NotNull EquipmentSlot slot) {
        return false;
    }

    @Override
    public @Nullable Color getWaypointColor() {
        return null;
    }

    @Override
    public boolean dropItem(boolean dropAll) {
        return false;
    }

    @Override
    public Item dropItem(int slot, int amount, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        return null;
    }

    @Override
    public Item dropItem(EquipmentSlot slot, int amount, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        return null;
    }

    @Override
    public Item dropItem(final ItemStack itemStack, boolean throwRandomly, @Nullable Consumer<Item> entityOperation) {
        return null;
    }

    @Override
    public void closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        try {
            if (mcPlayer != null) {
                // Call NMS player's closeContainer method
                mcPlayer.getClass().getMethod("closeContainer").invoke(mcPlayer);
            }
        } catch (Exception ignored) {}
        currentOpenInventory = null;
    }

    @Override
    public void closeInventory() {
        closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNKNOWN);
    }

    @Override
    public @Nullable Firework fireworkBoost(ItemStack boosterItem) {
        return null;
    }

    @Override
    public Iterable<? extends net.kyori.adventure.bossbar.BossBar> activeBossBars() {
        return List.of();
    }

    @Override
    public net.kyori.adventure.text.Component displayName() {
        return net.kyori.adventure.text.Component.empty();
    }

    @Override
    public void displayName(final net.kyori.adventure.text.@Nullable Component displayName) {
    }

    @Override
    public void playerListName(net.kyori.adventure.text.@Nullable Component name) {
    }

    @Override
    public net.kyori.adventure.text.@Nullable Component playerListName() {
        return null;
    }

    @Override
    public net.kyori.adventure.text.@Nullable Component playerListFooter() {
        return null;
    }

    @Override
    public CompletableFuture<byte @Nullable []> retrieveCookie(NamespacedKey key) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void storeCookie(NamespacedKey key, byte[] value) {
    }

    @Override
    public void transfer(String host, int port) {
    }

    @Override
    public boolean performCommand(String command) {
        return false;
    }

    @Override
    public boolean breakBlock(Block p0) {
        return false;
    }

    @Override
    public void sendBlockChange(Location p0, Material p1, byte p2) {
    }

    @Override
    public void sendBlockChange(Location p0, BlockData p1) {
    }

    @Override
    public void sendBlockChanges(Collection<BlockState> p0) {
    }

    @Override
    public void sendBlockUpdate(Location loc, TileState tileState) throws IllegalArgumentException {
    }

    @Override
    public void sendSignChange(Location loc, @Nullable String @Nullable [] lines, DyeColor dyeColor, boolean hasGlowingText) throws IllegalArgumentException {
    }

    @Deprecated
    @Override
    public void sendSignChange(Location loc, java.util.@Nullable List<? extends net.kyori.adventure.text.Component> lines, DyeColor dyeColor, boolean hasGlowingText) throws IllegalArgumentException {
    }

    @Deprecated
    @Override
    public void sendSignChange(Location loc, @Nullable String @Nullable [] lines, DyeColor dyeColor) throws IllegalArgumentException {
    }

    @Deprecated
    @Override
    public void sendSignChange(Location loc, @Nullable String @Nullable [] lines) throws IllegalArgumentException {
    }

    @Override
    public void sendBlockDamage(Location p0, float p1, Entity p2) {
    }

    @Override
    public void sendBlockDamage(Location loc, float progress, int sourceId) {
    }

    @Override
    public void sendMultiBlockChange(Map<? extends Position, BlockData> blockChanges) {
    }

    @Override
    public void sendEquipmentChange(LivingEntity p0, EquipmentSlot p1, ItemStack p2) {
    }

    @Override
    public void sendEquipmentChange(LivingEntity entity, Map<EquipmentSlot, ItemStack> items) {
    }

    @Override
    public void sendPotionEffectChange(LivingEntity p0, PotionEffect p1) {
    }

    @Override
    public void sendPotionEffectChangeRemove(LivingEntity p0, PotionEffectType p1) {
    }

    @Override
    public void sendMap(MapView p0) {
    }

    @Override
    public void showWinScreen() {
    }

    @Override
    public boolean hasSeenWinScreen() {
        return false;
    }

    @Override
    public void setHasSeenWinScreen(boolean p0) {
    }

    @Override
    public void sendActionBar(String msg) {
        try {
            var componentClass = Class.forName("net.minecraft.network.chat.Component");
            var cmp = componentClass.getMethod("literal", String.class).invoke(null, msg);
            var packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
            var packet = packetClass.getConstructor(componentClass, boolean.class).newInstance(cmp, true);
            var connection = getConnectionField();
            if (connection != null) {
                connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void sendActionBar(char p0, String p1) {
        sendActionBar(p1);
    }

    @Override
    public void sendActionBar(net.md_5.bungee.api.chat.BaseComponent... message) {
        if (message != null && message.length > 0) sendActionBar(message[0].toLegacyText());
    }

    @Override
    public void setTitleTimes(int p0, int p1, int p2) {
    }

    @Override
    public void setSubtitle(net.md_5.bungee.api.chat.BaseComponent[] p0) {
    }

    @Override
    public void setSubtitle(net.md_5.bungee.api.chat.BaseComponent p0) {
    }

    @Override
    public void showTitle(net.md_5.bungee.api.chat.BaseComponent[] p0) {
    }

    @Override
    public void showTitle(net.md_5.bungee.api.chat.BaseComponent p0) {
    }

    @Override
    public void showTitle(net.md_5.bungee.api.chat.BaseComponent[] p0, net.md_5.bungee.api.chat.BaseComponent[] p1, int p2, int p3, int p4) {
    }

    @Override
    public void showTitle(net.md_5.bungee.api.chat.BaseComponent p0, net.md_5.bungee.api.chat.BaseComponent p1, int p2, int p3, int p4) {
    }

    @Override
    public void hideTitle() {
    }

    @Override
    public void sendHurtAnimation(float p0) {
    }

    @Override
    public void sendLinks(ServerLinks p0) {
    }

    @Override
    public void addCustomChatCompletions(Collection<String> p0) {
    }

    @Override
    public void removeCustomChatCompletions(Collection<String> p0) {
    }

    @Override
    public void setCustomChatCompletions(Collection<String> p0) {
    }

    @Override
    public void updateInventory() {
        try {
            if (mcPlayer != null) {
                var container = mcCall("containerMenu", (Object) null);
                if (container != null) {
                    container.getClass().getMethod("broadcastChanges").invoke(container);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public GameMode getPreviousGameMode() {
        return null;
    }

    @Override
    public void setPlayerTime(long p0, boolean p1) {
    }

    @Override
    public long getPlayerTime() {
        return 0L;
    }

    @Override
    public long getPlayerTimeOffset() {
        return 0L;
    }

    @Override
    public boolean isPlayerTimeRelative() {
        return false;
    }

    @Override
    public void resetPlayerTime() {
    }

    @Override
    public void setPlayerWeather(WeatherType p0) {
    }

    @Override
    public WeatherType getPlayerWeather() {
        return null;
    }

    @Override
    public void resetPlayerWeather() {
    }

    @Override
    public int getExpCooldown() {
        return 0;
    }

    @Override
    public void setExpCooldown(int p0) {
    }

    @Override
    public void giveExp(int p0, boolean p1) {
    }

    @Override
    public int applyMending(int p0) {
        return 0;
    }

    @Override
    public void giveExpLevels(int p0) {
    }

    @Override
    public float getExp() {
        if (mcPlayer == null) return 0f;
        var val = getField(mcPlayer, "experienceProgress");
        return val instanceof Number n ? n.floatValue() : 0f;
    }

    @Override
    public void setExp(float p0) {
        if (mcPlayer != null) setField(mcPlayer, "experienceProgress", p0);
    }

    @Override
    public int getLevel() {
        if (mcPlayer == null) return 0;
        var val = getField(mcPlayer, "experienceLevel");
        return val instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public void setLevel(int p0) {
        if (mcPlayer != null) setField(mcPlayer, "experienceLevel", p0);
    }

    @Override
    public int getTotalExperience() {
        if (mcPlayer == null) return 0;
        var val = getField(mcPlayer, "totalExperience");
        return val instanceof Number n ? n.intValue() : 0;
    }

    @Override
    public int getExperiencePointsNeededForNextLevel() {
        return 0;
    }

    @Override
    public int calculateTotalExperiencePoints() {
        return 0;
    }

    @Override
    public void setExperienceLevelAndProgress(int p0) {
    }

    @Override
    public void setTotalExperience(int p0) {
        if (mcPlayer != null) setField(mcPlayer, "totalExperience", p0);
    }

    @Override
    public void sendExperienceChange(float p0) {
    }

    @Override
    public void sendExperienceChange(float p0, int p1) {
    }

    @Override
    public boolean getAllowFlight() {
        var ab = abilities();
        if (ab == null) return false;
        var val = getField(ab, "mayfly");
        return val instanceof Boolean b && b;
    }

    @Override
    public void setAllowFlight(boolean p0) {
        var ab = abilities();
        if (ab != null) setField(ab, "mayfly", p0);
    }

    @Override
    public void setFlyingFallDamage(net.kyori.adventure.util.TriState p0) {
    }

    @Override
    public net.kyori.adventure.util.TriState hasFlyingFallDamage() {
        return null;
    }

    @Override
    public void hidePlayer(Player p0) {
        if (p0 != null) hiddenPlayers.add(p0.getUniqueId());
    }

    @Override
    public void showPlayer(Player p0) {
        if (p0 != null) hiddenPlayers.remove(p0.getUniqueId());
    }

    @Override
    public boolean canSee(Player p0) {
        return p0 == null || !hiddenPlayers.contains(p0.getUniqueId());
    }

    @Override
    public boolean canSee(Entity p0) {
        return p0 == null || !(p0 instanceof Player p) || !hiddenPlayers.contains(p.getUniqueId());
    }

    @Override
    public void hideEntity(Plugin p0, Entity p1) {
        if (p1 instanceof Player p) hidePlayer(p);
    }

    @Override
    public void showEntity(Plugin p0, Entity p1) {
        if (p1 instanceof Player p) showPlayer(p);
    }

    @Override
    public boolean isFlying() {
        var ab = abilities();
        if (ab == null) return false;
        var val = getField(ab, "flying");
        return val instanceof Boolean b && b;
    }

    @Override
    public void setFlying(boolean p0) {
        var ab = abilities();
        if (ab != null) setField(ab, "flying", p0);
    }

    @Override
    public float getFlySpeed() {
        var ab = abilities();
        if (ab == null) return 0.1f;
        var val = getField(ab, "flySpeed");
        return val instanceof Number n ? n.floatValue() : 0.1f;
    }

    @Override
    public float getWalkSpeed() {
        var ab = abilities();
        if (ab == null) return 0.2f;
        var val = getField(ab, "walkingSpeed");
        return val instanceof Number n ? n.floatValue() : 0.2f;
    }

    @Override
    public void setResourcePack(String p0, byte [] p1, String p2, boolean p3) {
    }

    @Override
    public void setResourcePack(UUID p0, String p1, byte [] p2, String p3, boolean p4) {
    }

    @Override
    public void addResourcePack(UUID p0, String p1, byte [] p2, String p3, boolean p4) {
    }

    @Override
    public void removeResourcePack(UUID p0) {
    }

    @Override
    public void removeResourcePacks() {
    }

    @Override
    public Scoreboard getScoreboard() {
        if (scoreboard != null) return scoreboard;
        return org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return null;
    }

    @Override
    public void setWorldBorder(WorldBorder p0) {
    }

    @Override
    public void sendHealthUpdate(double p0, int p1, float p2) {
    }

    @Override
    public void sendHealthUpdate() {
    }

    @Override
    public boolean isHealthScaled() {
        return false;
    }

    @Override
    public void setHealthScaled(boolean p0) {
    }

    @Override
    public double getHealthScale() {
        return 0.0;
    }

    @Override
    public Entity getSpectatorTarget() {
        return null;
    }

    @Override
    public void setSpectatorTarget(Entity p0) {
    }

    @Override
    public void sendTitle(String title, String subtitle) {
        sendTitle(title, subtitle, 10, 70, 20);
    }

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        try {
            var connection = getConnectionField();
            if (connection == null) return;
            var sendMethod = connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            var componentClass = Class.forName("net.minecraft.network.chat.Component");
            var literal = componentClass.getMethod("literal", String.class);

            if (fadeIn >= 0 && stay >= 0 && fadeOut >= 0) {
                var animPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket");
                var animPacket = animPacketClass.getConstructor(int.class, int.class, int.class).newInstance(fadeIn, stay, fadeOut);
                sendMethod.invoke(connection, animPacket);
            }
            if (subtitle != null && !subtitle.isEmpty()) {
                var sub = literal.invoke(null, subtitle);
                var subPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket");
                var subPacket = subPacketClass.getConstructor(componentClass).newInstance(sub);
                sendMethod.invoke(connection, subPacket);
            }
            if (title != null && !title.isEmpty()) {
                var ttl = literal.invoke(null, title);
                var titlePacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket");
                var titlePacket = titlePacketClass.getConstructor(componentClass).newInstance(ttl);
                sendMethod.invoke(connection, titlePacket);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void resetTitle() {
        try {
            var connection = getConnectionField();
            if (connection == null) return;
            var packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundClearTitlesPacket");
            var packet = packetClass.getConstructor(boolean.class).newInstance(false);
            connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")).invoke(connection, packet);
        } catch (Exception ignored) {}
    }

    @Override
    public void updateTitle(com.destroystokyo.paper.Title title) {
        if (title != null) {
            var t = title.getTitle();
            var s = title.getSubtitle();
            sendTitle(t != null ? net.md_5.bungee.api.chat.TextComponent.toPlainText(t) : "",
                      s != null ? net.md_5.bungee.api.chat.TextComponent.toPlainText(s) : "",
                      title.getFadeIn(), title.getStay(), title.getFadeOut());
        }
    }

    @Override
    public void sendTitle(com.destroystokyo.paper.Title title) {
        updateTitle(title);
    }

    @Override
    public AdvancementProgress getAdvancementProgress(Advancement p0) {
        return null;
    }

    @Override
    public int getClientViewDistance() {
        return 10;
    }

    @Override
    public int getPing() {
        return mcCall("getLatency", 0);
    }

    @Override
    public String getLocale() {
        return "en_US";
    }

    @Override
    public boolean getAffectsSpawning() {
        return false;
    }

    @Override
    public void setAffectsSpawning(boolean p0) {
    }

    @Override
    public int getViewDistance() {
        return 0;
    }

    @Override
    public void setViewDistance(int p0) {
    }

    @Override
    public int getSimulationDistance() {
        return 0;
    }

    @Override
    public void setSimulationDistance(int p0) {
    }

    @Override
    public int getSendViewDistance() {
        return 0;
    }

    @Override
    public void setSendViewDistance(int p0) {
    }

    @Override
    public void updateCommands() {
        if (mcPlayer == null) return;
        try { mcInvoke(mcPlayer, "refreshCommands"); } catch (Exception ignored) {}
    }

    @Override
    public void openSign(Sign p0, Side p1) {
    }

    @Override
    public void showDemoScreen() {
    }

    @Override
    public boolean isAllowingServerListings() {
        return false;
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isBanned() {
        return false;
    }

    @Override
    public boolean isWhitelisted() {
        return false;
    }

    @Override
    public void setWhitelisted(boolean p0) {
    }

    @Override
    public Player getPlayer() {
        return this;
    }

    @Override
    public long getFirstPlayed() {
        return 0L;
    }

    @Override
    public long getLastPlayed() {
        return 0L;
    }

    @Override
    public boolean hasPlayedBefore() {
        return true;
    }

    @Override
    public long getLastLogin() {
        return 0L;
    }

    @Override
    public long getLastSeen() {
        return 0L;
    }

    @Override
    public void decrementStatistic(Statistic p0, EntityType p1, int p2) {
    }

    @Override
    public void setStatistic(Statistic p0, EntityType p1, int p2) {
    }

    @Override
    public void sendPluginMessage(Plugin p0, String p1, byte [] p2) {
    }

    @Override
    public Set<String> getListeningPluginChannels() {
        return Set.of();
    }

    @Override
    public void setMetadata(String p0, MetadataValue p1) {
        metadataMap.computeIfAbsent(p0, k -> new ArrayList<>()).add(p1);
    }

    @Override
    public List<MetadataValue> getMetadata(String p0) {
        return metadataMap.getOrDefault(p0, List.of());
    }

    @Override
    public boolean hasMetadata(String p0) {
        return metadataMap.containsKey(p0) && !metadataMap.get(p0).isEmpty();
    }

    @Override
    public void removeMetadata(String p0, Plugin p1) {
        metadataMap.computeIfPresent(p0, (k, v) -> {
            v.removeIf(mv -> mv.getOwningPlugin() == p1);
            return v.isEmpty() ? null : v;
        });
    }

    @Override
    public String getCustomName() {
        if (mcPlayer == null) return "";
        try {
            var name = mcPlayer.getClass().getMethod("getCustomName").invoke(mcPlayer);
            if (name == null) return "";
            return (String) name.getClass().getMethod("getString").invoke(name);
        } catch (Exception e) { return ""; }
    }

    @Override
    public void setCustomName(String name) {
        if (mcPlayer == null) return;
        try {
            var componentClass = tryLoadNmsComponent();
            if (componentClass == null) return;
            if (name == null || name.isEmpty()) {
                mcPlayer.getClass().getMethod("setCustomName", componentClass).invoke(mcPlayer, (Object) null);
            } else {
                var literal = componentClass.getMethod("literal", String.class);
                var component = literal.invoke(null, name);
                mcPlayer.getClass().getMethod("setCustomName", componentClass).invoke(mcPlayer, component);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public @NotNull PlayerGameConnection getConnection() {
        return null;
    }

    @Override
    public void setDeathScreenScore(int score) {
    }

    @Override
    public int getDeathScreenScore() {
        return 0;
    }

    @Override
    public @NotNull PlayerGiveResult give(@NotNull Collection<ItemStack> items, boolean dropIfFull) {
        return null;
    }

    @Override
    public void sendEntityEffect(@NotNull EntityEffect effect, @NotNull Entity entity) {
    }

    private final Spigot spigot = new Spigot() {
        @Override
        public int getPing() {
            return VeltisPlayerSender.this.getPing();
        }
    };

    @Override
    public @NotNull Spigot spigot() {
        return spigot;
    }

    @Override
    public boolean isChunkSent(long chunkKey) {
        return false;
    }

    @Override
    public @NotNull Set<Chunk> getSentChunks() {
        return java.util.Collections.emptySet();
    }

    @Override
    public @NotNull Set<Long> getSentChunkKeys() {
        return java.util.Collections.emptySet();
    }

    @Override
    public void resetIdleDuration() {
    }

    @Override
    public @NotNull Duration getIdleDuration() {
        return Duration.ZERO;
    }

    @Override
    public void increaseWardenWarningLevel() {
    }

    @Override
    public void setWardenWarningLevel(int warningLevel) {
    }

    @Override
    public int getWardenWarningLevel() {
        return 0;
    }

    @Override
    public void setWardenTimeSinceLastWarning(int time) {
    }

    @Override
    public int getWardenTimeSinceLastWarning() {
        return 0;
    }

    @Override
    public void setWardenWarningCooldown(int cooldown) {
    }

    @Override
    public int getWardenWarningCooldown() {
        return 0;
    }

    @Override
    public void showElderGuardian(boolean silent) {
    }

    @Override
    public void lookAt(@NotNull Entity entity, @NotNull LookAnchor playerAnchor, @NotNull LookAnchor entityAnchor) {
    }

    @Override
    public @Nullable String getClientBrandName() {
        return null;
    }

    @Override
    public void removeAdditionalChatCompletions(@NotNull Collection<String> completions) {
    }

    @Override
    public void addAdditionalChatCompletions(@NotNull Collection<String> completions) {
    }

    @Override
    public void sendOpLevel(byte level) {
    }

    @Override
    public <T> T getClientOption(@NotNull ClientOption<T> option) {
        return null;
    }

    @Override
    public void resetCooldown() {
    }

    @Override
    public float getCooledAttackStrength(float adjustTicks) {
        return 0;
    }

    @Override
    public float getCooldownPeriod() {
        return 0;
    }

    @Override
    public void setPlayerProfile(@NotNull com.destroystokyo.paper.profile.PlayerProfile profile) {
    }

    @Override
    public @NotNull com.destroystokyo.paper.profile.PlayerProfile getPlayerProfile() {
        try {
            var profile = mcInvoke(mcPlayer, "getGameProfile");
            if (profile instanceof com.destroystokyo.paper.profile.PlayerProfile p) return p;
        } catch (Exception ignored) {}
        try {
            var cl = getClass().getClassLoader();
            var profileClass = cl.loadClass("com.destroystokyo.paper.profile.PlayerProfile");
            return (com.destroystokyo.paper.profile.PlayerProfile) profileClass.getDeclaredConstructor(UUID.class, String.class).newInstance(uuid, name);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create PlayerProfile", e);
        }
    }

    @Override
    public void openVirtualSign(@NotNull Position block, @NotNull Side side) {
    }

    @Override
    public void openBook(@NotNull ItemStack book) {
    }

    @Override
    public @NotNull Locale locale() {
        return Locale.US;
    }

    @Override
    public <T> void spawnParticle(@NotNull Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra, @Nullable T data, boolean force) {
    }

    @Override
    public void setHealthScale(double scale) {
    }

    @Override
    public void setScoreboard(@NotNull Scoreboard scoreboard) throws IllegalArgumentException, IllegalStateException {
        this.scoreboard = scoreboard;
    }

    @Override
    public @Nullable org.bukkit.event.player.PlayerResourcePackStatusEvent.Status getResourcePackStatus() {
        return null;
    }

    @Override
    public void setResourcePack(@NotNull UUID uuid, @NotNull String url, byte @Nullable [] hash, @Nullable Component prompt, boolean force) {
    }

    @Override
    public void setWalkSpeed(float value) throws IllegalArgumentException {
        var ab = abilities();
        if (ab != null) setField(ab, "walkingSpeed", value);
    }

    @Override
    public void setFlySpeed(float value) throws IllegalArgumentException {
        var ab = abilities();
        if (ab != null) setField(ab, "flySpeed", value);
    }

    @Override
    public boolean listPlayer(@NotNull Player other) {
        return false;
    }

    @Override
    public boolean unlistPlayer(@NotNull Player other) {
        return false;
    }

    @Override
    public boolean isListed(@NotNull Player other) {
        return false;
    }

    private Entity adaptEntity(Object nmsEntity) {
        if (nmsEntity == null) return null;
        return org.veltismc.veltis.inventory.VeltisEntityProxy.adapt(nmsEntity, server);
    }

    @Override
    public @Nullable AttributeInstance getAttribute(@NotNull Attribute attribute) {
        if (mcPlayer == null) return null;
        try {
            var attributes = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            var attrField = attributes.getField("ATTRIBUTE");
            var attr = attrField.get(null);
            var key = org.bukkit.NamespacedKey.fromString(attribute.getKey().asString());
            if (key == null) return null;
            try {
                var getMethod = attr.getClass().getMethod("get", Class.forName("net.minecraft.resources.ResourceLocation"));
                var nmsAttr = getMethod.invoke(attr, Class.forName("net.minecraft.resources.ResourceLocation")
                    .getConstructor(String.class, String.class).newInstance(key.getNamespace(), key.getKey()));
                if (nmsAttr == null) return null;
                var instance = mcPlayer.getClass().getMethod("getAttribute", nmsAttr.getClass()).invoke(mcPlayer, nmsAttr);
                if (instance == null) return null;
                return (AttributeInstance) instance;
            } catch (Exception e2) {
                return null;
            }
        } catch (Exception e) { return null; }
    }
}
