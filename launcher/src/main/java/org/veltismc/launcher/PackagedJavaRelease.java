package org.veltismc.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The minimum Java release this jar's classes were compiled for.
 *
 * <p>Written by the build from the same {@code veltisJavaRelease} the compiler
 * targets, packaged as {@value RESOURCE}, and read here so the launcher asks
 * the runtime layer for a workspace built for exactly that release. It is
 * deliberately not derived from the running JVM: the bytecode release is a
 * property of the jar that was built, not of the JVM that is reading it, and a
 * workspace prepared on Java 25 must be reusable from Java 26 without looking
 * like a different build.
 *
 * <p>{@link JavaVersionGuard} reads the same file, so the entry point and the
 * rest of the launcher cannot disagree about what is required — each parses it
 * without sharing code because the guard is compiled to Java 8 and this class
 * can use anything the launcher module targets.
 */
final class PackagedJavaRelease {

    /** Packaged by the build from {@code veltisJavaRelease}. */
    static final String RESOURCE = "META-INF/veltis/java-version.txt";

    /** Only used when the resource is missing: Minecraft 26.3's own release. */
    static final int FALLBACK_RELEASE = 25;

    private PackagedJavaRelease() {
    }

    /** The minimum Java feature release the packaged classes were compiled for. */
    static int minimum() {
        try (InputStream in = PackagedJavaRelease.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return FALLBACK_RELEASE;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int digits = 0;
            while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
                digits++;
            }
            return digits == 0 ? FALLBACK_RELEASE : Integer.parseInt(text.substring(0, digits));
        } catch (IOException | NumberFormatException e) {
            return FALLBACK_RELEASE;
        }
    }
}