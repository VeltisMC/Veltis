package org.veltismc.veltis.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A node in the configuration tree.
 *
 * <p>Each node holds a value which can be a scalar (String, Number,
 * Boolean), a list of child nodes, or a map of child nodes.
 * Thread-safe by design — all reads are on immutable data.
 *
 * <p>Create via static factory methods:
 * <pre>{@code
 * var root = ConfigurationNode.map()
 *     .with("name", ConfigurationNode.of("VeltisMC"))
 *     .with("port", ConfigurationNode.of(25565));
 * }</pre>
 */
public final class ConfigurationNode {

    private final Object value;
    private final Type type;

    private ConfigurationNode(Object value, Type type) {
        this.value = value;
        this.type = type;
    }

    // -- Factory methods --

    /**
     * Creates a null node.
     */
    public static ConfigurationNode nil() {
        return new ConfigurationNode(null, Type.NULL);
    }

    /**
     * Creates a scalar string node.
     */
    public static ConfigurationNode of(String value) {
        return new ConfigurationNode(value, Type.STRING);
    }

    /**
     * Creates a scalar numeric node.
     */
    public static ConfigurationNode of(Number value) {
        return new ConfigurationNode(value, Type.NUMBER);
    }

    /**
     * Creates a scalar boolean node.
     */
    public static ConfigurationNode of(boolean value) {
        return new ConfigurationNode(value, Type.BOOLEAN);
    }

    /**
     * Creates an empty map node.
     */
    public static ConfigurationNode map() {
        return new ConfigurationNode(new LinkedHashMap<String, ConfigurationNode>(), Type.MAP);
    }

    /**
     * Creates a map node from key-value pairs.
     */
    @SuppressWarnings("unchecked")
    public static ConfigurationNode map(Object... pairs) {
        var map = new LinkedHashMap<String, ConfigurationNode>();
        for (int i = 0; i < pairs.length; i += 2) {
            var key = (String) pairs[i];
            var val = pairs[i + 1] instanceof ConfigurationNode n ? n : ConfigurationNode.wrap(pairs[i + 1]);
            map.put(key, val);
        }
        return new ConfigurationNode(map, Type.MAP);
    }

    /**
     * Creates an empty list node.
     */
    public static ConfigurationNode list() {
        return new ConfigurationNode(new ArrayList<ConfigurationNode>(), Type.LIST);
    }

    /**
     * Creates a list node from values.
     */
    public static ConfigurationNode list(Object... values) {
        var list = new ArrayList<ConfigurationNode>();
        for (var v : values) {
            list.add(v instanceof ConfigurationNode n ? n : wrap(v));
        }
        return new ConfigurationNode(list, Type.LIST);
    }

    /**
     * Wraps an arbitrary Java object as a ConfigurationNode.
     */
    public static ConfigurationNode wrap(Object obj) {
        if (obj == null) return nil();
        if (obj instanceof ConfigurationNode n) return n;
        if (obj instanceof String s) return of(s);
        if (obj instanceof Number n) return of(n);
        if (obj instanceof Boolean b) return of(b);
        if (obj instanceof Map<?, ?> m) {
            var node = map();
            for (var e : m.entrySet()) {
                node = node.with(String.valueOf(e.getKey()), wrap(e.getValue()));
            }
            return node;
        }
        if (obj instanceof List<?> l) {
            var nodes = l.stream().map(ConfigurationNode::wrap).toArray(ConfigurationNode[]::new);
            return list(nodes);
        }
        return of(String.valueOf(obj));
    }

    // -- Type --

    /**
     * The type of value held by this node.
     */
    public enum Type { NULL, STRING, NUMBER, BOOLEAN, MAP, LIST }

    /**
     * Returns the node type.
     */
    public Type type() { return type; }

    /**
     * Returns true if this node is null.
     */
    public boolean isNull() { return type == Type.NULL; }

    /**
     * Returns true if this node is a map.
     */
    public boolean isMap() { return type == Type.MAP; }

    /**
     * Returns true if this node is a list.
     */
    public boolean isList() { return type == Type.LIST; }

    // -- Scalar access --

    /**
     * Returns the string value.
     */
    public String asString() { return type == Type.STRING ? (String) value : toString(); }

    /**
     * Returns the integer value.
     */
    public int asInt() { return asLong().intValue(); }

