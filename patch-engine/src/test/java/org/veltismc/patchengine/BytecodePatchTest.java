package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one format that carries a change from a build to a running server.
 *
 * <p>Every test here is a way the distribution could be wrong without looking
 * wrong: a patch written twice differently, an edited index, a format version
 * this build cannot read, a payload whose bytes are not the bytes the index
 * promised. All of them are silent until the server is already serving, so each
 * one is required to fail at read time, with both values in the message.
 */
class BytecodePatchTest {

    private static final String SERVER_SHA1 = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c";
    private static final String CLASSES_SHA1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static List<BytecodePatch.ProducedEntry> sampleEntries() {
        return List.of(
            new BytecodePatch.ProducedEntry("net/minecraft/server/MinecraftServer.class",
                BytecodePatch.Kind.ENTRY, new byte[] {1, 2, 3, 4},
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                BytecodePatch.sha256Hex(new byte[] {1, 2, 3, 4})),
            new BytecodePatch.ProducedEntry("assets/veltis/lang/en_us.json",
                BytecodePatch.Kind.ENTRY, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                BytecodePatch.ABSENT,
                BytecodePatch.sha256Hex("{\"a\":1}".getBytes(StandardCharsets.UTF_8))),
            new BytecodePatch.ProducedEntry("META-INF/OLD.SF",
                BytecodePatch.Kind.DELETE, null,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                BytecodePatch.ABSENT));
    }

    private static Path writeSample(Path target) {
        BytecodePatch.write(target, "26.3", SERVER_SHA1, CLASSES_SHA1, 26, 1, sampleEntries());
        return target;
    }

    /** Rebuilds a container, substituting entries by name, keeping everything else. */
    private static BytecodePatch rewrite(Path source, Map<String, byte[]> replacements,
                                         List<String> removed) throws IOException {
        var rebuilt = source.resolveSibling("rebuilt.zip");
        try (var in = new ZipFile(source.toFile());
             var out = new ZipOutputStream(Files.newOutputStream(rebuilt))) {
            for (var e = in.entries(); e.hasMoreElements(); ) {
                var entry = e.nextElement();
                if (entry.isDirectory() || removed.contains(entry.getName())) {
                    continue;
                }
                byte[] bytes;
                try (var stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                var replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    bytes = replacement;
                }
                var target = new ZipEntry(entry.getName());
                target.setTime(0L);
                out.putNextEntry(target);
                out.write(bytes);
                out.closeEntry();
            }
        }
        try (var stream = Files.newInputStream(rebuilt)) {
            return BytecodePatch.read(stream, rebuilt.toString());
        }
    }

