package org.veltismc.nativeengine;

/**
 * Shared names and limits for the commit-pinned native manifest.
 *
 * <p>The manifest format itself is parsed by {@link NativeCommitManifest}. It is
 * fetched over the distribution transport's trusted HTTPS GitHub release
 * source, and the integrity of each platform archive is established by the
 * SHA-256 digest the manifest records for it.
 *
 * <p>There is no detached signature: SHA-256 proves the archive matches the
 * manifest that was served, while authenticity of the manifest rests on the
 * HTTPS transport and the official repository configuration described in
 * {@link NativeDistributionTransport}. Treating the checksum as a signature
 * would overstate what it proves.
 */
final class NativeManifest {

    /** The manifest file the release publishes, and the name the cache keeps. */
    static final String MANIFEST_ASSET = "native-manifest.json";

    /**
     * A manifest larger than this is refused before it is parsed. The real
     * document is a few kilobytes; the limit exists so a hostile or looping
     * endpoint cannot turn "read the manifest" into an unbounded allocation.
     */
    static final long MAX_MANIFEST_BYTES = 1024L * 1024L;

    /**
     * The largest archive this runtime will read. The published libraries are
     * well under a megabyte; 64 MiB is headroom measured in orders of
     * magnitude, and it bounds both the download and the in-memory extraction.
     */
    static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;

    private NativeManifest() {}
}