    /**
     * Returns the long value.
     */
    public Long asLong() {
        return switch (type) {
            case NUMBER -> ((Number) value).longValue();
            case STRING -> Long.parseLong((String) value);
            default -> 0L;
        };
    }

    /**
     * Returns the double value.
     */
    public double asDouble() {
        return switch (type) {
            case NUMBER -> ((Number) value).doubleValue();
            case STRING -> Double.parseDouble((String) value);
            default -> 0.0;
        };
    }

    /**
     * Returns the boolean value.
     */
    public boolean asBoolean() {
        return switch (type) {
            case BOOLEAN -> (Boolean) value;
            case STRING -> Boolean.parseBoolean((String) value);
            default -> false;
        };
    }

    // -- Map access --

    /**
     * Gets a child by key.
     */
    public Optional<ConfigurationNode> get(String key) {
        if (type != Type.MAP) return Optional.empty();
        return Optional.ofNullable(((Map<String, ConfigurationNode>) value).get(key));
    }

    /**
     * Gets a child by key with a default fallback.
     */
    public ConfigurationNode get(String key, ConfigurationNode fallback) {
        return get(key).orElse(fallback);
    }

    /**
     * Gets a scalar string by key.
     */
    public String getString(String key, String fallback) {
        return get(key).map(ConfigurationNode::asString).orElse(fallback);
    }

    /**
     * Gets a scalar integer by key.
     */
    public int getInt(String key, int fallback) {
        return get(key).map(ConfigurationNode::asInt).orElse(fallback);
    }

    /**
     * Gets a scalar boolean by key.
     */
    public boolean getBoolean(String key, boolean fallback) {
        return get(key).map(ConfigurationNode::asBoolean).orElse(fallback);
    }

    /**
     * Gets a scalar double by key.
     */
    public double getDouble(String key, double fallback) {
        return get(key).map(ConfigurationNode::asDouble).orElse(fallback);
    }

    /**
     * Returns all keys in a map node.
     */
    public java.util.Set<String> keys() {
        if (type != Type.MAP) return java.util.Set.of();
        return ((Map<String, ConfigurationNode>) value).keySet();
    }

    /**
     * Returns a new node with the given key set.
     */
    @SuppressWarnings("unchecked")
    public ConfigurationNode with(String key, ConfigurationNode child) {
        if (type != Type.MAP) {
            var map = new LinkedHashMap<String, ConfigurationNode>();
            map.put(key, child);
            return new ConfigurationNode(map, Type.MAP);
        }
        var copy = new LinkedHashMap<>((Map<String, ConfigurationNode>) value);
        copy.put(key, child);
        return new ConfigurationNode(copy, Type.MAP);
    }

    /**
     * Sets a scalar string value on a map node.
     */
    public ConfigurationNode with(String key, String value) {
        return with(key, of(value));
    }

    /**
     * Sets a numeric value on a map node.
     */
    public ConfigurationNode with(String key, Number value) {
        return with(key, of(value));
    }

    /**
     * Sets a boolean value on a map node.
     */
    public ConfigurationNode with(String key, boolean value) {
        return with(key, of(value));
    }

    // -- List access --

    /**
     * Returns the list children.
     */
    @SuppressWarnings("unchecked")
    public List<ConfigurationNode> asList() {
        if (type != Type.LIST) return List.of();
        return List.copyOf((List<ConfigurationNode>) value);
    }

    /**
     * Returns the size of a list or map.
     */
    public int size() {
        return switch (type) {
            case MAP -> ((Map<?, ?>) value).size();
            case LIST -> ((List<?>) value).size();
            default -> 0;
        };
    }

    /**
     * Converts to a plain Java object (Map, List, scalar, or null).
     */
    public Object unwrap() {
        return switch (type) {
            case NULL -> null;
            case STRING, NUMBER, BOOLEAN -> value;
            case MAP -> {
                var map = new LinkedHashMap<String, Object>();
                for (var key : keys()) {
                    map.put(key, get(key).map(ConfigurationNode::unwrap).orElse(null));
                }
                yield map;
            }
            case LIST -> asList().stream().map(ConfigurationNode::unwrap).toList();
        };
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : "null";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfigurationNode that)) return false;
        return type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }
}



