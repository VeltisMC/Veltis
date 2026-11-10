package org.veltismc.nativeengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBuildMetadataTest {

    private static final String COMMIT = "3f794c0" + "1".repeat(33);
    private static final String SHORT = "3f794c0";
    private static final String TAG = "veltis-pre-alpha-26.3-" + SHORT;

    @TempDir
    Path root;

    @Test
    void readsTheIdentityTheDistributableEmbeds() throws Exception {
        write("""
                {
                  "schemaVersion": 1,
                  "commit": {"full": "%s", "short": "%s"},
                  "minecraftVersion": "26.3",
                  "veltisVersion": "26.3-pre-alpha",
                  "channel": "pre-alpha",
                  "releaseTag": "%s"
                }
                """.formatted(COMMIT, SHORT, TAG));

        var identity = identity();
        assertTrue(identity.pinned());
        assertEquals(COMMIT, identity.fullCommit());
        assertEquals(SHORT, identity.shortCommit());
        assertEquals(TAG, identity.releaseTag());
        assertEquals("26.3", identity.minecraftVersion());
        assertEquals("pre-alpha", identity.channel());
    }

    @Test
    void aMissingResourceIsUnpinnedRatherThanAnError() throws Exception {
        var identity = identity();
        assertEquals(NativeBuildMetadata.Identity.UNPINNED, identity);
        assertFalse(identity.pinned());
    }

    @Test
    void aCommitThatIsNotAFullShaIsUnpinned() throws Exception {
        write("""
                {
                  "commit": {"full": "3f794c0", "short": "3f794c0"},
                  "minecraftVersion": "26.3",
                  "channel": "pre-alpha",
                  "releaseTag": "veltis-pre-alpha-26.3-3f794c0"
                }
                """);
        assertFalse(identity().pinned());
    }

    @Test
    void aReleaseTagThatDoesNotNameTheCommitIsUnpinned() throws Exception {
        write("""
                {
                  "commit": {"full": "%s", "short": "%s"},
                  "minecraftVersion": "26.3",
                  "channel": "pre-alpha",
                  "releaseTag": "veltis-pre-alpha-26.3-0000000"
                }
                """.formatted(COMMIT, SHORT));
        assertFalse(identity().pinned());
    }

    @Test
    void anInvalidJsonDocumentIsUnpinned() throws Exception {
        write("{ not json");
        assertEquals(NativeBuildMetadata.Identity.UNPINNED, identity());
    }

    @Test
    void anOversizedDocumentIsRejected() throws Exception {
        Files.createDirectories(resourcePath().getParent());
        Files.writeString(resourcePath(), "{".repeat(40 * 1024));
        assertFalse(identity().pinned());
    }

    private void write(String document) throws IOException {
        Files.createDirectories(resourcePath().getParent());
        Files.writeString(resourcePath(), document, StandardCharsets.UTF_8);
    }

    private Path resourcePath() {
        return root.resolve(NativeBuildMetadata.RESOURCE);
    }

    private NativeBuildMetadata.Identity identity() throws IOException {
        try (var loader = new URLClassLoader(new java.net.URL[] {root.toUri().toURL()}, null)) {
            return NativeBuildMetadata.identity(loader);
        }
    }
}
