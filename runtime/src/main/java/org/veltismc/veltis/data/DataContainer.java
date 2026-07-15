package org.veltismc.veltis.data;

import org.veltismc.veltis.config.ConfigurationNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A container holding structured data values.
 *
 * <p>Similar to {@link ConfigurationNode} but
 * designed for runtime data storage rather than configuration.
 * Supports nested maps and lists with type-safe access.
 */
public final class DataContainer {

    private final Object value;
    private final Type type;

    private DataContainer(Object value, Type type) {
        this.value = value;
        this.type = type;
    }

    /**
     * Value type enum.
     */
    public enum Type { NULL, STRING, NUMBER, BOOLEAN, MAP, LIST }

    /**
     * Creates a null container.
     */
    public static DataContainer nil() {
        return new DataContainer(null, Type.NULL);
    }

    /**
     * Wraps a string value.
     */
    public static DataContainer of(String value) {
        return new DataContainer(value, Type.STRING);
    }

    /**
     * Wraps a numeric value.
     */
    public static DataContainer of(Number value) {
        return new DataContainer(value, Type.NUMBER);
    }

    /**
     * Wraps a boolean value.
     */
    public static DataContainer of(boolean value) {
        return new DataContainer(value, Type.BOOLEAN);
    }

    /**
     * Creates an empty map container.
     */
    public static DataContainer map() {
        return new DataContainer(new LinkedHashMap<String, DataContainer>(), Type.MAP);
    }

    /**
     * Creates an empty list container.
     */
    public static DataContainer list() {
        return new DataContainer(new java.util.ArrayList<DataContainer>(), Type.LIST);
    }

    /**
     * Wraps an arbitrary Java object.
     */
    public static DataContainer wrap(Object obj) {
        if (obj == null) return nil();
        if (obj instanceof DataContainer d) return d;
        if (obj instanceof String s) return of(s);
        if (obj instanceof Number n) return of(n);
        if (obj instanceof Boolean b) return of(b);
        if (obj instanceof Map<?, ?> m) {
            var node = map();
            for (var e : m.entrySet()) {
                node = node.set(String.valueOf(e.getKey()), wrap(e.getValue()));
            }
            return node;
        }
        if (obj instanceof List<?> l) {
            var result = list();
            for (var item : l) {
                result = result.add(wrap(item));
            }
            return result;
        }
        return of(String.valueOf(obj));
    }

    /**
     * Returns the type.
     */
    public Type type() { return type; }

    /**
     * Returns the string value.
     */
    public String asString() { return type == Type.STRING ? (String) value : toString(); }

    /**
     * Returns the integer value.
     */
    public int asInt() { return ((Number) value).intValue(); }

    /**
     * Returns the double value.
     */
    public double asDouble() { return ((Number) value).doubleValue(); }

    /**
     * Returns the boolean value.
     */
    public boolean asBoolean() { return (Boolean) value; }

    // -- Map access --

    /**
     * Gets a child by key.
     */
    public Optional<DataContainer> get(String key) {
        if (type != Type.MAP) return Optional.empty();
        return Optional.ofNullable(((Map<String, DataContainer>) value).get(key));
    }

    /**
     * Returns all keys.
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> keys() {
        if (type != Type.MAP) return java.util.Set.of();
        return ((Map<String, DataContainer>) value).keySet();
    }

    /**
     * Sets a key on a map, returning a new container.
     */
    @SuppressWarnings("unchecked")
    public DataContainer set(String key, DataContainer child) {
        if (type != Type.MAP) {
            var map = new LinkedHashMap<String, DataContainer>();
            map.put(key, child);
            return new DataContainer(map, Type.MAP);
        }
        var copy = new LinkedHashMap<>((Map<String, DataContainer>) value);
        copy.put(key, child);
        return new DataContainer(copy, Type.MAP);
    }

    /**
     * Convenience for setting a string value.
     */
    public DataContainer set(String key, String value) {
        return set(key, of(value));
    }

    /**
     * Convenience for setting a numeric value.
     */
    public DataContainer set(String key, Number value) {
        return set(key, of(value));
    }

    /**
     * Convenience for setting a boolean value.
     */
    public DataContainer set(String key, boolean value) {
        return set(key, of(value));
    }

    // -- List access --

    /**
     * Returns list children.
     */
    @SuppressWarnings("unchecked")
    public List<DataContainer> asList() {
        if (type != Type.LIST) return List.of();
        return List.copyOf((List<DataContainer>) value);
    }

    /**
     * Adds a child to a list, returning a new container.
     */
    @SuppressWarnings("unchecked")
    public DataContainer add(DataContainer child) {
        var copy = new java.util.ArrayList<DataContainer>(
            type == Type.LIST ? (List<DataContainer>) value : List.of());
        copy.add(child);
        return new DataContainer(copy, Type.LIST);
    }

    /**
     * Returns the size.
     */
    public int size() {
        return switch (type) {
            case MAP -> ((Map<?, ?>) value).size();
            case LIST -> ((List<?>) value).size();
            default -> 0;
        };
    }

    /**
     * Converts to a plain Java object.
     */
    public Object unwrap() {
        return switch (type) {
            case NULL -> null;
            case STRING, NUMBER, BOOLEAN -> value;
            case MAP -> {
                var map = new LinkedHashMap<String, Object>();
                for (var key : keys()) {
                    get(key).ifPresent(v -> map.put(key, v.unwrap()));
                }
                yield map;
            }
            case LIST -> asList().stream().map(DataContainer::unwrap).toList();
        };
    }

    @Override
    public String toString() { return value != null ? value.toString() : "null"; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataContainer that)) return false;
        return type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() { return Objects.hash(type, value); }
}


