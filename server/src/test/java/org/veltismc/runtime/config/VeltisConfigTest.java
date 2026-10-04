package org.veltismc.runtime.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VeltisConfig} writes its configuration files on first load and
 * maps their kebab-case keys onto the configuration objects.
 */
class VeltisConfigTest {

    @TempDir
    Path home;

    @BeforeEach
    void resetToDefaults() {
        // Every test starts from the defaults written into its own temp home.
        VeltisConfig.load(home);
    }

    @Test
    void firstLoadWritesBothConfigFiles() {
        assertTrue(Files.exists(home.resolve("config").resolve("veltis-global.yml")));
        assertTrue(Files.exists(home.resolve("config").resolve("veltis-world-defaults.yml")));
    }

    @Test
    void defaultsAreAppliedWhenNoFileExists() {
        assertEquals(31, VeltisConfig.global()._version);
        assertTrue(VeltisConfig.global().misc.enableNether);
        assertEquals(5, VeltisConfig.global().misc.maxJoinsPerTick);
        assertFalse(VeltisConfig.worldDefaults().anticheat.antiXray.enabled);
        assertEquals(24, VeltisConfig.worldDefaults().chunks.maxAutoSaveChunksPerTick);
    }

    @Test
    void writtenDefaultsRoundTripThroughLoad() throws Exception {
        var globalFile = home.resolve("config").resolve("veltis-global.yml");
        var yaml = Files.readString(globalFile);

        // Kebab-case keys, defaults written out and readable back unchanged.
        assertTrue(yaml.contains("max-joins-per-tick: 5"), yaml);
        assertTrue(yaml.contains("enable-nether: true"), yaml);

        VeltisConfig.load(home);
        assertEquals(5, VeltisConfig.global().misc.maxJoinsPerTick);
    }

    @Test
    void nestedScalarSectionsAreApplied() throws Exception {
        Files.writeString(home.resolve("config").resolve("veltis-global.yml"), """
            _version: 31
            chunk-loading-basic:
              player-max-chunk-send-rate: 42.5
            misc:
              enable-nether: false
              max-joins-per-tick: 12
            proxies:
              velocity:
                enabled: true
                secret: "s3cret"
            """);
        Files.writeString(home.resolve("config").resolve("veltis-world-defaults.yml"), """
            _version: 31
            chunks:
              max-auto-save-chunks-per-tick: 5
              flush-regions-on-save: true
            anticheat:
              anti-xray:
                enabled: true
                engine-mode: 2
            """);

        VeltisConfig.load(home);

        var global = VeltisConfig.global();
        assertEquals(42.5, global.chunkLoadingBasic.playerMaxChunkSendRate);
        assertFalse(global.misc.enableNether);
        assertEquals(12, global.misc.maxJoinsPerTick);
        assertTrue(global.proxies.velocity.enabled);
        assertEquals("s3cret", global.proxies.velocity.secret);

        var world = VeltisConfig.worldDefaults();
        assertEquals(5, world.chunks.maxAutoSaveChunksPerTick);
        assertTrue(world.chunks.flushRegionsOnSave);
        assertTrue(world.anticheat.antiXray.enabled);
        assertEquals(2, world.anticheat.antiXray.engineMode);
    }

    @Test
    void mapTypedFieldsAcceptNestedYamlMaps() throws Exception {
        Files.writeString(home.resolve("config").resolve("veltis-world-defaults.yml"), """
            _version: 31
            tick-rates:
              sensor:
                villager:
                  secondarypoisensor: 99
            """);

        VeltisConfig.load(home);

        var sensor = VeltisConfig.worldDefaults().tickRates.sensor;
        assertEquals(99, sensor.get("villager").get("secondarypoisensor"));
    }

    @Test
    void worldDirectoryOverridesFallBackToWorldDefaults() throws Exception {
        var worldDir = home.resolve("world");
        Files.createDirectories(worldDir);
        Files.writeString(worldDir.resolve("veltis-world.yml"), """
            _version: 31
            chunks:
              max-auto-save-chunks-per-tick: 7
            """);

        assertEquals(7, VeltisConfig.worldConfig(worldDir).chunks.maxAutoSaveChunksPerTick);
        // No override file -> the shared defaults are returned.
        assertEquals(VeltisConfig.worldDefaults(),
            VeltisConfig.worldConfig(home.resolve("no-such-world")));
    }
}
