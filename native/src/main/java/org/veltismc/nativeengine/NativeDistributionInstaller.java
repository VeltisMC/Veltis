package org.veltismc.nativeengine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Everything that writes into {@code <home>/native/<platform>}: validating what
 * is already there, unpacking what arrived, and putting it in place.
 *
 * <p>The unpacking is deliberately paranoid, because the archive is the one
 * piece of this pipeline that arrives from the network:
 * <ul>
 *   <li>the archive's own SHA-256 must equal the signed manifest's, before it
 *       is opened;</li>
 *   <li>the zip may contain exactly three entries — this platform's library,
 *       the per-platform manifest and {@code SHA256SUMS} — so an entry named
 *       {@code ../../somewhere} or {@code /etc/passwd} is refused by the
 *       allowlist rather than by path normalisation, and a zip bomb runs into
 *       a byte cap while it is being read;</li>
 *   <li>the library's digest must equal the manifest's, the inner manifest
 *       must agree with the signed one, and {@code SHA256SUMS} must sign both
 *       files it shipped with;</li>
 *   <li>the extracted file passes {@link NativeLoader#checkBinary} — the same
 *       container/architecture gate the loader applies — <em>before</em> it is
 *       moved into place, so a wrong-architecture binary never becomes the
 *       installed one;</li>
 *   <li>the final move is atomic, and a half-written file is only ever named
 *       {@code *.part}.</li>
 * </ul>
 *
 * <p>Installations are guarded by an exclusive lock file so two servers
 * starting from the same home cannot download and unpack over each other.
 */
final class NativeDistributionInstaller {

    /** Records what was installed, so a later run can verify it without a network. */
    static final String INSTALL_RECORD = "transform.install.json";

    private static final String LOCK_FILE = ".distribution.lock";
    private static final String INNER_MANIFEST = "veltis-native-manifest.json";
    private static final String SUMS = "SHA256SUMS";

    private NativeDistributionInstaller() {}

    /**
     * The result of checking an existing installation: {@code valid} means the
     * file is present, matches its record, and passes the binary probe, so it
     * can be used (or adopted) without downloading anything.
     */
    record InstallState(boolean valid, String sha256, String nativeVersion, String detail) {
        static InstallState absent() {
            return new InstallState(false, "", "", "no library is installed for this platform");
        }
    }

    /** Checks the installed library against its record and the binary probe. */
    static InstallState validate(Path installDir, String platform) {
        return validate(installDir, NativeLoader.DEFAULT_MODULE_NAME, platform);
    }

    static InstallState validate(Path installDir, String module, String platform) {
        var library = installDir.resolve(NativeLoader.moduleLibraryFileName(module, platform));
        if (!Files.isRegularFile(library)) {
            return InstallState.absent();
        }
        final InstallRecord record;
        try {
            record = readRecord(installDir, module);
        } catch (IOException e) {
            return new InstallState(false, "", "", "the installation record is unreadable: " + e.getMessage());
        }
        if (record == null) {
            return new InstallState(false, "", "",
                    "a library is present but has no installation record, so it cannot be trusted");
        }
        if (!platform.equals(record.platform())) {
            return new InstallState(false, "", "",
                    "the installation record is for " + record.platform() + ", not " + platform);
        }
        if (!module.equals(record.module())) {
            return new InstallState(false, "", "",
                    "the installation record is for module " + record.module() + ", not " + module);
        }
        if (record.abiVersion() != NativeLoader.EXPECTED_ABI_VERSION) {
            return new InstallState(false, "", "",
                    "the installation record claims ABI " + record.abiVersion());
        }
        final String digest;
        try {
            digest = sha256(library);
        } catch (IOException e) {
            return new InstallState(false, "", "", "the installed library is unreadable: " + e.getMessage());
        }
        if (!digest.equals(record.sha256())) {
            return new InstallState(false, digest, record.nativeVersion(),
                    "the installed library does not match its installation record");
        }
        var problem = NativeLoader.checkBinary(library, platform);
        if (problem != null) {
            return new InstallState(false, digest, record.nativeVersion(), problem);
        }
        return new InstallState(true, digest, record.nativeVersion(), "verified installation");
    }

    /**
     * Unpacks {@code archiveFile} into {@code installDir} after checking every
     * claim the signed manifest makes about it.
     *
     * @throws IOException when any check fails; the installation directory is
     *                     left holding the previous library, never a partial one
     */
    static void install(Path installDir, String platform, Path archiveFile,
            NativeManifest.Artifact entry) throws IOException {
        if (!sha256(archiveFile).equals(entry.sha256())) {
            throw new IOException("the downloaded archive does not match the manifest's sha256");
        }

        var extracted = extract(Files.readAllBytes(archiveFile), platform, entry);
        verifyExtracted(platform, entry, extracted);

        Files.createDirectories(installDir);
        var library = installDir.resolve(entry.library());
        var staging = installDir.resolve(entry.library() + ".part");
        try {
            Files.write(staging, extracted.library(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            var problem = NativeLoader.checkBinary(staging, platform);
            if (problem != null) {
                throw new IOException("the downloaded library was rejected before installation: " + problem);
            }
            moveAtomically(staging, library);
            writeRecord(installDir, platform, entry, sha256(library));
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /**
     * An exclusive, non-blocking lock over {@code <home>/native}. Returns null
     * when another process (or this one) already holds it: the caller reports
     * that as "another installation is in progress" rather than waiting, so a
     * second server start never stalls on a download.
     */
    static DistributionLock tryLock(Path home) throws IOException {
        var directory = home.resolve("native");
        Files.createDirectories(directory);
        FileChannel channel;
        try {
            channel = FileChannel.open(directory.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IOException("cannot open the distribution lock: " + e.getMessage(), e);
        }
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException e) {
            channel.close();
            throw new IOException("cannot take the distribution lock: " + e.getMessage(), e);
        }
        if (lock == null) {
            channel.close();
            return null;
        }
        return new DistributionLock(channel, lock);
    }

    /** The held lock; closing it releases the file. */
    static final class DistributionLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private DistributionLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // The channel close below releases it too.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing else can be done, and nothing else holds the lock.
            }
        }
    }

    // ------------------------------------------------------------------
    // Archive reading and verification
    // ------------------------------------------------------------------

    /** The three files a release zip is allowed to contain. */
    private record Extracted(byte[] library, byte[] innerManifest, byte[] sums) {}

    private static Extracted extract(byte[] zipBytes, String platform, NativeManifest.Artifact entry)
            throws IOException {
        var allowed = Set.of(entry.library(), INNER_MANIFEST, SUMS);
        byte[] library = null;
        byte[] innerManifest = null;
        byte[] sums = null;
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = stream.getNextEntry()) != null) {
                var name = zipEntry.getName();
                if (zipEntry.isDirectory()) {
                    throw new IOException("the " + platform + " archive contains directory entry '"
                            + name + "', which the allowlist does not admit");
                }
                if (!allowed.contains(name)) {
                    throw new IOException("the " + platform + " archive contains unexpected entry '"
                            + name + "'; only " + allowed + " are allowed");
                }
                if (name.equals(entry.library())) {
                    if (library != null) {
                        throw new IOException("the " + platform + " archive contains two libraries");
                    }
                    library = readEntry(stream, name, NativeManifest.MAX_ARCHIVE_BYTES);
                } else if (name.equals(INNER_MANIFEST)) {
                    if (innerManifest != null) {
                        throw new IOException("the " + platform + " archive contains two manifests");
                    }
                    innerManifest = readEntry(stream, name, NativeManifest.MAX_MANIFEST_BYTES);
                } else {
                    if (sums != null) {
                        throw new IOException("the " + platform + " archive contains two SHA256SUMS");
                    }
                    sums = readEntry(stream, name, NativeManifest.MAX_MANIFEST_BYTES);
                }
            }
        }
        if (library == null || innerManifest == null || sums == null) {
            throw new IOException("the " + platform + " archive is missing "
                    + (library == null ? entry.library() + " " : "")
                    + (innerManifest == null ? INNER_MANIFEST + " " : "")
                    + (sums == null ? SUMS : ""));
        }
        return new Extracted(library, innerManifest, sums);
    }

    /** Reads one entry with a hard byte cap, so a bomb stops at the limit. */
    private static byte[] readEntry(ZipInputStream stream, String name, long limit) throws IOException {
        var buffer = new byte[8192];
        var out = new ByteArrayOutputStream();
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("entry '" + name + "' exceeds the " + limit + "-byte limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Cross-checks the three entries against each other and the manifest. */
    private static void verifyExtracted(String platform, NativeManifest.Artifact entry, Extracted extracted)
            throws IOException {
        var libraryDigest = HexFormat.of().formatHex(
                newSha256().digest(extracted.library()));
        if (!libraryDigest.equals(entry.librarySha256())) {
            throw new IOException("the library inside the archive hashes to " + libraryDigest
                    + " but the manifest declares " + entry.librarySha256());
        }

        final JsonObject inner;
        try {
            var parsed = JsonParser.parseString(new String(extracted.innerManifest(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("the inner manifest is not a JSON object");
            }
            inner = parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("the inner manifest is not valid JSON: " + e.getMessage(), e);
        }
        requireField(inner, "module", entry.module());
        requireField(inner, "platform", platform);
        requireField(inner, "library", entry.library());
        requireField(inner, "sha256", entry.librarySha256());
        if (!inner.has("abiVersion") || inner.get("abiVersion").getAsInt() != NativeLoader.EXPECTED_ABI_VERSION) {
            throw new IOException("the inner manifest does not declare ABI "
                    + NativeLoader.EXPECTED_ABI_VERSION);
        }
        if (!inner.has("version") || !entry.nativeVersion().equals(inner.get("version").getAsString())) {
            throw new IOException("the inner manifest declares version "
                    + (inner.has("version") ? inner.get("version").getAsString() : "<none>")
                    + " but the signed manifest says " + entry.nativeVersion());
        }

        var innerManifestDigest = HexFormat.of().formatHex(
                newSha256().digest(extracted.innerManifest()));
        var signed = parseSums(extracted.sums());
        var libraryClaim = signed.get(entry.library());
        if (libraryClaim == null || !libraryClaim.equals(libraryDigest)) {
            throw new IOException("SHA256SUMS does not sign " + entry.library() + " with its real digest");
        }
        var manifestClaim = signed.get(INNER_MANIFEST);
        if (manifestClaim == null || !manifestClaim.equals(innerManifestDigest)) {
            throw new IOException("SHA256SUMS does not sign " + INNER_MANIFEST + " with its real digest");
        }
        if (signed.size() != 2) {
            throw new IOException("SHA256SUMS signs " + signed.size() + " files, expected exactly 2");
        }
    }

    private static void requireField(JsonObject object, String name, String expected) throws IOException {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()
                || !expected.equals(object.get(name).getAsString())) {
            throw new IOException("the inner manifest's '" + name + "' does not match the signed manifest");
        }
    }

    /** {@code <sha256>  <name>} per line, the format {@code packageNative} writes. */
    private static java.util.Map<String, String> parseSums(byte[] sums) throws IOException {
        var result = new java.util.LinkedHashMap<String, String>();
        for (var line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var fields = trimmed.split("\\s+", 2);
            if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")) {
                throw new IOException("malformed SHA256SUMS line: '" + trimmed + "'");
            }
            if (result.put(fields[1], fields[0]) != null) {
                throw new IOException("SHA256SUMS lists " + fields[1] + " twice");
            }
        }
        if (result.isEmpty()) {
            throw new IOException("SHA256SUMS is empty");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Installation record
    // ------------------------------------------------------------------

    private record InstallRecord(
            String module, String platform, String sha256, String nativeVersion, int abiVersion) {}

    private static InstallRecord readRecord(Path installDir, String module) throws IOException {
        var file = installDir.resolve(installRecordName(module));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            var parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("the installation record is not a JSON object");
            }
            var object = parsed.getAsJsonObject();
            return new InstallRecord(
                    object.get("module").getAsString(),
                    object.get("platform").getAsString(),
                    object.get("sha256").getAsString(),
                    object.get("nativeVersion").getAsString(),
                    object.get("abiVersion").getAsInt());
        } catch (com.google.gson.JsonParseException | IllegalStateException | NullPointerException e) {
            throw new IOException("the installation record is malformed: " + e.getMessage(), e);
        }
    }

    /**
     * Writes the installation record for a library that is already in place and
     * already matches the published digest, so a later run can verify it
     * without downloading it again.
     */
    static void record(Path installDir, String platform, NativeManifest.Artifact entry,
            String librarySha256) throws IOException {
        Files.createDirectories(installDir);
        writeRecord(installDir, platform, entry, librarySha256);
    }

    private static void writeRecord(Path installDir, String platform, NativeManifest.Artifact entry,
            String librarySha256) throws IOException {
        var object = new JsonObject();
        object.addProperty("schemaVersion", 1);
        object.addProperty("module", entry.module());
        object.addProperty("platform", platform);
        object.addProperty("library", entry.library());
        object.addProperty("sha256", librarySha256);
        object.addProperty("nativeVersion", entry.nativeVersion());
        object.addProperty("abiVersion", entry.abiVersion());
        object.addProperty("asset", entry.asset());
        object.addProperty("installedAt", Instant.now().toString());
        var recordName = installRecordName(entry.module());
        var target = installDir.resolve(recordName);
        var staging = installDir.resolve(recordName + ".part");
        try {
            Files.writeString(staging, object.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            moveAtomically(staging, target);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static String installRecordName(String module) {
        return module.equals(NativeLoader.DEFAULT_MODULE_NAME)
                ? INSTALL_RECORD
                : module + ".install.json";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            // Some file systems refuse to replace within ATOMIC_MOVE; the
            // delete-and-retry window only ever holds for the record or a
            // library this process just verified.
            Files.deleteIfExists(to);
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    static String sha256(Path file) throws IOException {
        var digest = newSha256();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) throws IOException {
        return HexFormat.of().formatHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable: " + e.getMessage(), e);
        }
    }
}
