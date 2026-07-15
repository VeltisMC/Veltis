package org.veltismc.veltis.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Serializes and deserializes {@link ConfigurationNode} trees to/from
 * various text formats.
 *
 * <p>Supports YAML, JSON, and TOML formats. Each format is identified
 * by its file extension.
 */
public final class ConfigurationSerializer {

    private static final Logger LOG = Logger.getLogger(ConfigurationSerializer.class.getName());

    private ConfigurationSerializer() {
    }

    /**
     * Loads a configuration node from a file path with BOM-aware encoding detection.
     *
     * @param path   the file path
     * @param format the format name ("yml", "json", "toml")
     * @return the parsed configuration node
     * @throws IOException if reading or parsing fails
     */
    public static ConfigurationNode load(Path path, String format) throws IOException {
        var bytes = Files.readAllBytes(path);
        var detection = org.veltismc.veltis.util.CharsetDetector.detect(bytes);
        var charset = detection.charset();
        var content = new String(bytes, charset);

        switch (detection.bomType()) {
            case UTF_8, UTF_16BE, UTF_16LE, UTF_32BE, UTF_32LE -> {
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
            }
        }

        LOG.fine("[Config] Detected " + charset.name()
            + (detection.bomType() != org.veltismc.veltis.util.CharsetDetector.BomType.NONE ? " with BOM" : ""));
        return parse(content, format);
    }

