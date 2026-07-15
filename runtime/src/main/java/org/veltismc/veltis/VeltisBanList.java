package org.veltismc.veltis;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class VeltisBanList implements BanList<Object> {

    private final Object mcBanList;
    private final Type type;
    private final Method getEntriesMethod;
    private final Method addEntryMethod;
    private final Method removeMethod;
    private final Method containsMethod;
    private final Method getMethod;

    VeltisBanList(Object mcBanList, Type type) {
        this.mcBanList = mcBanList;
        this.type = type;
        var clazz = mcBanList.getClass();
        try {
            this.getEntriesMethod = clazz.getMethod("getEntries");
            this.removeMethod = clazz.getMethod("remove", Object.class);
            this.containsMethod = findContainsMethod(clazz);
            this.getMethod = clazz.getMethod("get", Object.class);
            this.addEntryMethod = findAddMethod(clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ban list methods", e);
        }
    }

    private static Method findContainsMethod(Class<?> clazz) throws NoSuchMethodException {
        try {
            return clazz.getMethod("contains", Object.class);
        } catch (NoSuchMethodException e) {
            // Walk up superclass hierarchy for non-public methods
            Class<?> current = clazz;
            while (current != null) {
                try {
                    var method = current.getDeclaredMethod("contains", Object.class);
                    if (method.getReturnType() == boolean.class) {
                        method.setAccessible(true);
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {}
                current = current.getSuperclass();
            }
            throw e;
        }
    }

    private static Method findAddMethod(Class<?> clazz) throws NoSuchMethodException {
        for (var m : clazz.getMethods()) {
            if ("add".equals(m.getName()) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new NoSuchMethodException("add with 1 parameter not found in " + clazz);
    }

    @Override
    @Deprecated
    @SuppressWarnings("unchecked")
    public @Nullable <E extends BanEntry<? super Object>> E getBanEntry(@NotNull String target) {
        var entry = findEntry(target);
        return entry != null ? (E) entry : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable BanEntry<Object> getBanEntry(@NotNull Object target) {
        if (type == Type.IP && target instanceof InetAddress addr) {
            return getBanEntry(addr.getHostAddress());
        }
        if (type == Type.PROFILE && target instanceof com.destroystokyo.paper.profile.PlayerProfile prof) {
            return getBanEntry(prof.getName());
        }
        return null;
    }

    @Override
    @Deprecated
    @SuppressWarnings("unchecked")
    public @Nullable <E extends BanEntry<? super Object>> E addBan(@NotNull String target, @Nullable String reason, @Nullable Date expires, @Nullable String source) {
        var entry = createEntry(target, reason, expires, source);
        if (entry != null) {
            try {
                addEntryMethod.invoke(mcBanList, entry);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return (E) entry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable BanEntry<Object> addBan(@NotNull Object target, @Nullable String reason, @Nullable Date expires, @Nullable String source) {
        if (type == Type.IP && target instanceof InetAddress addr) {
            return addBan(addr.getHostAddress(), reason, expires, source);
        }
        if (type == Type.PROFILE && target instanceof com.destroystokyo.paper.profile.PlayerProfile prof) {
            return addBan(prof.getName(), reason, expires, source);
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable BanEntry<Object> addBan(@NotNull Object target, @Nullable String reason, @Nullable Instant expires, @Nullable String source) {
        return addBan(target, reason, expires != null ? Date.from(expires) : null, source);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable BanEntry<Object> addBan(@NotNull Object target, @Nullable String reason, @Nullable Duration duration, @Nullable String source) {
        return addBan(target, reason, duration != null ? Date.from(Instant.now().plus(duration)) : null, source);
    }

    @Override
    @Deprecated
    @SuppressWarnings("unchecked")
    public @NotNull Set<BanEntry> getBanEntries() {
        return (Set) getEntries();
    }

    @Override
    @SuppressWarnings("unchecked")
    public @NotNull <E extends BanEntry<? super Object>> Set<E> getEntries() {
        var result = new HashSet<E>();
        try {
            var mcEntries = (Iterable<?>) getEntriesMethod.invoke(mcBanList);
            for (var mcEntry : mcEntries) {
                var bukkitEntry = wrapEntry(mcEntry);
                if (bukkitEntry != null) {
                    result.add((E) bukkitEntry);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public boolean isBanned(@NotNull Object target) {
        if (type == Type.IP && target instanceof InetAddress addr) {
            return isBanned(addr.getHostAddress());
        }
        if (type == Type.PROFILE && target instanceof com.destroystokyo.paper.profile.PlayerProfile prof) {
            return isBanned(prof.getName());
        }
        return false;
    }

    @Override
    @Deprecated
    public boolean isBanned(@NotNull String target) {
        try {
            return (boolean) containsMethod.invoke(mcBanList, lookupKey(target));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void pardon(@NotNull Object target) {
        if (type == Type.IP && target instanceof InetAddress addr) {
            pardon(addr.getHostAddress());
        }
        if (type == Type.PROFILE && target instanceof com.destroystokyo.paper.profile.PlayerProfile prof) {
            pardon(prof.getName());
        }
    }

    @Override
    @Deprecated
    public void pardon(@NotNull String target) {
        try {
            removeMethod.invoke(mcBanList, lookupKey(target));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Object lookupKey(String target) {
        if (type == Type.IP) {
            return target;
        }
        if (type == Type.PROFILE || type == Type.NAME) {
            try {
                var nameAndIdClass = Class.forName("net.minecraft.server.players.NameAndId");
                return nameAndIdClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes()), target);
            } catch (Exception e) {
                return target;
            }
        }
        return target;
    }

    @Nullable
    private BanEntry<Object> findEntry(String target) {
        try {
            var mcEntry = getMethod.invoke(mcBanList, lookupKey(target));
            return mcEntry != null ? wrapEntry(mcEntry) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private BanEntry<Object> wrapEntry(Object mcEntry) {
        if (mcEntry == null) return null;
        try {
            var getUserMethod = mcEntry.getClass().getMethod("getUser");
            var getUser = getUserMethod.invoke(mcEntry);
            var userStr = getUser != null ? getUser.toString() : "unknown";

            var getCreatedMethod = mcEntry.getClass().getMethod("getCreated");
            var getSourceMethod = mcEntry.getClass().getMethod("getSource");
            var getExpiresMethod = mcEntry.getClass().getMethod("getExpires");
            var getReasonMethod = mcEntry.getClass().getMethod("getReason");

            var created = (Date) getCreatedMethod.invoke(mcEntry);
            var source = (String) getSourceMethod.invoke(mcEntry);
            var expires = (Date) getExpiresMethod.invoke(mcEntry);
            var reason = (String) getReasonMethod.invoke(mcEntry);

            return new VeltisBanEntry<>(userStr, created, source, expires, reason, mcEntry, this);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private Object createEntry(String target, @Nullable String reason, @Nullable Date expires, @Nullable String source) {
        try {
            if (type == Type.IP) {
                var clazz = Class.forName("net.minecraft.server.players.IpBanListEntry");
                return clazz.getConstructor(String.class, Date.class, String.class, Date.class, String.class)
                    .newInstance(target, new Date(), source != null ? source : "Unknown", expires, reason != null ? reason : "Banned by an operator.");
            }
            if (type == Type.PROFILE || type == Type.NAME) {
                var clazz = Class.forName("net.minecraft.server.players.UserBanListEntry");
                var nameAndIdClass = Class.forName("net.minecraft.server.players.NameAndId");
                var nameAndId = nameAndIdClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes()), target);
                return clazz.getConstructor(nameAndIdClass, Date.class, String.class, Date.class, String.class)
                    .newInstance(nameAndId, new Date(), source != null ? source : "Unknown", expires, reason != null ? reason : "Banned by an operator.");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    void save() {
        try {
            var saveMethod = mcBanList.getClass().getMethod("save");
            saveMethod.invoke(mcBanList);
        } catch (Exception ignored) {
        }
    }

    private static final class VeltisBanEntry<T> implements BanEntry<T> {
        private final T target;
        private Date created;
        private String source;
        private Date expires;
        private String reason;
        private final Object mcEntry;
        private final VeltisBanList banList;

        VeltisBanEntry(T target, Date created, String source, Date expires, String reason, Object mcEntry, VeltisBanList banList) {
            this.target = target;
            this.created = created;
            this.source = source;
            this.expires = expires;
            this.reason = reason;
            this.mcEntry = mcEntry;
            this.banList = banList;
        }

        @Override
        @Deprecated
        public @NotNull String getTarget() {
            return Objects.toString(target);
        }

        @Override
        public @NotNull T getBanTarget() {
            return target;
        }

        @Override
        public @NotNull Date getCreated() {
            return created;
        }

        @Override
        public void setCreated(@NotNull Date created) {
            this.created = created;
        }

        @Override
        public @NotNull String getSource() {
            return source;
        }

        @Override
        public void setSource(@NotNull String source) {
            this.source = source;
        }

        @Override
        public @Nullable Date getExpiration() {
            return expires;
        }

        @Override
        public void setExpiration(@Nullable Date expiration) {
            this.expires = expiration;
        }

        @Override
        public @Nullable String getReason() {
            return reason;
        }

        @Override
        public void setReason(@Nullable String reason) {
            this.reason = reason;
        }

        @Override
        public void save() {
            banList.save();
        }

        @Override
        public void remove() {
            banList.pardon(target != null ? target.toString() : "unknown");
        }
    }
}
