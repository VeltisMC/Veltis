package org.veltismc.world.api;

import java.io.IOException;

/**
 * Codec SPI for (de)serializing {@link ChunkSnapshot} to/from bytes.
 * Compression is a codec property; swap codecs to enable future schemes.
 */
public interface ChunkDataCodec {

    Compression compression();

    byte[] encode(ChunkSnapshot snapshot) throws IOException;

    ChunkSnapshot decode(byte[] data) throws IOException;
}
