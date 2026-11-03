package org.veltismc.patchengine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The bytecode patch set: everything that makes a VeltisMC server different
 * from the Minecraft jar Mojang published, and nothing else.
 *
 * <p>This is the only format that carries a patch from a build to a running
 * server. The development source patches under
 * {@code server/Shulker/code|data|modules} are how a contributor writes a
 * change; this is how that change ships. There is
 * no third representation in between — no reconstructed source, no decompiler on
 * a server, no compiler on a server.
 *
 * <h2>The container</h2>
 *
 * <p>A ZIP, because a ZIP already gives deterministic entry names, per-entry
 * compression, random access, and a structure every JVM can read without a
 * dependency. Three kinds of entry and nothing else:
 *
 * <pre>
 * META-INF/veltis/patch.properties   the metadata block (below)
 * index.txt                          one line per changed entry
 * runtime-patches/&lt;entry name&gt;       the bytes to write for that entry
 * </pre>
 *
 * <h2>The metadata block</h2>
 *
 * <p>Everything a patch format has to state about itself, as plain
 * {@code key=value} lines so the first thing anyone reads in a bug report is the
 * thing that explains the failure:
 *
 * <ul>
 *   <li>{@code formatVersion} — the patch format version. Read <em>before</em>
 *       anything is applied; a format this build does not know is a refusal, not
 *       a guess.</li>
 *   <li>{@code minecraftVersion} — the version the patch addresses. A patch for
 *       26.3 is never applied to 26.4, and the mismatch is reported with both
 *       values.</li>
 *   <li>{@code serverSha1} — the SHA-1 of the Mojang server artifact the patch
 *       was generated against. This is what makes the patch reject an artifact
 *       Mojang re-published under the same version id.</li>
 *   <li>{@code classesSha1} — the SHA-1 of the extracted classes jar that is the
 *       actual diff baseline. Mojang publishes the bundler jar's hash, not the
 *       inner one's, so this is the only hash that can prove the baseline bytes
 *       are the bytes the patch was cut from.</li>
 *   <li>{@code classFileRelease} — the class file release the payload classes
 *       were compiled to.</li>
 *   <li>{@code fingerprint} — SHA-256 over {@code index.txt}. The index carries
 *       the SHA-256 of every payload, of the baseline it replaces and of the
 *       bytes it must produce, so this one value changes when and only when the
 *       patch's <em>content</em> changes. It is half of the cache identity:
 *       version, artifact SHA-1, fingerprint, format version.</li>
 *   <li>{@code entryCount}, {@code classCount}, {@code resourceCount},
 *       {@code sourcePatchCount} — sizes, so a log line can state what was
 *       applied without parsing anything. {@code sourcePatchCount} is how many
 *       development source patches this patch set was generated from; it is
 *       deliberately not the number of class payloads, which counts classes
 *       rather than patches.</li>
 * </ul>
 *
 * <h2>The index</h2>
 *
 * <p>Tab-separated, sorted by entry name, one line per entry:
 *
 * <pre>
 * kind &lt;TAB&gt; name &lt;TAB&gt; originalSha256 &lt;TAB&gt; resultSha256 &lt;TAB&gt; payloadSha256
 * </pre>
 *
 * <p>All three hashes are always present and all are always checked.
 * {@code originalSha256} is the SHA-256 of the baseline entry in the vanilla jar —
 * or {@code -} when the entry does not exist there, which is itself a claim the
 * applier verifies. {@code resultSha256} is the SHA-256 of the bytes the applier
 * must produce for that entry: for a {@code CLASS} entry that is the merged
 * class, the value {@link ClassDelta#apply} re-derives before the class is
 * served. {@code payloadSha256} is the SHA-256 of the payload bytes as stored —
 * for a {@code CLASS} entry the delta file itself, which is what it is. An entry
 * whose original hash does not match is a patch cut from a different artifact,
 * and the applier refuses it rather than writing a jar that half applies.
 *
 * <h2>What a class payload is</h2>
 *
 * <p>Not a replacement class. A {@code CLASS} payload is a
 * {@link ClassDelta}: the Veltis-only difference between the vanilla class and
 * the class the patched sources compile to — members carried, members removed,
 * access flags rewritten — containing no Mojang bytecode and none of the
 * unchanged methods javac merely reproduced. Applying one reads the verified
 * vanilla class, splices the delta onto it, and checks the result against
 * {@code resultSha256} before the class is used. One delta per distinct
 * modified class: a patch set for five source patches that touch two classes
 * carries two deltas.
 *
 * <p>Resource payloads ({@code ENTRY}) remain whole files, because a resource
 * has no structure to take a difference of, and removals ({@code DELETE}) carry
 * no payload at all.
 *
 * <p>None of this happens here. This class stores and verifies bytes: it writes
 * the container, reads it back, and checks every hash. The interpretation of a
 * class delta lives in {@link ClassDelta}, and it only ever runs against a
 * vanilla class whose SHA-256 has already been checked against this index.
 */
public final class BytecodePatch {

    /**
     * The patch format version this build writes and is willing to read.
     *
     * <p>Bumped whenever the container, the metadata keys, or the index grammar
     * changes in a way an older applier would misread. Recorded in the metadata,
     * checked before a single entry is touched, and carried in the cache
     * identity — so an incompatible patch fails with a statement of both
     * versions instead of a corrupt jar.
     */
    public static final int FORMAT = 3;

    /** Where a packaged patch set lives inside the launcher jar, one per version. */
    public static final String RESOURCE_PREFIX = "META-INF/veltis/patches/";

    /** The metadata entry inside the container. */
    public static final String METADATA_ENTRY = "META-INF/veltis/patch.properties";

    /** The index entry inside the container. */
    public static final String INDEX_ENTRY = "index.txt";

    /** The prefix every payload entry shares, inside the container. */
    public static final String PAYLOAD_PREFIX = "runtime-patches/";

    /** The placeholder for "there is no such value", as distinct from an empty one. */
    public static final String ABSENT = "-";

    /** What an index line says about one entry of the baseline jar. */
    public enum Kind {
        /** Replace the entry, or add it when the baseline does not have it. */
        ENTRY,
        /** Remove the entry from the result. */
        DELETE,
        /**
         * Apply a {@link ClassDelta} payload onto the baseline class and write
         * what comes out. The payload is the delta, not a class; the index's
         * {@code resultSha256} is the hash of the class the applier must
         * produce, which is what makes the result checkable.
         */
        CLASS
    }

    /**
     * One line of {@link #INDEX_ENTRY}.
     *
     * @param kind          whether the entry is written, removed, or spliced
     *                      from a class delta
     * @param name          the entry name, exactly as it appears in a jar
     * @param originalSha256 SHA-256 of the baseline entry, or {@link #ABSENT}
     * @param resultSha256  SHA-256 of the bytes the applier must produce, or
     *                      {@link #ABSENT} for a removal
     * @param payloadSha256 SHA-256 of the payload bytes as stored, or
     *                      {@link #ABSENT} for a removal
     */
    public record IndexEntry(Kind kind, String name, String originalSha256,
                             String resultSha256, String payloadSha256) {
    }

    /**
     * The metadata block.
     *
     * @param minecraftVersion the Minecraft version this patch addresses
     * @param serverSha1       SHA-1 of Mojang's server artifact for that version
     * @param classesSha1      SHA-1 of the extracted classes jar used as the baseline
     * @param classFileRelease the class file release the payloads were compiled to
     * @param fingerprint      SHA-256 over the index
     * @param entryCount       number of index lines
     * @param classCount       how many of them are class entries
     * @param resourceCount    how many of them are resources or removals
     * @param sourcePatchCount how many development source patches this set was
     *                         generated from — kept separate from the class and
     *                         entry counts on purpose: patches and classes are
     *                         not the same quantity
     */
    public record Metadata(String minecraftVersion, String serverSha1, String classesSha1,
                           int classFileRelease, String fingerprint, int entryCount,
                           int classCount, int resourceCount, int sourcePatchCount) {

        /** The value of {@code formatVersion} as this build writes it. */
        public String formatVersion() {
            return String.valueOf(FORMAT);
        }

        /**
         * Whether this metadata describes the same artifact the patch was cut
         * from.
         *
         * @param actualServerSha1  the SHA-1 of the artifact on disk or about to
         *                          be downloaded
         * @param actualClassesSha1 the SHA-1 of the extracted classes jar
         */
        public boolean matches(String actualServerSha1, String actualClassesSha1) {
            return serverSha1.equalsIgnoreCase(actualServerSha1)
                && classesSha1.equalsIgnoreCase(actualClassesSha1);
        }
    }

    /**
     * How long reading the patch took, split so the two costs are reported in
     * their own columns instead of one blended number.
     *
     * @param metadataNanos opening the container, reading the metadata block and
     *                      the index, verifying the fingerprint
     * @param payloadNanos  reading the payloads themselves
     */
    public record ReadTiming(long metadataNanos, long payloadNanos) {
        public long totalNanos() {
            return metadataNanos + payloadNanos;
        }
    }

    private final Metadata metadata;
    private final List<IndexEntry> entries;
    private final Map<String, byte[]> container;
    private final ReadTiming timing;

    private BytecodePatch(Metadata metadata, List<IndexEntry> entries,
                          Map<String, byte[]> container, ReadTiming timing) {
        this.metadata = metadata;
        this.entries = List.copyOf(entries);
        this.container = container;
        this.timing = timing;
    }

    /** The metadata block, already format- and fingerprint-validated. */
    public Metadata metadata() {
        return metadata;
    }

    /** The index, sorted by entry name so application order is deterministic. */
    public List<IndexEntry> entries() {
        return entries;
    }

    /**
     * The bytes for {@code name}, or {@code null} when the index marks the entry
     * as a removal.
     */
    public byte[] payload(String name) {
        return container.get(PAYLOAD_PREFIX + name);
    }

    /** How long this instance took to read, for the loading benchmark. */
    public ReadTiming timing() {
        return timing;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads a patch set from disk: container, metadata, index, payloads — with
     * the two phases timed apart.
     *
     * <p>Nothing here validates against a Minecraft artifact. That is deliberate:
     * a patch set that has been read is not a patch set that has been accepted.
     * Format version, target version, artifact SHA-1, per-entry original hashes
     * and per-entry result hashes are all checked by the applier, in order,
     * before and after it writes anything.
     *
     * @throws PatchEngineException when the container is not a patch set this
     *                              build understands, naming both format
     *                              versions when the reason is a format change
     */
    public static BytecodePatch read(Path file) {
        Objects.requireNonNull(file, "file cannot be null");
        try (var in = Files.newInputStream(file)) {
            return read(in, file.toString());
        } catch (IOException e) {
            throw new PatchEngineException(
                "Cannot read the bytecode patch set " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Reads a patch set from a stream, such as one packaged inside the launcher
     * jar.
     *
     * @param in     the container's bytes
     * @param source a description used in every failure message, because the
     *               caller may be reading from a jar rather than a path
     */
    public static BytecodePatch read(InputStream in, String source) {
        var opened = System.nanoTime();
        var container = new TreeMap<String, byte[]>();
        long metadataNanos;
        long payloadNanos;
        try (var zip = new ZipInputStream(in)) {
            long metadata = System.nanoTime() - opened;
            long payload = 0L;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                var start = System.nanoTime();
                var bytes = zip.readAllBytes();
                var elapsed = System.nanoTime() - start;
                if (entry.getName().startsWith(PAYLOAD_PREFIX)) {
                    payload += elapsed;
                } else {
                    metadata += elapsed;
                }
                container.put(entry.getName(), bytes);
            }
            metadataNanos = metadata;
            payloadNanos = payload;
        } catch (IOException e) {
            throw new PatchEngineException(
                source + " is not a readable bytecode patch set"
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        var parsed = fromContainer(container, source);
        return new BytecodePatch(parsed.metadata(), parsed.entries(), container,
            new ReadTiming(metadataNanos, payloadNanos));
    }

    /**
     * Reads a packaged patch set out of a launcher jar.
     *
     * @throws PatchEngineException when this jar carries no patch set for the
     *                              requested version, naming the resource it
     *                              looked for — a jar ships one patch set per
     *                              Minecraft version, and an absent one is a
     *                              statement about the jar, not a reason to
     *                              proceed without a patch
     */
    public static BytecodePatch readPackaged(ClassLoader loader, String minecraftVersion) {
        var path = RESOURCE_PREFIX + minecraftVersion + ".zip";
        var stream = loader.getResourceAsStream(path);
        if (stream == null) {
            throw new PatchEngineException(
                "This jar carries no bytecode patch set for Minecraft " + minecraftVersion
                    + "\n  Expected resource: " + path
                    + "\n  Reason: a jar ships one patch set per Minecraft version, and this one"
                    + " has none for the requested version"
                    + "\n  Fix: run this jar with a version it was built for, or rebuild it for "
                    + minecraftVersion);
        }
        try (stream) {
            return read(stream, "packaged bytecode patch set for Minecraft " + minecraftVersion);
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to read the packaged bytecode patch set for Minecraft "
                    + minecraftVersion, e);
        }
    }

    private static BytecodePatch fromContainer(Map<String, byte[]> container, String source) {
        var metadataBytes = container.get(METADATA_ENTRY);
        if (metadataBytes == null) {
            throw new PatchEngineException(
                source + " carries no " + METADATA_ENTRY
                    + "\n  Reason: a bytecode patch set states its own format, target version,"
                    + " artifact hashes and fingerprint. Without them there is nothing to"
                    + " validate before applying, so the patch is refused rather than applied"
                    + " blindly.");
        }
        var values = parseKeyValues(new String(metadataBytes, StandardCharsets.UTF_8));
        var format = values.get("formatVersion");
        if (!String.valueOf(FORMAT).equals(format)) {
            throw new PatchEngineException(
                "Bytecode patch format " + format + " is not supported by this build"
                    + "\n  Source: " + source
                    + "\n  Expected format version: " + FORMAT
                    + "\n  Actual format version:   " + format
                    + "\n  Reason: applying a patch in a format this build does not understand"
                    + " would misread its index, and a misread index is indistinguishable from"
                    + " a corrupt jar only after the server has started");
        }
        var indexBytes = container.get(INDEX_ENTRY);
        if (indexBytes == null) {
            throw new PatchEngineException(
                source + " carries no " + INDEX_ENTRY
                    + "\n  Reason: the index is the list of entries the patch changes. Without"
                    + " it there is nothing to validate and nothing to apply.");
        }
        var fingerprint = sha256Hex(indexBytes);
        var recorded = values.get("fingerprint");
        if (recorded == null || !recorded.equalsIgnoreCase(fingerprint)) {
            throw new PatchEngineException(
                "Bytecode patch set is corrupt: its index does not match its fingerprint"
                    + "\n  Source: " + source
                    + "\n  Expected fingerprint: " + recorded
                    + "\n  Actual fingerprint:   " + fingerprint
                    + "\n  Reason: the index lists every entry and every hash, so a different"
                    + " fingerprint means the patch was edited, truncated or mixed with another");
        }
        var metadata = new Metadata(
            values.get("minecraftVersion"),
            values.get("serverSha1"),
            values.get("classesSha1"),
            requireInt(values, "classFileRelease", source),
            fingerprint,
            requireInt(values, "entryCount", source),
            requireInt(values, "classCount", source),
            requireInt(values, "resourceCount", source),
            requireInt(values, "sourcePatchCount", source));
        if (metadata.minecraftVersion() == null || metadata.serverSha1() == null
                || metadata.classesSha1() == null) {
            throw new PatchEngineException(
                "Bytecode patch set in " + source + " is missing required metadata"
                    + "\n  Present keys: " + values.keySet()
                    + "\n  Required: minecraftVersion, serverSha1, classesSha1"
                    + "\n  Reason: without the artifact hashes there is no way to tell which"
                    + " Minecraft build this patch belongs to, and a patch is never applied to"
                    + " an artifact it was not cut from");
        }
        var entries = parseIndex(indexBytes, metadata, source);
        for (var entry : entries) {
            if (entry.kind() == Kind.DELETE) {
                continue;
            }
            if (!container.containsKey(PAYLOAD_PREFIX + entry.name())) {
                throw new PatchEngineException(
                    "Bytecode patch set in " + source + " has no payload for "
                        + entry.name()
                        + "\n  Reason: the index promises this entry and the container does not"
                        + " carry it, so the patch is incomplete and applying it would leave a"
                        + " jar the index describes as patched but which is not");
            }
        }
        return new BytecodePatch(metadata, entries, container, new ReadTiming(0L, 0L));
    }

    private static List<IndexEntry> parseIndex(byte[] indexBytes, Metadata metadata,
                                               String source) {
        var entries = new ArrayList<IndexEntry>();
        var previousName = "";
        for (var line : new String(indexBytes, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var parts = line.split("\t", -1);
            if (parts.length != 5) {
                throw new PatchEngineException(
                    "Bytecode patch index in " + source + " has a malformed line"
                        + "\n  Line: " + line
                        + "\n  Expected: kind<TAB>name<TAB>originalSha256<TAB>resultSha256"
                        + "<TAB>payloadSha256"
                        + "\n  Reason: every entry must carry all three hashes, because all are"
                        + " checked — the original before writing, the result after, and the"
                        + " payload when it is read");
            }
            Kind kind;
            try {
                kind = Kind.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                throw new PatchEngineException(
                    "Bytecode patch index names an unknown entry kind '" + parts[0] + "'"
                        + "\n  Source: " + source
                        + "\n  Line: " + line
                        + "\n  Known kinds: " + List.of(Kind.values()), e);
            }
            if (parts[1].compareTo(previousName) < 0) {
                throw new PatchEngineException(
                    "Bytecode patch index is not sorted by entry name"
                        + "\n  Source: " + source
                        + "\n  Line: " + line
                        + "\n  Previous: " + previousName
                        + "\n  Reason: application order is the index order, so an unsorted index"
                        + " would make the result depend on how the patch was written");
            }
            previousName = parts[1];
            entries.add(new IndexEntry(kind, parts[1], parts[2], parts[3], parts[4]));
        }
        if (entries.size() != metadata.entryCount()) {
            throw new PatchEngineException(
                "Bytecode patch set in " + source + " declares "
                    + metadata.entryCount() + " entries but its index holds " + entries.size()
                    + "\n  Reason: the metadata and the index disagree, so the patch cannot be"
                    + " trusted to be complete");
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * One changed entry as the generator produces it.
     *
     * @param name           the jar entry name
     * @param kind           whether this writes a class delta, writes a
     *                       resource, or removes the entry
     * @param payload        the bytes to store: the delta for {@link Kind#CLASS},
     *                       the file for {@link Kind#ENTRY}, {@code null} for
     *                       {@link Kind#DELETE}
     * @param originalSha256 SHA-256 of the baseline entry, or {@link #ABSENT}
     * @param resultSha256   SHA-256 of the bytes the applier must produce for
     *                       this entry — the merged class for {@link Kind#CLASS},
     *                       the payload itself for {@link Kind#ENTRY},
     *                       {@link #ABSENT} for {@link Kind#DELETE}
     */
    public record ProducedEntry(String name, Kind kind, byte[] payload, String originalSha256,
                                String resultSha256) {
        public ProducedEntry {
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(kind, "kind cannot be null");
            Objects.requireNonNull(originalSha256, "originalSha256 cannot be null");
            Objects.requireNonNull(resultSha256, "resultSha256 cannot be null");
        }
    }

    /**
     * Writes a patch set, deterministically.
     *
     * <p>Entries are written in name order with fixed timestamps, so two builds
     * over the same inputs produce byte-identical containers. The staging file
     * and atomic move mean a crash leaves a {@code .writing} sibling rather than
     * a truncated patch set a later build would trust.
     *
     * @param target           where to write it; parent directories are created
     * @param minecraftVersion the version the payloads were produced against
     * @param serverSha1       Mojang's SHA-1 for that version's server artifact
     * @param classesSha1      SHA-1 of the classes jar the payloads were diffed against
     * @param classFileRelease the class file release the payloads were compiled to
     * @param sourcePatchCount how many development source patches produced these records
     * @param records          the changed entries, in any order
     * @return the metadata that was written, which is what the caller records
     */
    public static Metadata write(Path target, String minecraftVersion, String serverSha1,
                                 String classesSha1, int classFileRelease,
                                 int sourcePatchCount, List<ProducedEntry> records) {
        Objects.requireNonNull(records, "records cannot be null");
        var sorted = new TreeMap<String, ProducedEntry>();
        for (var record : records) {
            var previous = sorted.put(record.name(), record);
            if (previous != null) {
                throw new PatchEngineException(
                    "Bytecode patch generation produced " + record.name() + " twice"
                        + "\n  Reason: one jar entry has one value, so a duplicate would make the"
                        + " applied result depend on iteration order");
            }
        }
        var index = new StringBuilder();
        int classCount = 0;
        int resourceCount = 0;
        for (var entry : sorted.values()) {
            var removal = entry.kind() == Kind.DELETE;
            var payloadSha = removal ? ABSENT : sha256Hex(entry.payload());
            validate(entry, payloadSha);
            index.append(entry.kind()).append('\t')
                .append(entry.name()).append('\t')
                .append(entry.originalSha256()).append('\t')
                .append(entry.resultSha256()).append('\t')
                .append(payloadSha).append('\n');
            if (entry.name().endsWith(".class")) {
                classCount++;
            } else {
                resourceCount++;
            }
        }
        var indexBytes = index.toString().getBytes(StandardCharsets.UTF_8);
        var metadata = new Metadata(minecraftVersion, serverSha1, classesSha1, classFileRelease,
            sha256Hex(indexBytes), sorted.size(), classCount, resourceCount, sourcePatchCount);

        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            var staging = target.resolveSibling(target.getFileName() + ".writing");
            Files.deleteIfExists(staging);
            try (var zip = new ZipOutputStream(Files.newOutputStream(staging))) {
                writeEntry(zip, METADATA_ENTRY, renderMetadata(metadata));
                writeEntry(zip, INDEX_ENTRY, indexBytes);
                for (var entry : sorted.values()) {
                    if (entry.payload() != null) {
                        writeEntry(zip, PAYLOAD_PREFIX + entry.name(), entry.payload());
                    }
                }
            }
            try {
                Files.move(staging, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return metadata;
        } catch (IOException e) {
            throw new PatchEngineException(
                "Failed to write the bytecode patch set " + target
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Writes one entry with a fixed timestamp, so two builds of the same inputs
     * produce byte-identical containers.
     *
     * @throws PatchEngineException when the name could escape the container,
     *                              which would let a payload land outside the jar
     *                              it is applied to
     */
    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        if (name.isBlank() || name.startsWith("/") || name.contains("..")
                || name.indexOf('\\') >= 0) {
            throw new PatchEngineException(
                "Refusing to write an unsafe entry name into a bytecode patch set: '"
                    + name + "'"
                    + "\n  Reason: entry names are relative paths inside a jar; a name that"
                    + " escapes that directory would be written outside the server directory");
        }
        var entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Refuses a record whose hashes cannot describe what it is about to write.
     * The generator computes these values; a disagreement here is a bug in the
     * build rather than a corrupt patch, and catching it before a byte is
     * written keeps a bad container from ever existing.
     *
     * @param payloadSha the SHA-256 of the payload, or {@link #ABSENT} for a removal
     */
    private static void validate(ProducedEntry entry, String payloadSha) {
        if (!isSha256(entry.originalSha256()) && !ABSENT.equals(entry.originalSha256())) {
            throw new PatchEngineException(
                "Bytecode patch generation produced " + entry.name() + " with a"
                    + " malformed baseline hash: " + entry.originalSha256()
                    + "\n  Reason: the baseline hash is what proves this entry was cut from"
                    + " this exact vanilla jar, and it must be a SHA-256 or \"-\""
                    + "\n  Nothing was written.");
        }
        switch (entry.kind()) {
            case DELETE -> {
                if (entry.payload() != null) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced the removal " + entry.name()
                            + " with a payload"
                            + "\n  Reason: a removal deletes the baseline entry; carrying bytes"
                            + " for it would make the result depend on which of the two the"
                            + " applier chose"
                            + "\n  Nothing was written.");
                }
                if (!ABSENT.equals(entry.resultSha256())) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced the removal " + entry.name()
                            + " with a result hash of " + entry.resultSha256()
                            + "\n  Reason: a removed entry produces nothing, so its result hash"
                            + " must be \"-\""
                            + "\n  Nothing was written.");
                }
            }
            case ENTRY -> {
                if (entry.payload() == null) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced the entry " + entry.name()
                            + " without its bytes"
                            + "\n  Reason: an entry payload is written as-is, so it must be"
                            + " present and its result hash must be its own hash"
                            + "\n  Nothing was written.");
                }
                if (!payloadSha.equalsIgnoreCase(entry.resultSha256())) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced " + entry.name() + " with a"
                            + " result hash that is not its payload's hash"
                            + "\n  Payload SHA-256: " + payloadSha
                            + "\n  Result SHA-256:  " + entry.resultSha256()
                            + "\n  Reason: for a resource the bytes are the result, so the two"
                            + " hashes must agree"
                            + "\n  Nothing was written.");
                }
            }
            case CLASS -> {
                if (entry.payload() == null) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced the class delta for "
                            + entry.name() + " without its payload"
                            + "\n  Reason: a class delta is applied onto the verified vanilla"
                            + " class; without the delta there is no patch"
                            + "\n  Nothing was written.");
                }
                if (!isSha256(entry.resultSha256())) {
                    throw new PatchEngineException(
                        "Bytecode patch generation produced the class delta for "
                            + entry.name() + " with a malformed result hash: "
                            + entry.resultSha256()
                            + "\n  Reason: the result hash is the hash of the merged class the"
                            + " applier must reproduce byte for byte, so it must be a SHA-256"
                            + "\n  Nothing was written.");
                }
            }
        }
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] renderMetadata(Metadata metadata) {
        return ("formatVersion=" + metadata.formatVersion() + '\n'
            + "minecraftVersion=" + metadata.minecraftVersion() + '\n'
            + "serverSha1=" + metadata.serverSha1().toLowerCase(Locale.ROOT) + '\n'
            + "classesSha1=" + metadata.classesSha1().toLowerCase(Locale.ROOT) + '\n'
            + "classFileRelease=" + metadata.classFileRelease() + '\n'
            + "fingerprint=" + metadata.fingerprint() + '\n'
            + "entryCount=" + metadata.entryCount() + '\n'
            + "classCount=" + metadata.classCount() + '\n'
            + "resourceCount=" + metadata.resourceCount() + '\n'
            + "sourcePatchCount=" + metadata.sourcePatchCount() + '\n')
            .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The {@code key=value} lines of a metadata block. */
    static Map<String, String> parseKeyValues(String text) {
        var values = new LinkedHashMap<String, String>();
        var properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new PatchEngineException("Bytecode patch metadata is unreadable"
                + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        for (var name : properties.stringPropertyNames()) {
            values.putIfAbsent(name, properties.getProperty(name));
        }
        return values;
    }

    private static int requireInt(Map<String, String> values, String key, String source) {
        var value = values.get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException | NullPointerException e) {
            throw new PatchEngineException(
                "Bytecode patch metadata in " + source + " has no usable " + key
                    + "\n  Value: " + value
                    + "\n  Reason: the field is required and numeric, and a patch set that does"
                    + " not state its own shape cannot be validated", e);
        }
    }

    /** SHA-256 of a payload, baseline entry or index, lowercase hex. */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest("SHA-256", bytes));
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide both SHA-1 and SHA-256.
            throw new IllegalStateException("this JRE does not provide " + algorithm, e);
        }
    }
}
