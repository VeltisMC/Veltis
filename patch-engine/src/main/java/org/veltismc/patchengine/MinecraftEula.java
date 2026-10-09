package org.veltismc.patchengine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The Minecraft EULA gate: generate {@code eula.txt} if it is absent, and refuse
 * to run unless it says {@code eula=true}.
 *
 * <p>It lives beside {@link VeltisConsole} because the EULA refusal is one of
 * the raw bootstrap lines that class is about, and because it has to be callable
 * from the two places a server starts: {@code VeltisLauncher}, before it fetches
 * Mojang's artifacts and applies the patch set, and {@code server.Main}, before
 * it loads the runtime. Both compile against this module, so the decision and
 * its wording exist once.
 *
 * <h2>Ordering is the point</h2>
 * A hosting panel that has not accepted the EULA must be told so before the
 * first download and before any Minecraft class is touched. Checking after the
 * vanilla jar and the patch set have been fetched wastes the operator's
 * bandwidth and startup time on a server that is about to exit, so the gate is
 * deliberately front-loaded rather than left to vanilla's own check.
 *
 * <h2>Never auto-accept</h2>
 * A missing, empty, unreadable, or {@code eula=false} file is a refusal. This
 * code never writes {@code eula=true} and never rewrites an existing file; the
 * only file it creates is the default {@code eula=false} that gives the operator
 * something to edit.
 */
public final class MinecraftEula {

    /**
     * The refusal, verbatim. Hosting panels key off this sentence, and the
     * requirement is that it reads exactly as vanilla's does.
     */
    public static final String REFUSAL = "You need to agree to the EULA in order to"
        + " run the server. Go to eula.txt for more info.";

    private MinecraftEula() {
    }

    /**
     * Runs the whole gate: ensure there is an {@code eula.txt}, then refuse with
     * a non-zero exit when it does not agree.
     *
     * <p>Call this before any download or Minecraft initialisation. It never
     * returns on refusal, so a caller cannot accidentally continue into the
     * expensive path.
     */
    public static void require(Path homeDir) {
        generate(homeDir);
        if (!agreed(homeDir)) {
            // Raw console output, like the other bootstrap lines: the refusal is
            // the server's only answer, and it must not be buried in a log
            // pattern. Non-zero so panels and scripts detect the failure.
            VeltisConsole.bootstrap(REFUSAL);
            System.exit(1);
        }
    }

    /**
     * Creates a default {@code eula=false} file when the home directory has
     * none. Never modifies an existing file, so a developer's {@code eula=true}
     * survives every start.
     */
    public static void generate(Path homeDir) {
        var eulaFile = homeDir.resolve("eula.txt");
        if (Files.exists(eulaFile)) {
            return;
        }
        try {
            Files.createDirectories(homeDir);
            Files.writeString(eulaFile, """
                #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).
                eula=false
                """, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // An unwritable home directory means the refusal below, not a crash:
            // there is nothing useful to do with the error here.
        }
    }

    /**
     * Whether {@code eula.txt} explicitly agrees.
     *
     * <p>Case-insensitive, {@code #} comments and blank lines ignored. A missing
     * or unreadable file is a refusal rather than an acceptance: the only thing
     * that lets the server start is an explicit {@code eula=true}.
     */
    public static boolean agreed(Path homeDir) {
        var eulaFile = homeDir.resolve("eula.txt");
        if (!Files.isRegularFile(eulaFile)) {
            return false;
        }
        try {
            return Files.readAllLines(eulaFile, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("eula=true"));
        } catch (Exception e) {
            return false;
        }
    }
}
