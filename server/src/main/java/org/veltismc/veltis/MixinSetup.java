package org.veltismc.veltis;

import com.google.gson.JsonParser;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MixinSetup {

    private static boolean initialized = false;
    private static int totalMixins = 0;
    private static int failedMixins = 0;
    private static final List<String> FAILED_MIXIN_REPORTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private MixinSetup() {}

    public static synchronized void initialize() {
        if (initialized) {
            System.out.println("[VeltisMC] Moonrise: already initialized, skipping");
            return;
        }

        System.out.println("[VeltisMC] Moonrise: Initializing Mixin environment...");

        try {
            MixinBootstrap.init();
        } catch (Exception e) {
            System.err.println("[VeltisMC] Moonrise FATAL: MixinBootstrap.init() failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Mixin initialization failed", e);
        }

        // Set SERVER side so mixins with @env(SERVER) apply and environment detection is correct
        MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.SERVER);

        System.out.println("[VeltisMC] Moonrise: Loading moonrise.mixins.json");

        try {
            Mixins.addConfiguration("moonrise.mixins.json");
        } catch (Exception e) {
            System.err.println("[VeltisMC] Moonrise FATAL: Failed to load moonrise.mixins.json: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to load moonrise.mixins.json", e);
        }

        var configs = Mixins.getConfigs();
        totalMixins = 0;
        for (var config : configs) {
            System.out.println("[VeltisMC] Moonrise: Config loaded: " + config.getName());
            totalMixins += countMixinsInConfig(config.getName());
        }

        var env = MixinEnvironment.getCurrentEnvironment();
        System.out.println("[VeltisMC] Moonrise: " + totalMixins + " mixins registered | Environment: " + env.getSide());

        MixinAgent.wireTransformer();

        System.out.println("[VeltisMC] Moonrise: Mixin environment ready");
        initialized = true;
    }

    private static int countMixinsInConfig(String configName) {
        try (var is = MixinSetup.class.getClassLoader().getResourceAsStream(configName)) {
            if (is == null) return 0;
            var obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            var mixins = obj.getAsJsonArray("mixins");
            return mixins != null ? mixins.size() : 0;
        } catch (Exception e) {
            System.err.println("[VeltisMC] Moonrise WARNING: Could not count mixins in " + configName + ": " + e.getMessage());
            return 0;
        }
    }

    public static void reportMixinResult(String mixinClass, String targetClass, boolean success, String reason) {
        if (!success) {
            failedMixins++;
            var report = "  Failed mixin:\n    Class: " + mixinClass + "\n    Target: " + targetClass + "\n    Reason: " + reason;
            FAILED_MIXIN_REPORTS.add(report);
            System.err.println("[VeltisMC] Moonrise: " + report);
        }
    }

    public static void printMixinSummary() {
        int loaded = totalMixins - failedMixins;
        System.out.println("[VeltisMC] Moonrise: Loaded mixins: " + loaded + "/" + totalMixins);
        if (failedMixins > 0) {
            System.out.println("[VeltisMC] Moonrise: Failed mixins: " + failedMixins);
            for (var report : FAILED_MIXIN_REPORTS) {
                System.err.println("[VeltisMC] " + report);
            }
        } else {
            System.out.println("[VeltisMC] Moonrise: All mixins applied successfully");
        }
        System.out.println("[VeltisMC] Moonrise: Chunk system active");
        System.out.println("[VeltisMC] Moonrise: Scheduling system active");
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean hasAllMixinsApplied() {
        return failedMixins == 0;
    }
}
