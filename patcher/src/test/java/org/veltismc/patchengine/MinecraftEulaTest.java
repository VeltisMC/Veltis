package org.veltismc.patchengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared EULA gate.
 *
 * <p>{@link MinecraftEula#require} itself ends in {@code System.exit(1)}, so it
 * is exercised end to end by the packaged-server refusal test rather than here.
 * These tests pin the two pure halves the gate is built from: what counts as
 * agreement, and the fact that generation only ever writes {@code eula=false}
 * and never touches an existing file.
 */
class MinecraftEulaTest {

    @Test
    void missingFileIsARefusal(@TempDir Path home) {
        assertFalse(MinecraftEula.agreed(home));
    }

    @Test
    void explicitFalseIsARefusal(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("eula.txt"), "eula=false\n", StandardCharsets.UTF_8);
        assertFalse(MinecraftEula.agreed(home));
    }

    @Test
    void emptyFileIsARefusal(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("eula.txt"), "\n\n", StandardCharsets.UTF_8);
        assertFalse(MinecraftEula.agreed(home));
    }

    @Test
    void onlyTrueAgrees(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        assertTrue(MinecraftEula.agreed(home));
    }

    @Test
    void agreementIgnoresCaseCommentsAndBlankLines(@TempDir Path home) throws Exception {
        var content = """
            #By changing the setting below to TRUE you are indicating your agreement to our EULA.
            #Sat Jun 20 12:39:46 IST 2026

               EULA=TRUE
            """;
        Files.writeString(home.resolve("eula.txt"), content, StandardCharsets.UTF_8);
        assertTrue(MinecraftEula.agreed(home));
    }

    @Test
    void generateCreatesFalseWhenMissing(@TempDir Path home) throws Exception {
        MinecraftEula.generate(home);

        var eula = home.resolve("eula.txt");
        assertTrue(Files.isRegularFile(eula));
        assertFalse(MinecraftEula.agreed(home));
        var text = Files.readString(eula, StandardCharsets.UTF_8);
        assertTrue(text.contains("eula=false"), text);
        assertFalse(text.contains("eula=true"), text);
    }

    @Test
    void generateNeverOverwritesAnAgreement(@TempDir Path home) throws Exception {
        var eula = home.resolve("eula.txt");
        Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);

        MinecraftEula.generate(home);

        assertTrue(MinecraftEula.agreed(home), "generation must not clobber eula=true");
    }
}