    /**
     * Saves a configuration node to a file.
     *
     * @param path   the file path
     * @param format the format name ("yml", "json", "toml")
     * @param node   the node to serialize
     * @throws IOException if writing fails
     */
    public static void save(Path path, String format, ConfigurationNode node) throws IOException {
        var content = serialize(node, format);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Detects the format from a file name.
     *
     * @param fileName the file name (e.g. "server.yml")
     * @return the format name
     * @throws IllegalArgumentException if the format is not recognized
     */
    public static String detectFormat(String fileName) {
        var lower = fileName.toLowerCase();
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yml";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".toml")) return "toml";
        throw new IllegalArgumentException("Unsupported config format: " + fileName);
    }

    // -- Parse --

    /**
     * Parses a string into a configuration node.
     */
    public static ConfigurationNode parse(String content, String format) {
        return switch (format) {
            case "yml", "yaml" -> parseYaml(content);
            case "json" -> parseJson(content);
            case "toml" -> parseToml(content);
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }

    /**
     * Serializes a configuration node to a string.
     */
    public static String serialize(ConfigurationNode node, String format) {
        return switch (format) {
            case "yml", "yaml" -> toYaml(node);
            case "json" -> toJson(node);
            case "toml" -> toToml(node);
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }

    // -- YAML --

    private static ConfigurationNode parseYaml(String content) {
        var yaml = new org.yaml.snakeyaml.Yaml();
        var raw = yaml.load(content);
        return ConfigurationNode.wrap(raw != null ? raw : new LinkedHashMap<>());
    }

    private static String toYaml(ConfigurationNode node) {
        var yaml = new org.yaml.snakeyaml.Yaml();
        var options = new org.yaml.snakeyaml.DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        var yamlPretty = new org.yaml.snakeyaml.Yaml(options);
        return yamlPretty.dump(node.unwrap());
    }

    // -- JSON --

    private static ConfigurationNode parseJson(String content) {
        var gson = new com.google.gson.Gson();
        var element = gson.fromJson(content, com.google.gson.JsonElement.class);
        return fromJsonElement(element);
    }

    private static ConfigurationNode fromJsonElement(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return ConfigurationNode.nil();
        if (el.isJsonPrimitive()) {
            var prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return ConfigurationNode.of(prim.getAsBoolean());
            if (prim.isNumber()) return ConfigurationNode.of(prim.getAsDouble());
            return ConfigurationNode.of(prim.getAsString());
        }
        if (el.isJsonArray()) {
            var nodes = el.getAsJsonArray().asList().stream()
                .map(ConfigurationSerializer::fromJsonElement)
                .toArray(ConfigurationNode[]::new);
            return ConfigurationNode.list(nodes);
        }
        if (el.isJsonObject()) {
            var obj = el.getAsJsonObject();
            var node = ConfigurationNode.map();
            for (var entry : obj.entrySet()) {
                node = node.with(entry.getKey(), fromJsonElement(entry.getValue()));
            }
            return node;
        }
        return ConfigurationNode.nil();
    }

    private static String toJson(ConfigurationNode node) {
        var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(toJsonElement(node));
    }

    private static com.google.gson.JsonElement toJsonElement(ConfigurationNode node) {
        return switch (node.type()) {
            case NULL -> com.google.gson.JsonNull.INSTANCE;
            case STRING -> new com.google.gson.JsonPrimitive(node.asString());
            case NUMBER -> new com.google.gson.JsonPrimitive(node.asDouble());
            case BOOLEAN -> new com.google.gson.JsonPrimitive(node.asBoolean());
            case LIST -> {
                var arr = new com.google.gson.JsonArray();
                for (var child : node.asList()) {
                    arr.add(toJsonElement(child));
                }
                yield arr;
            }
            case MAP -> {
                var obj = new com.google.gson.JsonObject();
                for (var key : node.keys()) {
                    node.get(key).ifPresent(child -> obj.add(key, toJsonElement(child)));
                }
                yield obj;
            }
        };
    }

    // -- TOML --

    private static ConfigurationNode parseToml(String content) {
        var toml = new com.moandjiezana.toml.Toml();
        var raw = toml.read(content);
        return tomlToNode(raw);
    }

    @SuppressWarnings("unchecked")
    private static ConfigurationNode tomlToNode(com.moandjiezana.toml.Toml toml) {
        var map = toml.toMap();
        if (map.isEmpty()) return ConfigurationNode.map();

        var node = ConfigurationNode.map();
        for (var entry : map.entrySet()) {
            node = node.with(entry.getKey(), tomlValueToNode(entry.getValue()));
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private static ConfigurationNode tomlValueToNode(Object value) {
        if (value == null) return ConfigurationNode.nil();
        if (value instanceof String s) return ConfigurationNode.of(s);
        if (value instanceof Number n) return ConfigurationNode.of(n);
        if (value instanceof Boolean b) return ConfigurationNode.of(b);
        if (value instanceof List<?> l) {
            var nodes = l.stream()
                .map(ConfigurationSerializer::tomlValueToNode)
                .toArray(ConfigurationNode[]::new);
            return ConfigurationNode.list(nodes);
        }
        if (value instanceof Map<?, ?> m) {
            var node = ConfigurationNode.map();
            for (var entry : m.entrySet()) {
                node = node.with(String.valueOf(entry.getKey()), tomlValueToNode(entry.getValue()));
            }
            return node;
        }
        return ConfigurationNode.of(String.valueOf(value));
    }

    private static String toToml(ConfigurationNode node) {
        var sb = new StringBuilder();
        toTomlAppend(sb, node, "");
        return sb.toString();
    }

    private static void toTomlAppend(StringBuilder sb, ConfigurationNode node, String prefix) {
        if (node.type() == ConfigurationNode.Type.MAP) {
            for (var key : node.keys()) {
                var child = node.get(key).orElse(ConfigurationNode.nil());
                var fullKey = prefix.isEmpty() ? key : prefix + "." + key;
                switch (child.type()) {
                    case MAP -> {
                        sb.append("[").append(fullKey).append("]\n");
                        toTomlAppend(sb, child, fullKey);
                    }
                    case LIST -> {
                        for (var item : child.asList()) {
                            if (item.type() == ConfigurationNode.Type.MAP) {
                                sb.append("[[").append(fullKey).append("]]\n");
                                toTomlAppend(sb, item, fullKey);
                            } else {
                                sb.append(key).append(" = ").append(tomlValue(item)).append("\n");
                            }
                        }
                    }
                    default -> sb.append(key).append(" = ").append(tomlValue(child)).append("\n");
                }
            }
        }
    }

    private static String tomlValue(ConfigurationNode node) {
        return switch (node.type()) {
            case STRING -> "\"" + node.asString().replace("\"", "\\\"") + "\"";
            case NUMBER -> node.asDouble() == node.asInt() ? String.valueOf(node.asInt()) : String.valueOf(node.asDouble());
            case BOOLEAN -> String.valueOf(node.asBoolean());
            case NULL -> "";
            default -> String.valueOf(node.unwrap());
        };
    }

    // -- Convenience --

    /**
     * Loads a configuration file from disk.
     */
    public static ConfigurationFile loadFile(String name, Path path) throws IOException {
        var format = detectFormat(path.getFileName().toString());
        var root = load(path, format);
        return new ConfigurationFile(name, path, format, root, System.currentTimeMillis());
    }

    /**
     * Saves a configuration file to disk.
     */
    public static void saveFile(ConfigurationFile file) throws IOException {
        save(file.path(), file.format(), file.root());
    }
}


