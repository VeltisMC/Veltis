package org.veltismc.nativeengine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The operations a loaded native engine reports as available.
 *
 * <p>The masks are the ABI's {@code veltis_native_capability} bits. A capability
 * is advertised by the library, not assumed by Java: a build that has the symbol
 * for an operation but not the implementation can report a narrower set without
 * changing the ABI.
 */
public enum NativeCapabilities {

    /** Bulk deterministic transformation of an {@code int[]}. */
    TRANSFORM_I32(1, "bulk integer-array transform"),

    /** The library can run its own deterministic self-test. */
    SELF_TEST(1 << 1, "native self-test");

    private final int mask;
    private final String description;

    NativeCapabilities(int mask, String description) {
        this.mask = mask;
        this.description = description;
    }

    public int mask() {
        return mask;
    }

    public String description() {
        return description;
    }

    /**
     * Decodes a capability bit mask. Unknown bits are ignored so a newer library
     * with additional capabilities remains usable on an older Java side.
     */
    public static Set<NativeCapabilities> fromMask(int mask) {
        var supported = EnumSet.noneOf(NativeCapabilities.class);
        for (var capability : values()) {
            if ((mask & capability.mask) != 0) {
                supported.add(capability);
            }
        }
        return Collections.unmodifiableSet(supported);
    }

    public static int toMask(Set<NativeCapabilities> capabilities) {
        var mask = 0;
        for (var capability : capabilities) {
            mask |= capability.mask;
        }
        return mask;
    }
}
