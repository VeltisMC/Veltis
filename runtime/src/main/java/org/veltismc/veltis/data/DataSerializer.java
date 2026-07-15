package org.veltismc.veltis.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes and deserializes {@link DataContainer} trees.
 *
 * <p>Supports JSON format for persistent storage. Binary format
 * support is available for future use.
 */
public final class DataSerializer {

    private DataSerializer() {
    }

    /**
     * Serializes a container to a JSON byte array.
     */
    public static byte[] toJson(DataContainer container) {
        var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(toJsonElement(container)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserializes a container from a JSON byte array.
     */
    public static DataContainer fromJson(byte[] data) {
        var gson = new com.google.gson.Gson();
        var content = new String(data, StandardCharsets.UTF_8);
        var element = gson.fromJson(content, com.google.gson.JsonElement.class);
        return fromJsonElement(element);
    }

    /**
     * Loads a container from a JSON file.
     */
    public static DataContainer loadJson(Path path) throws IOException {
        var data = Files.readAllBytes(path);
        return fromJson(data);
    }

    /**
     * Saves a container to a JSON file.
     */
    public static void saveJson(Path path, DataContainer container) throws IOException {
        var data = toJson(container);
        Files.write(path, data);
    }

    // -- JSON element conversion --

    private static com.google.gson.JsonElement toJsonElement(DataContainer container) {
        return switch (container.type()) {
            case NULL -> com.google.gson.JsonNull.INSTANCE;
            case STRING -> new com.google.gson.JsonPrimitive(container.asString());
            case NUMBER -> new com.google.gson.JsonPrimitive(container.asDouble());
            case BOOLEAN -> new com.google.gson.JsonPrimitive(container.asBoolean());
            case LIST -> {
                var arr = new com.google.gson.JsonArray();
                for (var child : container.asList()) {
                    arr.add(toJsonElement(child));
                }
                yield arr;
            }
            case MAP -> {
                var obj = new com.google.gson.JsonObject();
                for (var key : container.keys()) {
                    container.get(key).ifPresent(v -> obj.add(key, toJsonElement(v)));
                }
                yield obj;
            }
        };
    }

    private static DataContainer fromJsonElement(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return DataContainer.nil();
        if (el.isJsonPrimitive()) {
            var prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return DataContainer.of(prim.getAsBoolean());
            if (prim.isNumber()) return DataContainer.of(prim.getAsDouble());
            return DataContainer.of(prim.getAsString());
        }
        if (el.isJsonArray()) {
            var result = DataContainer.list();
            for (var item : el.getAsJsonArray()) {
                result = result.add(fromJsonElement(item));
            }
            return result;
        }
        if (el.isJsonObject()) {
            var result = DataContainer.map();
            for (var entry : el.getAsJsonObject().entrySet()) {
                result = result.set(entry.getKey(), fromJsonElement(entry.getValue()));
            }
            return result;
        }
        return DataContainer.nil();
    }
}


