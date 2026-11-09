package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Reads the build identity embedded into the server distribution at build time.
 *
 * <p>The file lives at {@value #RESOURCE}; the launcher packs it from the same
 * generated tree as the patch set, and it names the exact commit, the release
 * tag that commit published under, and the channel/version the tag was derived
 * from. A distribution built without that resource is <em>unpinned</em>: it
 * cannot claim a commit, so native commit lookup must fail open to Java.
 */
public final class NativeBuildMetadata {

    static final String RESOURCE = "META-INF/veltis/build-info.json";
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHORT_COMMIT = Pattern.compile("[0-9a-f]{7}");

    /**
     * What the running distribution knows about its own build.
     *
     * <p>{@link #UNPINNED} is the identity of a build with no usable metadata;
     * it is never equal to a real commit because every field is empty.
     */
    public record Identity(String fullCommit, String shortCommit, String channel,
            String minecraftVersion, String releaseTag) {

        static final Identity UNPINNED = new Identity("", "", "", "", "");

        public boolean pinned() {
            return FULL_COMMIT.matcher(fullCommit).matches()
                    && SHORT_COMMIT.matcher(shortCommit).matches()
                    && shortCommit.equals(fullCommit.substring(0, 7))
                    && releaseTag.equals("veltis-" + channel + "-" + minecraftVersion + "-" + shortCommit);
        }
    }

    private NativeBuildMetadata() {}

    /** The identity of the running distribution, or {@link Identity#UNPINNED}. */
    public static Identity identity() {
        return identity(NativeBuildMetadata.class.getClassLoader());
    }

    static Identity identity(ClassLoader loader) {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return Identity.UNPINNED;
            }
            var bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return Identity.UNPINNED;
            }
            var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Identity.UNPINNED;
            }
            var root = parsed.getAsJsonObject();
            if (!root.has("commit") || !root.get("commit").isJsonObject()) {
                return Identity.UNPINNED;
            }
            var commit = root.getAsJsonObject("commit");
            var identity = new Identity(
                    string(commit, "full"),
                    string(commit, "short"),
                    string(root, "channel"),
                    string(root, "minecraftVersion"),
                    string(root, "releaseTag"));
            return identity.pinned() ? identity : Identity.UNPINNED;
        } catch (IOException | RuntimeException e) {
            return Identity.UNPINNED;
        }
    }

    private static String string(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()
                || !object.getAsJsonPrimitive(field).isString()) {
            return "";
        }
        return object.get(field).getAsString();
    }
}
