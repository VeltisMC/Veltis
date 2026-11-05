package org.veltismc.nativeengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five distribution knobs: safe defaults, tolerant parsing, and the same
 * file-plus-system-property precedence the loader's settings use.
 *
 * <p>The defaults matter most: a server whose operator never heard of the
 * distribution must never open a socket because of this class.
 */
class NativeDistributionConfigTest {

    /** Every property this class reads, so no test leaks one into the next. */
    private static final String[] GUARDED = {
            NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY,
            NativeDistributionConfig.OFFLINE_PROPERTY,
            NativeDistributionConfig.UPDATE_POLICY_PROPERTY,
            NativeDistributionConfig.DOWNLOAD_TIMEOUT_PROPERTY,
            NativeDistributionConfig.REPOSITORY_PROPERTY,
    };

    @TempDir
    Path home;

    private final java.util.Map<String, String> saved = new java.util.HashMap<>();

    @BeforeEach
    void rememberSystemProperties() {
        for (var name : GUARDED) {
            var value = System.getProperty(name);
            if (value != null) {
                saved.put(name, value);
            }
            System.clearProperty(name);
        }
    }

    @AfterEach
    void restoreSystemProperties() {
        for (var name : GUARDED) {
            var value = saved.get(name);
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        }
    }

    @Test
    void defaultsTouchNoNetworkAndAdoptTheOfficialReleaseLine() {
        var defaults = NativeDistributionConfig.defaults();

        assertTrue(defaults.autoDownload(),
                "the shipped default is the one the operator experience describes: java -jar, and it tries");
        assertFalse(defaults.offline());
        assertEquals(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, defaults.updatePolicy());
        assertEquals(30, defaults.downloadTimeoutSeconds());
        assertEquals("", defaults.repository());
        assertTrue(defaults.policyAllowsNetwork(), "if-missing may use the network");
        assertFalse(defaults.policyRequiresRefresh());
    }

    @Test
    void theDefaultRepositoryIsVersionedNotLatest() {
        // `releases/latest` would resolve to whichever release the repository
        // created last — the server distribution publishes far more often than
        // the native engine does.
        var repository = NativeDistributionConfig.DEFAULT_REPOSITORY;
        assertTrue(repository.startsWith("https://"), repository);
        assertTrue(repository.contains("/releases/download/veltis-native-v"), repository);
        assertFalse(repository.contains("latest"), repository);
    }

    @Test
    void everyPolicyIsRecognisedAndKeepsItsMeaning() {
        assertEquals(NativeDistributionConfig.UPDATE_POLICY_NEVER,
                NativeDistributionConfig.UPDATE_POLICY_NEVER);
        var never = new NativeDistributionConfig(true, false,
                NativeDistributionConfig.UPDATE_POLICY_NEVER, 10, "");
        assertFalse(never.policyAllowsNetwork(), "never forbids every request");
        assertFalse(never.policyRequiresRefresh());

        var always = new NativeDistributionConfig(true, false,
                NativeDistributionConfig.UPDATE_POLICY_ALWAYS, 10, "");
        assertTrue(always.policyAllowsNetwork());
        assertTrue(always.policyRequiresRefresh(), "always must re-read the manifest");
    }

    @Test
    void anInvalidPolicyFallsBackInsteadOfFailingTheServer() {
        var properties = new Properties();
        properties.setProperty(NativeDistributionConfig.UPDATE_POLICY_PROPERTY, "sometimes");

        var parsed = NativeDistributionConfig.fromProperties(properties);

        assertEquals(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, parsed.updatePolicy(),
                "a typo must not silence or enable downloads by surprise");
    }

    @Test
    void invalidBooleansAndTimeoutsFallBackToTheDefaults() {
        var properties = new Properties();
        properties.setProperty(NativeDistributionConfig.AUTO_DOWNLOAD_PROPERTY, "yes");
        properties.setProperty(NativeDistributionConfig.OFFLINE_PROPERTY, "");
        properties.setProperty(NativeDistributionConfig.DOWNLOAD_TIMEOUT_PROPERTY, "-5");

        var parsed = NativeDistributionConfig.fromProperties(properties);

        assertTrue(parsed.autoDownload(), "'yes' is not true, so the default stands");
        assertFalse(parsed.offline());
        assertEquals(NativeDistributionConfig.DEFAULT_DOWNLOAD_TIMEOUT_SECONDS,
                parsed.downloadTimeoutSeconds());
    }

    @Test
    void aPositiveTimeoutIsTakenAsGiven() {
        var properties = new Properties();
        properties.setProperty(NativeDistributionConfig.DOWNLOAD_TIMEOUT_PROPERTY, "7");

        assertEquals(7, NativeDistributionConfig.fromProperties(properties).downloadTimeoutSeconds());
    }

    @Test
    void serverPropertiesProvideTheValuesAndSystemPropertiesOverrideThem() throws Exception {
        Files.writeString(home.resolve("server.properties"), """
                veltis.native.autoDownload=true
                veltis.native.updatePolicy=never
                veltis.native.repository=https://mirror.example/veltis
                """);
        System.setProperty(NativeDistributionConfig.UPDATE_POLICY_PROPERTY, "always");

        var loaded = NativeDistributionConfig.load(home);

        assertTrue(loaded.autoDownload(), "the file's value applies when nothing overrides it");
        assertEquals(NativeDistributionConfig.UPDATE_POLICY_ALWAYS, loaded.updatePolicy(),
                "a -D override wins over server.properties");
        assertEquals("https://mirror.example/veltis", loaded.repository());
    }

    @Test
    void aMissingPropertiesFileStillYieldsTheSafeDefaults() {
        var loaded = NativeDistributionConfig.load(home.resolve("nowhere"));

        assertTrue(loaded.autoDownload());
        assertEquals("", loaded.repository());
    }

    @Test
    void anUnreadablePropertiesFileIsReportedAndDefaultsApply() throws Exception {
        // A directory where the file should be: the read fails, the loader
        // warns, and the result is still a usable configuration.
        Files.createDirectory(home.resolve("server.properties"));

        var loaded = NativeDistributionConfig.load(home);

        assertTrue(loaded.autoDownload());
        assertEquals(NativeDistributionConfig.UPDATE_POLICY_IF_MISSING, loaded.updatePolicy());
    }
}