    private static byte[] metadataOf(Path container) throws IOException {
        try (var zip = new ZipFile(container.toFile())) {
            var entry = zip.getEntry(BytecodePatch.METADATA_ENTRY);
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static byte[] indexOf(Path container) throws IOException {
        try (var zip = new ZipFile(container.toFile())) {
            var entry = zip.getEntry(BytecodePatch.INDEX_ENTRY);
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static PatchEngineException refusal(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(PatchEngineException.class, action);
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    void everythingWrittenComesBackUnchanged(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var patch = BytecodePatch.read(file);

        assertEquals("26.3", patch.metadata().minecraftVersion());
        assertEquals(SERVER_SHA1, patch.metadata().serverSha1());
        assertEquals(CLASSES_SHA1, patch.metadata().classesSha1());
        assertEquals(26, patch.metadata().classFileRelease());
        assertEquals(3, patch.metadata().entryCount());
        assertEquals(1, patch.metadata().classCount());
        assertEquals(2, patch.metadata().resourceCount());

        assertEquals(
            List.of("META-INF/OLD.SF", "assets/veltis/lang/en_us.json",
                "net/minecraft/server/MinecraftServer.class"),
            patch.entries().stream().map(BytecodePatch.IndexEntry::name).toList(),
            "the index is sorted by name, so applying it cannot depend on write order");

        assertArrayEquals(new byte[] {1, 2, 3, 4},
            patch.payload("net/minecraft/server/MinecraftServer.class"));
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8),
            patch.payload("assets/veltis/lang/en_us.json"));
        assertNull(patch.payload("META-INF/OLD.SF"),
            "a removal carries no payload, which is what makes it a removal");
    }

    @Test
    void bothHashesAreCarriedForEveryEntry(@TempDir Path tmp) throws Exception {
        var patch = BytecodePatch.read(writeSample(tmp.resolve("patch.zip")));
        var byName = new LinkedHashMap<String, BytecodePatch.IndexEntry>();
        for (var entry : patch.entries()) {
            byName.put(entry.name(), entry);
        }

        assertEquals(BytecodePatch.Kind.ENTRY,
            byName.get("net/minecraft/server/MinecraftServer.class").kind());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            byName.get("net/minecraft/server/MinecraftServer.class").originalSha256());
        assertEquals(BytecodePatch.sha256Hex(new byte[] {1, 2, 3, 4}),
            byName.get("net/minecraft/server/MinecraftServer.class").resultSha256());

        assertEquals(BytecodePatch.ABSENT,
            byName.get("assets/veltis/lang/en_us.json").originalSha256(),
            "'-' means the baseline does not have this entry, which is itself a claim"
                + " the applier checks against the vanilla jar");

        assertEquals(BytecodePatch.Kind.DELETE, byName.get("META-INF/OLD.SF").kind());
        assertEquals(BytecodePatch.ABSENT, byName.get("META-INF/OLD.SF").resultSha256());
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    void twoWritesOverTheSameInputsProduceOneByteString(@TempDir Path tmp) throws Exception {
        var first = BytecodePatch.write(tmp.resolve("a.zip"), "26.3", SERVER_SHA1, CLASSES_SHA1,
            26, 1, sampleEntries());
        var second = BytecodePatch.write(tmp.resolve("b.zip"), "26.3", SERVER_SHA1, CLASSES_SHA1,
            26, 1, new ArrayList<>(List.copyOf(sampleEntries().reversed())));

        assertArrayEquals(Files.readAllBytes(tmp.resolve("a.zip")),
            Files.readAllBytes(tmp.resolve("b.zip")),
            "entry order and timestamps are fixed by the format, so the patch set a"
                + " build produces is a function of its inputs and nothing else");
        assertEquals(first, second, "and so is the metadata describing it");
    }

    @Test
    void theFingerprintChangesWhenAndOnlyWhenContentDoes(@TempDir Path tmp) throws Exception {
        var base = BytecodePatch.write(tmp.resolve("base.zip"), "26.3", SERVER_SHA1, CLASSES_SHA1,
            26, 1, sampleEntries()).fingerprint();

        var sameAgain = BytecodePatch.write(tmp.resolve("again.zip"), "26.3", SERVER_SHA1,
            CLASSES_SHA1, 26, 1, sampleEntries()).fingerprint();
        assertEquals(base, sameAgain,
            "a patch set rebuilt from the same inputs must keep its fingerprint, or every"
                + " second build would re-download and re-apply");

        var changed = BytecodePatch.write(tmp.resolve("changed.zip"), "26.3", SERVER_SHA1,
            CLASSES_SHA1, 26, 1, List.of(
                new BytecodePatch.ProducedEntry("net/minecraft/server/MinecraftServer.class",
                    BytecodePatch.Kind.ENTRY, new byte[] {1, 2, 3, 5},
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    BytecodePatch.sha256Hex(new byte[] {1, 2, 3, 5})),
                new BytecodePatch.ProducedEntry("assets/veltis/lang/en_us.json",
                    BytecodePatch.Kind.ENTRY, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                    BytecodePatch.ABSENT,
                    BytecodePatch.sha256Hex("{\"a\":1}".getBytes(StandardCharsets.UTF_8))),
                new BytecodePatch.ProducedEntry("META-INF/OLD.SF",
                    BytecodePatch.Kind.DELETE, null,
                    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    BytecodePatch.ABSENT))).fingerprint();
        assertNotEquals(base, changed,
            "one byte of payload differs, and the cache identity must notice");
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void anUnsupportedFormatVersionIsRefusedBeforeAnythingIsRead(@TempDir Path tmp)
        throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .replaceFirst("formatVersion=" + BytecodePatch.FORMAT, "formatVersion=999");

        var failure = refusal(() -> rewrite(file,
            Map.of(BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8)),
            List.of()));
        assertTrue(failure.getMessage().contains("999"), failure.getMessage());
        assertTrue(failure.getMessage().contains(String.valueOf(BytecodePatch.FORMAT)),
            "a format mismatch must state both versions: " + failure.getMessage());
    }

    @Test
    void anEditedIndexIsCaughtByTheFingerprint(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var index = new String(indexOf(file), StandardCharsets.UTF_8);
        var tampered = index.replace("1, 2, 3, 4", "9, 9, 9, 9")
            .replace("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");

        var failure = refusal(() -> rewrite(file,
            Map.of(BytecodePatch.INDEX_ENTRY, tampered.getBytes(StandardCharsets.UTF_8)),
            List.of()));
        assertTrue(failure.getMessage().contains("fingerprint"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Expected fingerprint"),
            "the message must show the hash the file claims: " + failure.getMessage());
    }

    @Test
    void aContainerWithoutMetadataIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var failure = refusal(() -> rewrite(file, Map.of(),
            List.of(BytecodePatch.METADATA_ENTRY)));
        assertTrue(failure.getMessage().contains(BytecodePatch.METADATA_ENTRY),
            failure.getMessage());
    }

    @Test
    void aContainerWithoutAnIndexIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .replace("fingerprint=" + BytecodePatch.sha256Hex(indexOf(file)),
                "fingerprint=" + "0".repeat(64));
        var failure = refusal(() -> rewrite(file,
            Map.of(BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8)),
            List.of(BytecodePatch.INDEX_ENTRY)));
        assertTrue(failure.getMessage().contains(BytecodePatch.INDEX_ENTRY),
            "the index is what states which entries change; without it there is nothing"
                + " to apply: " + failure.getMessage());
    }

