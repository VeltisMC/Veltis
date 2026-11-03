package org.veltismc.patchengine;

/**
 * Which entries of a jar carry a jar signature.
 *
 * <p>This lives in its own class because every question it answers is asked by
 * code that runs at install time, while the code that used to own it — {@link
 * AccessWidener} — exists to run ASM over a class file. The distributable ships
 * neither ASM nor a decompiler, so a helper reachable from the installed
 * runtime's verification step cannot sit on a class whose linking would need
 * them. One method, no dependencies, and a name that says what it is for.
 *
 * <p>Three callers have to agree on the answer: the widening step, which drops
 * these entries from a baseline it is about to rewrite; the bytecode patch
 * generator, which records them as explicit removals in the patch index rather
 * than as a rule the applier has to remember; and the runtime's guard, which
 * refuses to trust a packaged jar that still contains them. Two implementations
 * of the question could disagree, and a disagreement here is a
 * {@code SecurityException} several minutes into a start.
 */
final class JarSignatures {

    private JarSignatures() {
    }

    /**
     * Whether an entry belongs to a jar signature.
     *
     * <p>A signed jar's entries are covered by digests in {@code MANIFEST.MF} and
     * {@code *.SF}, and the whole chain is sealed by {@code *.RSA}/{@code *.DSA}/
     * {@code *.EC}. Rewriting the classes invalidates every one of those digests,
     * and the JDK does not merely warn: {@code JarFile} routes the read through
     * {@code JarVerifier} and throws
     * {@code SecurityException: SHA-384 digest error for <entry>} on the first
     * class the loader asks for. A server that reached that point would fail on a
     * Minecraft class, several minutes into a start, naming neither the step that
     * rewrote the jar nor the cause.
     *
     * @param name a zip entry name, in the form the archive reports it
     * @return true when the entry is signature material that must not survive a
     *         rewrite
     */
    static boolean isSignatureEntry(String name) {
        if (!name.regionMatches(true, 0, "META-INF/", 0, "META-INF/".length())) {
            return false;
        }
        var rest = name.substring("META-INF/".length());
        if (rest.length() < 3) {
            // "META-INF/" alone, or a two-character name: the suffix tests below
            // would compare against a negative start and match everything.
            return false;
        }
        if (rest.regionMatches(true, 0, "SIG-", 0, "SIG-".length())) {
            return true;
        }
        return rest.regionMatches(true, rest.length() - 3, ".SF", 0, 3)
            || rest.regionMatches(true, rest.length() - 4, ".RSA", 0, 4)
            || rest.regionMatches(true, rest.length() - 4, ".DSA", 0, 4)
            || rest.regionMatches(true, rest.length() - 3, ".EC", 0, 3)
            || rest.regionMatches(true, rest.length() - 4, ".SIG", 0, 4);
    }
}
