package org.veltismc.launcher;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The first class the JVM loads out of {@code veltismc.jar}, and the one class
 * in it compiled to Java 8 bytecode.
 *
 * <p>Everything else targets the release the build records (25 for Minecraft
 * 26.3). On a runtime older than that the JVM cannot load {@link VeltisLauncher}
 * at all — it raises {@code UnsupportedClassVersionError} while resolving the
 * manifest's entry point, before a single line of VeltisMC code runs, and the
 * operator is left reading a class-file version number instead of an
 * instruction. A class-file-version check is therefore only useful if the class
 * doing the checking is itself loadable on the runtime being checked, which
 * means this file may use JDK 8 APIs and nothing newer.
 *
 * <p>It reads the minimum release the build wrote to
 * {@value #RESOURCE} — the same file the packaged classes were compiled
 * against — and either prints an actionable message and exits non-zero, or
 * delegates to {@link VeltisLauncher} by reflection. Reflection rather than a
 * direct call so that the Java-8 compiler never has to see the Java-25 class as
 * a compile-time dependency, which keeps the "only JDK 8 APIs" rule checkable:
 * this source set is compiled with an empty classpath.
 */
public final class JavaVersionGuard {

    /** Packaged by the build from {@code veltisJavaRelease}. */
    static final String RESOURCE = "/META-INF/veltis/java-version.txt";

    /**
     * Used only when the resource is missing — a jar assembled outside this
     * build. 25 is Minecraft 26.3's own class-file release, so refusing anything
     * older is still correct.
     */
    static final int FALLBACK_RELEASE = 25;

    static final String LAUNCHER = "org.veltismc.launcher.VeltisLauncher";

    private JavaVersionGuard() {
    }

    public static void main(String[] args) throws Exception {
        int required = requiredRelease();
        int running = runningFeature();
        if (running < required) {
            System.err.println(
                "VeltisMC requires Java " + required + " or newer"
                    + "\n  Running on: Java " + System.getProperty("java.version")
                    + " (" + System.getProperty("java.vendor") + ")"
                    + "\n  Reason: this server's classes are compiled for Java " + required
                    + " — Minecraft 26.3 and the VeltisMC runtime are both Java "
                    + required + " class files — and an older JVM cannot load them"
                    + "\n  Fix: start the server with a Java " + required + "+ runtime, e.g."
                    + "\n         \"<path-to-jdk-" + required + ">/bin/java\" -jar veltismc.jar"
                    + "\n       or set JAVA_HOME to that runtime first. No compiler or full"
                    + " JDK is needed at runtime; a Java " + required + "+ JRE is enough.");
            System.exit(1);
            return;
        }
        Class.forName(LAUNCHER)
            .getMethod("main", String[].class)
            .invoke(null, (Object) args);
    }

    /** The minimum release the packaged classes were compiled for. */
    private static int requiredRelease() {
        InputStream in = null;
        try {
            in = JavaVersionGuard.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                return FALLBACK_RELEASE;
            }
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            if (read <= 0) {
                return FALLBACK_RELEASE;
            }
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
            int digits = 0;
            while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
                digits++;
            }
            return digits == 0 ? FALLBACK_RELEASE : Integer.parseInt(text.substring(0, digits));
        } catch (Exception e) {
            return FALLBACK_RELEASE;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Reading a classpath resource we are about to discard.
                }
            }
        }
    }

    /**
     * The running feature release.
     *
     * <p>{@code java.specification.version} rather than {@code Runtime.version()}:
     * the latter is Java 9+, and this class has to run on Java 8 to be able to
     * reject it. The value is "1.8" through Java 8 and "9", "10", ... afterwards.
     * An unparseable value is treated as supported rather than blocking a start
     * on a runtime that is probably fine.
     */
    private static int runningFeature() {
        String spec = System.getProperty("java.specification.version");
        try {
            if (spec != null) {
                String value = spec.startsWith("1.") ? spec.substring(2) : spec;
                int digits = 0;
                while (digits < value.length() && Character.isDigit(value.charAt(digits))) {
                    digits++;
                }
                if (digits > 0) {
                    return Integer.parseInt(value.substring(0, digits));
                }
            }
        } catch (Exception ignored) {
            // fall through to "unknown means allow"
        }
        return Integer.MAX_VALUE;
    }
}
