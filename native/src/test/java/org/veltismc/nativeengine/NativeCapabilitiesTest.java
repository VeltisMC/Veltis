package org.veltismc.nativeengine;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCapabilitiesTest {

    @Test
    void decodesKnownBitsAndIgnoresUnknownOnes() {
        var mask = NativeCapabilities.TRANSFORM_I32.mask()
                | NativeCapabilities.SELF_TEST.mask()
                | (1 << 20);

        assertEquals(
                EnumSet.of(NativeCapabilities.TRANSFORM_I32, NativeCapabilities.SELF_TEST),
                NativeCapabilities.fromMask(mask));
    }

    @Test
    void maskRoundTrips() {
        var capabilities = EnumSet.of(NativeCapabilities.TRANSFORM_I32);
        assertEquals(1, NativeCapabilities.toMask(capabilities));
        assertEquals(capabilities, NativeCapabilities.fromMask(NativeCapabilities.toMask(capabilities)));
    }

    @Test
    void emptyMaskHasNoCapabilities() {
        assertTrue(NativeCapabilities.fromMask(0).isEmpty());
    }

    @Test
    void statusExposesCapabilitiesAndFallbackState() {
        var available = NativeStatus.available(
                1, "1.0.0", EnumSet.of(NativeCapabilities.TRANSFORM_I32));
        assertTrue(available.available());
        assertFalse(available.fallbackActive());
        assertTrue(available.supports(NativeCapabilities.TRANSFORM_I32));
        assertFalse(available.supports(NativeCapabilities.SELF_TEST));
        assertTrue(available.summary().contains("1.0.0"));

        var unavailable = NativeStatus.unavailable(true, 1, "1.0.0", "ABI mismatch");
        assertFalse(unavailable.available());
        assertTrue(unavailable.loaded());
        assertTrue(unavailable.fallbackActive());
        assertTrue(unavailable.summary().contains("ABI mismatch"));
    }
}
