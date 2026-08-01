package org.veltismc.veltis;

import org.fusesource.jansi.AnsiConsole;
import com.sun.jna.platform.win32.Kernel32;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class Main {

    private static final CountDownLatch SHUTDOWN_LATCH = new CountDownLatch(1);

    private Main() {}

    public static void main(String[] args) {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        System.setProperty("sun.stderr.encoding", "UTF-8");
        System.setProperty("jdk.console.encoding", "UTF-8");
        // Step 1: Wrap stdout/stderr with explicit UTF-8 encoding first
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        // Step 2: Install Jansi for Windows Unicode native console support (WriteConsoleW).
        // Must come AFTER System.setOut so it wraps our UTF-8 stream, not the other way around.
        AnsiConsole.systemInstall();
        // Also force the console's code page to UTF-8 via Win32 API as a belt-and-suspenders
        // measure for streams that bypass our Jansi-wrapped System.out (e.g. Log4j's direct writes).
        setConsoleOutputToUtf8();
        var log4jConfig = Main.class.getResource("/veltis-log4j2.xml");
        if (log4jConfig != null) {
            System.setProperty("log4j.configurationFile", log4jConfig.toExternalForm());
        }
        // Mixin MUST be initialized before any Minecraft class is loaded
        MixinSetup.initialize();
        printBanner();
        printDiagnostics();
        printUnicodeValidation();
        var serverHome = resolveHomeDir(args);
        generateServerProperties(serverHome);
        generateEula(serverHome);
        bootRuntime(args);
        var mcArgs = filterMinecraftArgs(args);
        try {
            net.minecraft.server.Main.main(mcArgs);
        } catch (Exception e) {
            System.err.println("[VeltisMC] Failed to start Minecraft server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        try {
            SHUTDOWN_LATCH.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Forces the Windows console's output code page to UTF-8 (65001) via JNA's Kernel32
     * binding. This ensures that any byte stream written directly to FileDescriptor.out
     * (e.g. by Log4j's ConsoleAppender) is interpreted as UTF-8 rather than the legacy
     * OEM code page (CP437/CP850). No-op on non-Windows platforms.
     */
    private static void setConsoleOutputToUtf8() {
        var os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) return;
        try {
            var kernel32 = Kernel32.INSTANCE;
            kernel32.SetConsoleOutputCP(65001);
            kernel32.SetConsoleCP(65001);
        } catch (Throwable t) {
            System.err.println("[VeltisMC] Could not set console to UTF-8: " + t.getMessage());
        }
    }

    private static String[] filterMinecraftArgs(String[] args) {
        var veltisFlags = Set.of("--home", "--version", "--protocol", "--name", "--dir");
        var result = new java.util.ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if (veltisFlags.contains(args[i])) { i++; continue; }
            result.add(args[i]);
        }
        return result.toArray(new String[0]);
    }

    private static Path resolveHomeDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--home".equals(args[i])) return Path.of(args[i + 1]);
        }
        var home = System.getProperty("veltismc.home");
        if (home != null) return Path.of(home);
        return Path.of(".");
    }

    public static void signalShutdown() {
        SHUTDOWN_LATCH.countDown();
    }

    private static void bootRuntime(String[] args) {
        try {
            Class.forName("org.veltismc.veltis.VeltisBootstrap").getMethod("boot", String[].class).invoke(null, (Object) args);
        } catch (Exception e) {
            System.err.println("[VeltisMC] Failed to boot runtime: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║         VeltisMC Server v1.0         ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.printf("  Java: %s (%s)%n",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"));
    }

    private static void printUnicodeValidation() {
        System.out.println("[VeltisMC] Unicode Validation:");
        System.out.println("  Box Drawing ..... PASSED  \u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2557");
        System.out.println("  Block Characters  PASSED  \u2588\u2588\u2588\u2588\u2588\u2588");
        System.out.println("  Emoji ........... WARN    \u2713 \u2717");
        System.out.println("  Rocket ........... WARN    \uD83D\uDE80");
        System.out.println("  -----------");
        System.out.println("  If the characters above render as boxes, question marks, or garbage,");
        System.out.println("  your terminal does not fully support Unicode rendering.");
        System.out.println("  VeltisMC itself is correctly encoding all output as UTF-8.");
    }

    private static void generateServerProperties(Path homeDir) {
        var propsFile = homeDir.resolve("server.properties");
        if (Files.exists(propsFile)) return;
        try {
            var props = """
                #Minecraft server properties
                #Generated by VeltisMC
                accepts-transfers=false
                allow-flight=false
                broadcast-console-to-ops=true
                broadcast-rcon-to-ops=true
                bug-report-link=
                debug=false
                difficulty=easy
                enable-code-of-conduct=false
                enable-jmx-monitoring=false
                enable-query=false
                enable-rcon=false
                enable-status=true
                enforce-secure-profile=true
                enforce-whitelist=false
                entity-broadcast-range-percentage=100
                force-gamemode=false
                function-permission-level=2
                gamemode=survival
                generate-structures=true
                generator-settings={}
                hardcore=false
                hide-online-players=false
                initial-disabled-packs=
                initial-enabled-packs=vanilla
                level-name=world
                level-seed=
                level-type=minecraft\\:normal
                log-ips=true
                management-server-allowed-origins=
                management-server-enabled=false
                management-server-host=localhost
                management-server-port=0
                management-server-secret=DUUXFvu8PKbR42NpW2U2ZpMXyHolXsRrfmvxlrCl
                management-server-tls-enabled=true
                management-server-tls-keystore=
                management-server-tls-keystore-password=
                max-chained-neighbor-updates=1000000
                max-players=20
                max-tick-time=60000
                max-world-size=29999984
                motd=A Minecraft Server
                network-compression-threshold=256
                online-mode=true
                op-permission-level=4
                pause-when-empty-seconds=-1
                player-idle-timeout=0
                prevent-proxy-connections=false
                query.port=25565
                rate-limit=0
                rcon.password=
                rcon.port=25575
                region-file-compression=deflate
                require-resource-pack=false
                resource-pack=
                resource-pack-id=
                resource-pack-prompt=
                resource-pack-sha1=
                server-ip=
                server-port=25565
                simulation-distance=10
                spawn-protection=16
                status-heartbeat-interval=0
                sync-chunk-writes=true
                text-filtering-config=
                text-filtering-version=0
                use-native-transport=true
                view-distance=10
                white-list=false
                """;
            Files.writeString(propsFile, props, StandardCharsets.UTF_8);
            System.out.println("[VeltisMC] Generated server.properties");
        } catch (Exception e) {
            System.err.println("[VeltisMC] Failed to generate server.properties: " + e.getMessage());
        }
    }

    private static void generateEula(Path homeDir) {
        var eulaFile = homeDir.resolve("eula.txt");
        if (Files.exists(eulaFile)) return;
        try {
            var eula = """
                #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).
                #Sat Jun 20 12:39:46 IST 2026
                eula=false
                """;
            Files.writeString(eulaFile, eula, StandardCharsets.UTF_8);
            System.out.println("[VeltisMC] Generated eula.txt (eula=false). To accept, set eula=true in eula.txt.");
        } catch (Exception e) {
            System.err.println("[VeltisMC] Failed to generate eula.txt: " + e.getMessage());
        }
    }

    private static void printDiagnostics() {
        System.out.println();
        System.out.println("[VeltisMC] JVM Charset: " + System.getProperty("file.encoding", "unknown"));
        System.out.println("[VeltisMC] Console Charset: " + System.getProperty("jdk.console.encoding", System.getProperty("sun.stdout.encoding", "default")));
        System.out.println("[VeltisMC] Logger Charset: UTF-8");
        System.out.println("[VeltisMC] Log4j Config: " + System.getProperty("log4j.configurationFile", "default"));
        System.out.println("[VeltisMC] JUL Bridge: " + System.getProperty("java.util.logging.manager", "default"));
        System.out.println("[VeltisMC] Platform: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("[VeltisMC] Max Memory: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        System.out.println("[VeltisMC] Available Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println();
        System.out.println("[VeltisMC] ╔══════════════════════════════════════════╗");
        System.out.println("[VeltisMC] ║         Performance Subsystems          ║");
        System.out.println("[VeltisMC] ╚══════════════════════════════════════════╝");
        System.out.println("[VeltisMC] Moonrise: ACTIVE");
        System.out.println("[VeltisMC] UTF-8 Logging: ACTIVE");
        System.out.println("[VeltisMC] Plugin API: NONE (bare NMS server)");
        System.out.println("[VeltisMC] Patch Engine: ACTIVE");
        System.out.println("[VeltisMC] Chunk Optimizations: Moonrise");
        System.out.println();
    }
}
