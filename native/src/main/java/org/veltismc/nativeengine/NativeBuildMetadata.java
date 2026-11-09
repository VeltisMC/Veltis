package org.veltismc.nativeengine;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Reads the exact source commit embedded into the server distribution at build time. */
public final class NativeBuildMetadata {

    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final System.Logger LOG = System.getLogger(NativeBuildMetadata.class.getName());

    private NativeBuildMetadata() {}

    /**
     * Returns an empty string when the running distribution has no trustworthy
     * build metadata; native commit lookup must then fail open to Java.
     */
    public static String commit() {
        try (InputStream input = NativeBuildMetadata.class.getResourceAsStream("/veltis-build-metadata.json")) {
            if (input == null) {
                LOG.log(System.Logger.Level.INFO,
                        "No embedded Veltis build metadata; commit-pinned native installation is unavailable");
                return "";
            }
            var bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) {
                throw new IOException("build metadata exceeds 64 KiB");
            }
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("commit")
                    || !root.getAsJsonObject().get("commit").isJsonPrimitive()
                    || !root.getAsJsonObject().getAsJsonPrimitive("commit").isString()) {
                throw new IOException("build metadata has no string commit");
            }
            var commit = root.getAsJsonObject().get("commit").getAsString();
            if (!FULL_COMMIT.matcher(commit).matches()) {
                throw new IOException("build metadata commit is not a full Git SHA");
            }
            return commit;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Embedded Veltis build metadata is invalid; commit-pinned native installation is unavailable", e);
            return "";
        }
    }
}
