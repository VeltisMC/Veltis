package org.veltismc.veltis;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VeltisPersistentDataContainer implements PersistentDataContainer {

    private final ConcurrentHashMap<NamespacedKey, byte[]> data = new ConcurrentHashMap<>();
    private final VeltisContext context = new VeltisContext();

    @Override
    public <P, C> void set(@NotNull NamespacedKey key, @NotNull PersistentDataType<P, C> type, @NotNull C value) {
        P primitive = type.toPrimitive(value, context);
        data.put(key, serializePrimitive(primitive));
    }

    @Override
    public <P, C> boolean has(@NotNull NamespacedKey key, @NotNull PersistentDataType<P, C> type) {
        return data.containsKey(key);
    }

    @Override
    public boolean has(@NotNull NamespacedKey key) {
        return data.containsKey(key);
    }

    @Override
    public <P, C> @Nullable C get(@NotNull NamespacedKey key, @NotNull PersistentDataType<P, C> type) {
        byte[] raw = data.get(key);
        if (raw == null) return null;
        try {
            P primitive = deserializePrimitive(raw, type.getPrimitiveType());
            return type.fromPrimitive(primitive, context);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public <P, C> @NotNull C getOrDefault(@NotNull NamespacedKey key, @NotNull PersistentDataType<P, C> type, @NotNull C defaultValue) {
        C val = get(key, type);
        return val != null ? val : defaultValue;
    }

    @Override
    @NotNull
    public Set<NamespacedKey> getKeys() {
        return data.keySet();
    }

    @Override
    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public void copyTo(@NotNull PersistentDataContainer other, boolean replace) {
        for (var entry : data.entrySet()) {
            if (replace || !other.has(entry.getKey())) {
                other.set(entry.getKey(), PersistentDataType.BYTE_ARRAY, entry.getValue());
            }
        }
    }

    @Override
    @NotNull
    public PersistentDataAdapterContext getAdapterContext() {
        return context;
    }

    @Override
    public byte @NotNull [] serializeToBytes() throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        var oos = new java.io.ObjectOutputStream(output);
        oos.writeInt(data.size());
        for (var entry : data.entrySet()) {
            oos.writeUTF(entry.getKey().getNamespace());
            oos.writeUTF(entry.getKey().getKey());
            oos.writeInt(entry.getValue().length);
            oos.write(entry.getValue());
        }
        oos.close();
        return output.toByteArray();
    }

    @Override
    public void readFromBytes(byte @NotNull [] bytes, boolean clear) throws IOException {
        if (clear) data.clear();
        var input = new java.io.ByteArrayInputStream(bytes);
        var ois = new java.io.ObjectInputStream(input);
        int size = ois.readInt();
        for (int i = 0; i < size; i++) {
            var namespace = ois.readUTF();
            var key = ois.readUTF();
            int len = ois.readInt();
            var value = new byte[len];
            ois.readFully(value);
            data.put(NamespacedKey.fromString(namespace + ":" + key), value);
        }
        ois.close();
    }

    @Override
    public int getSize() {
        return data.size();
    }

    @Override
    public void remove(@NotNull NamespacedKey key) {
        data.remove(key);
    }

    @SuppressWarnings("unchecked")
    private static <P> P deserializePrimitive(byte[] raw, Class<P> primitiveType) {
        if (primitiveType == byte[].class) {
            return (P) raw;
        }
        if (primitiveType == String.class) {
            return (P) new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (primitiveType == Integer.class || primitiveType == int.class) {
            return (P) Integer.valueOf(java.nio.ByteBuffer.wrap(raw).getInt());
        }
        if (primitiveType == Long.class || primitiveType == long.class) {
            return (P) Long.valueOf(java.nio.ByteBuffer.wrap(raw).getLong());
        }
        if (primitiveType == Double.class || primitiveType == double.class) {
            return (P) Double.valueOf(java.nio.ByteBuffer.wrap(raw).getDouble());
        }
        if (primitiveType == Float.class || primitiveType == float.class) {
            return (P) Float.valueOf(java.nio.ByteBuffer.wrap(raw).getFloat());
        }
        if (primitiveType == Short.class || primitiveType == short.class) {
            return (P) Short.valueOf(java.nio.ByteBuffer.wrap(raw).getShort());
        }
        if (primitiveType == Byte.class || primitiveType == byte.class) {
            return (P) Byte.valueOf(raw[0]);
        }
        if (primitiveType == Boolean.class || primitiveType == boolean.class) {
            return (P) Boolean.valueOf(raw[0] != 0);
        }
        if (primitiveType == int[].class) {
            var buf = java.nio.ByteBuffer.wrap(raw);
            int len = buf.getInt();
            int[] arr = new int[len];
            for (int i = 0; i < len; i++) arr[i] = buf.getInt();
            return (P) arr;
        }
        if (primitiveType == long[].class) {
            var buf = java.nio.ByteBuffer.wrap(raw);
            int len = buf.getInt();
            long[] arr = new long[len];
            for (int i = 0; i < len; i++) arr[i] = buf.getLong();
            return (P) arr;
        }
        if (primitiveType == PersistentDataContainer.class) {
            var pdc = new VeltisPersistentDataContainer();
            try { pdc.readFromBytes(raw, false); } catch (IOException ignored) {}
            return (P) pdc;
        }
        if (primitiveType == PersistentDataContainer[].class) {
            try {
                var input = new java.io.ByteArrayInputStream(raw);
                var ois = new java.io.ObjectInputStream(input);
                int len = ois.readInt();
                var arr = new PersistentDataContainer[len];
                for (int i = 0; i < len; i++) {
                    var pdc = new VeltisPersistentDataContainer();
                    pdc.readFromBytes(ois.readAllBytes(), false);
                    arr[i] = pdc;
                }
                ois.close();
                return (P) arr;
            } catch (Exception e) {
                return (P) new PersistentDataContainer[0];
            }
        }
        return (P) raw;
    }

    private static byte[] serializePrimitive(Object primitive) {
        if (primitive instanceof byte[] bytes) return bytes;
        if (primitive instanceof String s) return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (primitive instanceof Integer i) { var buf = java.nio.ByteBuffer.allocate(4); buf.putInt(i); return buf.array(); }
        if (primitive instanceof Long l) { var buf = java.nio.ByteBuffer.allocate(8); buf.putLong(l); return buf.array(); }
        if (primitive instanceof Double d) { var buf = java.nio.ByteBuffer.allocate(8); buf.putDouble(d); return buf.array(); }
        if (primitive instanceof Float f) { var buf = java.nio.ByteBuffer.allocate(4); buf.putFloat(f); return buf.array(); }
        if (primitive instanceof Short s) { var buf = java.nio.ByteBuffer.allocate(2); buf.putShort(s); return buf.array(); }
        if (primitive instanceof Byte b) return new byte[]{b};
        if (primitive instanceof Boolean b) return new byte[]{(byte) (b ? 1 : 0)};
        if (primitive instanceof int[] arr) {
            var buf = java.nio.ByteBuffer.allocate(4 + arr.length * 4);
            buf.putInt(arr.length);
            for (int v : arr) buf.putInt(v);
            return buf.array();
        }
        if (primitive instanceof long[] arr) {
            var buf = java.nio.ByteBuffer.allocate(4 + arr.length * 8);
            buf.putInt(arr.length);
            for (long v : arr) buf.putLong(v);
            return buf.array();
        }
        if (primitive instanceof PersistentDataContainer pdc) {
            try { return pdc.serializeToBytes(); } catch (IOException e) { return new byte[0]; }
        }
        if (primitive instanceof PersistentDataContainer[] arr) {
            try {
                var output = new java.io.ByteArrayOutputStream();
                var oos = new java.io.ObjectOutputStream(output);
                oos.writeInt(arr.length);
                for (var pdc : arr) {
                    var bytes = pdc.serializeToBytes();
                    oos.writeInt(bytes.length);
                    oos.write(bytes);
                }
                oos.close();
                return output.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }
        return new byte[0];
    }

    private static class VeltisContext implements PersistentDataAdapterContext {
        @Override
        public @NotNull PersistentDataContainer newPersistentDataContainer() {
            return new VeltisPersistentDataContainer();
        }
    }
}
