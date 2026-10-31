package org.veltismc.nativeengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeConfigTest {

    @TempDir
    Path home;

    @Test
    void defaultsAttemptLoadAndFallback() {
        var config = NativeConfig.defaults();
        assertTrue(config.enabled());
        assertTrue(config.autoload());
        assertTrue(config.fallback());
        assertFalse(config.stats());
        assertFalse(config.hasLibraryPath());
    }

    @Test
    void readsEverySupportedProperty() {
        var properties = new Properties();
        properties.setProperty(NativeConfig.ENABLED_PROPERTY, "false");
        properties.setProperty(NativeConfig.AUTOLOAD_PROPERTY, "false");
        properties.setProperty(NativeConfig.FALLBACK_PROPERTY, "false");
        properties.setProperty(NativeConfig.STATS_PROPERTY, "true");
        properties.setProperty(NativeConfig.PATH_PROPERTY, "  /opt/veltis/libveltis_native.so  ");

        var config = NativeConfig.fromProperties(properties);

        assertFalse(config.enabled());
        assertFalse(config.autoload());
        assertFalse(config.fallback());
        assertTrue(config.stats());
        assertEquals("/opt/veltis/libveltis_native.so", config.libraryPath());
        assertTrue(config.hasLibraryPath());
    }

    @Test
    void invalidBooleansKeepTheirSafeDefault() {
        var properties = new Properties();
        properties.setProperty(NativeConfig.ENABLED_PROPERTY, "maybe");

        assertTrue(NativeConfig.fromProperties(properties).enabled());
    }

    @Test
    void loadsValuesFromServerProperties() throws Exception {
        Files.writeString(home.resolve("server.properties"), """
                motd=test
                veltis.native.enabled=false
                veltis.native.stats=true
                veltis.native.path=native/test/libveltis_native.so
                """);

        var config = NativeConfig.load(home);

        assertFalse(config.enabled());
        assertTrue(config.stats());
        assertEquals("native/test/libveltis_native.so", config.libraryPath());
    }

    @Test
    void systemPropertiesOverrideServerProperties() throws Exception {
        Files.writeString(home.resolve("server.properties"), """
                veltis.native.enabled=false
                veltis.native.stats=false
                """);
        var previous = System.getProperty(NativeConfig.ENABLED_PROPERTY);
        try {
            System.setProperty(NativeConfig.ENABLED_PROPERTY, "true");
            assertTrue(NativeConfig.load(home).enabled());
        } finally {
            restore(NativeConfig.ENABLED_PROPERTY, previous);
        }
    }

    private static void restore(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }
}
