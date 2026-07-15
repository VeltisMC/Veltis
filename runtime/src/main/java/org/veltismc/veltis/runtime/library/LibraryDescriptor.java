package org.veltismc.veltis.runtime.library;

public record LibraryDescriptor(
    String name,
    String path,
    String url,
    String sha1,
    long size
) {
}


