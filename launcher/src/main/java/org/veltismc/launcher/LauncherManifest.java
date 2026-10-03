package org.veltismc.launcher;

import org.veltismc.patchengine.PatchCategory;
import org.veltismc.patchengine.PatchDiscovery;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LauncherManifest {

    private final Path homeDirectory;
    private final Path workspaceBase;
    private final Path patchesRoot;
    private final Map<String, String> flags;

    private LauncherManifest(Path homeDirectory, Path workspaceBase, Path patchesRoot,
                             Map<String, String> flags) {
        this.homeDirectory = homeDirectory;
        this.workspaceBase = workspaceBase;
        this.patchesRoot = patchesRoot;
        this.flags = Map.copyOf(flags);
    }

    public static LauncherManifest from(String[] args) {
        return from(args, VeltisLauncher.locateOwnJar());
    }

    /**
     * @param launcherDirectory the directory this jar was loaded from, used to
     *                          find a developer checkout's {@code patches/}
     */
    public static LauncherManifest from(String[] args, Path launcherDirectory) {
        var flags = parseFlags(args);
        var home = detectHomeDirectory(flags);
        return new LauncherManifest(home, detectWorkspace(flags, home),
            detectPatches(flags, launcherDirectory), flags);
    }

    public Path homeDirectory() {
        return homeDirectory;
    }

    /**
     * The server home the installation is laid out under: {@code Vanilla/} and
     * {@code Veltis/} are resolved from it, and so is the private build
     * directory the preparation runs in.
     */
    public Path workspaceBase() {
        return workspaceBase;
    }

    /** An explicit or adjacent patch directory, or {@code null} for the packaged set. */
    public Optional<Path> patchesRoot() {
        return Optional.ofNullable(patchesRoot);
    }

    public Optional<String> minecraftVersion() {
        return Optional.ofNullable(flags.get("version"));
    }

    /**
     * The patch worker count.
     *
     * <p>{@code --patch-workers} wins, then the {@code veltismc.patchWorkers}
     * system property, then a documented fallback of one thread per core less one.
     * It only changes how long a rebuild takes: the patcher is required to produce
     * identical output at any worker count, which is asserted at 1, 4 and 8.
     */
    public int patchWorkers() {
        var explicit = flag("patch-workers");
        if (explicit != null) {
            return Math.max(1, Integer.parseInt(explicit));
        }
        var property = System.getProperty("veltismc.patchWorkers");
        if (property != null) {
            return Math.max(1, Integer.parseInt(property));
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public boolean hasFlag(String key) {
        return flags.containsKey(key);
    }

    /**
     * The value of a {@code --key value} flag, or {@code null} when it was
     * absent or given without a value.
     */
    public String flag(String key) {
        var value = flags.get(key);
        return "true".equals(value) ? null : value;
    }

    /**
     * Server data directory: {@code --home <dir>} wins, then the
     * {@code veltismc.home} system property, then the working directory
     * (matching {@code VeltisBootstrap} and {@code Main}, which read the same
     * flag from the command line the launcher forwards).
     */
    private static Path detectHomeDirectory(Map<String, String> flags) {
        var customHome = flags.get("home");
        if (customHome != null) {
            return Paths.get(customHome).toAbsolutePath().normalize();
        }
        var property = System.getProperty("veltismc.home");
        if (property != null) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    /**
     * Where the installation is laid out.
     *
     * <p>Defaults to the server home, which is the same directory the world, the
     * config and the logs live in. That single rule is what makes the build and
     * the server agree on one installation rather than two competing caches: a
     * developer who runs {@code ./gradlew prepareVeltisRuntime} and then starts
     * the jar from the project root works against the same {@code build/minecraft/}
     * tree the build wrote, and an operator who drops the jar into an empty
     * directory gets a self-contained {@code Vanilla/} and {@code Veltis/} there.
     * The override exists for CI, where several builds run against one checkout
     * and must not share state.
     *
     * <p>Nothing about the intermediate work tree derives from this beyond a hash
     * of the path: it lives under the system temporary directory, so no run can
     * leave anything behind in the server home no matter how it ends.
     */
    private static Path detectWorkspace(Map<String, String> flags, Path home) {
        var override = flags.get("workspace");
        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        var property = System.getProperty("veltismc.workspace");
        if (property != null) {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        return home;
    }

    /**
     * The patch set to build the runtime from.
     *
     * <p>An explicit {@code --patches} wins, then a {@code patches/} directory
     * beside the jar. The second rule is what gives a developer a live editing
     * loop: edit a patch, restart the server, and it is your patch that gets
     * applied, with no repackaging step. When neither is present — an operator who
     * has only {@code server.jar} — the patch set packaged inside the jar is used,
     * which is the same one the build would have produced.
     */
    private static Path detectPatches(Map<String, String> flags, Path launcherDirectory) {
        var override = flags.get("patches");
        if (override != null) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        if (isPatchDirectory(launcherDirectory.resolve(PatchDiscovery.PATCHES_DIRECTORY))) {
            return launcherDirectory.resolve(PatchDiscovery.PATCHES_DIRECTORY);
        }
        return null;
    }

    private static boolean isPatchDirectory(Path path) {
        for (var category : PatchCategory.values()) {
            if (java.nio.file.Files.isDirectory(path.resolve(category.directoryName()))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> parseFlags(String[] args) {
        var map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var key = arg.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    map.put(key, args[++i]);
                } else {
                    map.put(key, "true");
                }
            }
        }
        return map;
    }
}