    @Test
    void metadataMissingTheArtifactHashesIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .lines()
            .filter(line -> !line.startsWith("classesSha1="))
            .reduce("", (a, b) -> a + b + "\n");

        var failure = refusal(() -> rewrite(file,
            Map.of(BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8)),
            List.of()));
        assertTrue(failure.getMessage().contains("classesSha1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("never applied to"),
            "and must say why the field matters: " + failure.getMessage());
    }

    @Test
    void anIndexThatIsNotSortedIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var index = new String(indexOf(file), StandardCharsets.UTF_8);
        var lines = index.lines().filter(l -> !l.isEmpty()).toList();
        var scrambled = String.join("\n", lines.reversed()) + "\n";
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .replace("fingerprint=" + BytecodePatch.sha256Hex(indexOf(file)),
                "fingerprint=" + BytecodePatch.sha256Hex(
                    scrambled.getBytes(StandardCharsets.UTF_8)));

        var failure = refusal(() -> rewrite(file, Map.of(
            BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8),
            BytecodePatch.INDEX_ENTRY, scrambled.getBytes(StandardCharsets.UTF_8)), List.of()));
        assertTrue(failure.getMessage().contains("sorted"), failure.getMessage());
    }

    @Test
    void aMalformedIndexLineIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var index = new String(indexOf(file), StandardCharsets.UTF_8);
        var damaged = index.replace("\t", " ").replaceFirst(" ", "\t");
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .replace("fingerprint=" + BytecodePatch.sha256Hex(indexOf(file)),
                "fingerprint=" + BytecodePatch.sha256Hex(
                    damaged.getBytes(StandardCharsets.UTF_8)));

        var failure = refusal(() -> rewrite(file, Map.of(
            BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8),
            BytecodePatch.INDEX_ENTRY, damaged.getBytes(StandardCharsets.UTF_8)), List.of()));
        assertTrue(failure.getMessage().contains("Expected: kind"),
            "the message must restate the grammar rather than just say it is wrong: "
                + failure.getMessage());
    }

    @Test
    void anIndexTheMetadataDisagreesWithIsRefused(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var index = new String(indexOf(file), StandardCharsets.UTF_8)
            .lines()
            .filter(line -> !line.isEmpty() && !line.contains("OLD.SF"))
            .reduce("", (a, b) -> a + b + "\n");
        var metadata = new String(metadataOf(file), StandardCharsets.UTF_8)
            .replace("fingerprint=" + BytecodePatch.sha256Hex(indexOf(file)),
                "fingerprint=" + BytecodePatch.sha256Hex(index.getBytes(StandardCharsets.UTF_8)));

        var failure = refusal(() -> rewrite(file, Map.of(
            BytecodePatch.METADATA_ENTRY, metadata.getBytes(StandardCharsets.UTF_8),
            BytecodePatch.INDEX_ENTRY, index.getBytes(StandardCharsets.UTF_8)), List.of()));
        assertTrue(failure.getMessage().contains("declares"), failure.getMessage());
    }

    @Test
    void anIndexPromisingAPayloadTheContainerDoesNotCarryIsRefused(@TempDir Path tmp)
        throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        var failure = refusal(() -> rewrite(file, Map.of(),
            List.of(BytecodePatch.PAYLOAD_PREFIX + "assets/veltis/lang/en_us.json")));
        assertTrue(failure.getMessage().contains("assets/veltis/lang/en_us.json"),
            failure.getMessage());
    }

    @Test
    void somethingThatIsNotAPatchSetIsRefusedRatherThanReadAsAnEmptyOne(@TempDir Path tmp)
        throws Exception {
        var plain = tmp.resolve("not-a-zip.zip");
        Files.writeString(plain, "this is text", StandardCharsets.UTF_8);
        var failure = refusal(() -> BytecodePatch.read(plain));
        assertTrue(failure.getMessage().contains(BytecodePatch.METADATA_ENTRY),
            "an unreadable container must fail on its first missing requirement rather"
                + " than yield zero entries and a server that runs vanilla: "
                + failure.getMessage());

        var truncated = tmp.resolve("truncated.zip");
        var whole = Files.readAllBytes(writeSample(tmp.resolve("intact.zip")));
        Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length / 2));
        var cut = refusal(() -> BytecodePatch.read(truncated));
        assertTrue(cut.getMessage().contains("not a readable bytecode patch set")
                || cut.getMessage().contains(BytecodePatch.METADATA_ENTRY),
            cut.getMessage());
    }

    @Test
    void anEntryNameThatCouldEscapeTheContainerIsRefused(@TempDir Path tmp) {
        var failure = refusal(() -> BytecodePatch.write(tmp.resolve("evil.zip"), "26.3",
            SERVER_SHA1, CLASSES_SHA1, 26, 1,
            List.of(new BytecodePatch.ProducedEntry("../../etc/passwd",
                BytecodePatch.Kind.ENTRY, new byte[] {1}, BytecodePatch.ABSENT,
                BytecodePatch.sha256Hex(new byte[] {1})))));
        assertTrue(failure.getMessage().contains("unsafe entry name"), failure.getMessage());
    }

    @Test
    void theSameEntryTwiceIsRefused(@TempDir Path tmp) {
        var failure = refusal(() -> BytecodePatch.write(tmp.resolve("twice.zip"), "26.3",
            SERVER_SHA1, CLASSES_SHA1, 26, 1,
            List.of(new BytecodePatch.ProducedEntry("a.txt",
                    BytecodePatch.Kind.ENTRY, new byte[] {1}, BytecodePatch.ABSENT,
                    BytecodePatch.sha256Hex(new byte[] {1})),
                new BytecodePatch.ProducedEntry("a.txt",
                    BytecodePatch.Kind.ENTRY, new byte[] {2}, BytecodePatch.ABSENT,
                    BytecodePatch.sha256Hex(new byte[] {2})))));
        assertTrue(failure.getMessage().contains("twice"), failure.getMessage());
    }

    // ------------------------------------------------------------------
    // Packaging
    // ------------------------------------------------------------------

    @Test
    void aJarCarryingNoPatchSetForTheVersionSaysWhichResourceItLookedFor(@TempDir Path tmp)
        throws Exception {
        var root = Files.createTempDirectory("veltis-jar");
        var patch = writeSample(root.resolve("x.zip"));
        Files.createDirectories(root.resolve("META-INF/veltis/patches"));
        Files.copy(patch, root.resolve("META-INF/veltis/patches/26.3.zip"));

        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {
                root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertEquals(3, BytecodePatch.readPackaged(loader, "26.3").entries().size());

            var failure = refusal(() -> BytecodePatch.readPackaged(loader, "26.4"));
            assertTrue(failure.getMessage().contains("26.4"), failure.getMessage());
            assertTrue(failure.getMessage().contains(
                    BytecodePatch.RESOURCE_PREFIX + "26.4.zip"),
                "a missing patch set must name the resource it wanted: "
                    + failure.getMessage());
        }
    }

    @Test
    void theReadTimingSplitsMetadataFromPayload(@TempDir Path tmp) throws Exception {
        var file = writeSample(tmp.resolve("patch.zip"));
        try (var stream = Files.newInputStream(file)) {
            var patch = BytecodePatch.read(stream, file.toString());
            assertTrue(patch.timing().metadataNanos() >= 0);
            assertTrue(patch.timing().payloadNanos() >= 0);
            assertEquals(patch.timing().totalNanos(),
                patch.timing().metadataNanos() + patch.timing().payloadNanos(),
                "the headline number must be the sum of the two columns it is printed from");
        }
    }
}
