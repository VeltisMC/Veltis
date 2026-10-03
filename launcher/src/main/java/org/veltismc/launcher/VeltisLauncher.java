package org.veltismc.launcher;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.veltismc.patchengine.MinecraftVersion;
import org.veltismc.patchengine.VeltisConsole;
import org.veltismc.patchengine.VeltisRuntime;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the VeltisMC server, building its own runtime if it has to.
 *
 * <p>This is the whole of {@code java -jar server.jar}. There is no build step
 * in front of it and none behind it: on a machine with nothing cached, the
 * launcher resolves the Minecraft version against Mojang, downloads and
 * SHA-1 verifies the server artifact and its libraries, widens access, decompiles,
 * applies the Veltis patch set, compiles the patched sources, and only then loads
 * them. On a machine where that has already happened it does none of it.
 *
 * <p>The split from the build is one of scope, not of code. {@code ./gradlew
 * buildVeltisMC} runs {@link VeltisRuntime#prepare()} — the same call, against
 * the same workspace, in the same process shape — because a distribution and a CI
 * job need the artifacts up front and a developer needs the failure to land in
 * the build log. A server operator needs neither. Both then start the server from
 * one classpath assembled by one method.
 *
 * <h2>What the classpath contains, and why that is checked rather than trusted</h2>
 *
 * <p>Three kinds of entry and nothing else: {@code Veltis/<version>/
 * veltis-server.jar}, Mojang's libraries, and this jar so Minecraft's classes
 * can reach VeltisMC's own. There is no directory of loose class files and no
 * second copy of a vanilla class anywhere on the list — the artifact is
 * complete, and vanilla cannot win a contest it is not entered in.
 *
 * <p>"Cannot win" is still a claim about a file layout, and layouts change. So
 * {@link VeltisRuntime#verifyPatchedClasses} asks the live loader, before a
 * single Minecraft class is initialised, where it resolved each patched class
 * from, whether the bytes it will hand out are the ones recorded when the jar
 * was packaged, and whether those bytes are still different from the vanilla
 * class they replaced. A server that would run something other than the patched
 * runtime does not start; it reports why.
 *
 * <h2>The clock</h2>
 *
 * <p>{@link VeltisStartup} starts before this file does anything else, and the
 * patched {@code DedicatedServer} reads it when it prints {@code Done}. That is
 * the only {@code Done} line, and the duration it prints is the whole launch —
 * download, decompile, patch, compile, package, load, prepare the world, run
 * Veltis's hooks — not the last twenty seconds of it.
 */
public final class VeltisLauncher {

    /**
     * Assigned in {@link #main} right after {@link VeltisConsole#configureLog4j()}:
     * a logger obtained earlier would initialize Log4j2 before its configuration
     * is in place, which is exactly what produced mixed output patterns before.
     */
    private static Logger LOG;

    private VeltisLauncher() {
    }

    /**
     * Report-and-exit for stage 0: this runs before any logging exists, so
     * stderr is the whole channel, and a stack trace is worth printing only
     * when asked for.
     */
    private static void fail(IllegalStateException e) {
        System.err.println(e.getMessage());
        if (Boolean.getBoolean("veltismc.debug")) {
            e.printStackTrace(System.err);
        }
        System.exit(1);
    }

    /**
     * Re-executes the server in a fresh JVM after stage 0 had to fetch, and
     * exits with the child's status.
     *
     * <p>The child gets this process's exact command line — every user JVM
     * option, in order — minus the stale {@code veltismc.parentElapsedNanos}
     * from a previous hop, plus the time this process has already spent, so the
     * single {@code Done} line still measures the whole launch from the first
     * instant. Stage 0 in the child finds the files present, verifies without
     * fetching, and does not restart again.
     *
     * <p>Nothing here logs: stage 0 runs before any logging exists.
     */
    private static void restartAfterFetch(String[] args) {
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        // Every JVM option this process was given, in order, minus the one
        // whose value describes an earlier process rather than the next one.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(a -> !a.startsWith(PARENT_ELAPSED_PREFIX))
            .forEach(command::add);
        // The child prints Done, but the clock started here (see
        // relaunchIfNeeded for why elapsed time is passed, not an instant).
        command.add(PARENT_ELAPSED_PREFIX + VeltisStartup.elapsedNanos());

        var launcherLocation = locateOwnJar();
        if (Files.isRegularFile(launcherLocation)) {
            command.add("-jar");
            command.add(launcherLocation.toString());
        } else {
            command.add("-cp");
            command.add(launcherLocation.toString());
            command.add(VeltisLauncher.class.getName());
        }
        for (var arg : args) {
            command.add(arg);
        }

        try {
            var child = new ProcessBuilder(command).inheritIO().start();
            System.exit(child.waitFor());
        } catch (Exception e) {
            System.err.println("[Veltis] Stage 0 fetched this jar's libraries but"
                + " could not restart the server"
                + "\n  Reason: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage())
                + "\n  Fix: start the server again; everything is on disk now, and"
                + " the next start verifies it without fetching");
            if (Boolean.getBoolean("veltismc.debug")) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        // Before anything, including before logging: this is the instant the
        // server's only Done line is measured from, and every millisecond spent
        // configuring output first would be a millisecond missing from it.
        VeltisStartup.begin();

        // Stage 0: this jar's own dependencies, on disk and verified, before a
        // single third-party class is named. Log4j is configured a few lines
        // down and Gson with it, and a Class-Path lookup that misses once is
        // missed for the life of the JVM — so the honest place to find out is
        // here, with a message that says which file, from where, and with what
        // checksum, rather than a ClassNotFoundException three steps later.
        // It is pure JDK on purpose: anything else would need what it is
        // fetching — and a fetch means this JVM cannot boot the server, so
        // whatever it touched is left behind by restarting with the files
        // already in place.
        boolean fetched;
        try {
            fetched = BootstrapLibraries.ensure();
        } catch (IllegalStateException e) {
            fail(e);
            return;
        }
        if (fetched) {
            // See BootstrapLibraries: the probes a fetch performs cache their
            // misses, and a cached miss outlives the files that would have
            // satisfied it. The child verifies without fetching and boots.
            restartAfterFetch(args);
            return;
        }

        var manifest = LauncherManifest.from(args);
        var homeDir = manifest.homeDirectory();

        // Decide about output before there is any. Whether this JVM is going to
        // replace itself is knowable from the command line and the working
        // directory alone, and it changes how output must be wired: a process
        // that only relays must not open logs/latest.log, because it outlives
        // its own start and would hold that file while the child boots.
        var relaunch = relaunchDecision(homeDir);
        if (relaunch.needed()) {
            VeltisConsole.configureLog4jForRelaunch();
        }

        // One logging system (Log4j2) and a UTF-8 console — before anything logs.
        // The Minecraft classpath carries Mojang's own log4j2.xml, which must
        // never win; that is what produced the mixed patterns and the
        // Queue/Listener/ServerGuiConsole/Tracy appender errors.
        VeltisConsole.configureLog4j();
        VeltisConsole.installConsole();
        LOG = LogManager.getLogger(VeltisLauncher.class);

        var verbose = manifest.hasFlag("verbose");
        if (verbose) {
            // Diagnostics (javac notes, decompiler chatter) are logged at
            // DEBUG; --verbose is the only way to see them.
            if (LogManager.getContext(false)
                    instanceof org.apache.logging.log4j.core.LoggerContext ctx) {
                ctx.getConfiguration().getRootLogger()
                    .setLevel(org.apache.logging.log4j.Level.DEBUG);
                ctx.updateLoggers();
            }
        }

        // One home-dir message, printed by whichever process is going to keep
        // running. The relaunching parent says nothing, because its output
        // never reaches the log file — and a line printed twice, once by a
        // process that is about to disappear, is worse than not printing it.
        if (!relaunch.needed()) {
            LOG.info("Server home: {}", homeDir.toAbsolutePath());
        }
        relaunchIfNeeded(args, relaunch);
        if (verbose) {
            printDiagnostics(homeDir, manifest);
        }

        var version = resolveVersion(manifest);

        // A patches/ directory beside this jar -- or an explicit --patches --
        // means someone wants the development loop: apply the source patches to
        // a decompiled tree, widen, compile, rebuild. None of that is here, and
        // it cannot be: the distributable deliberately carries no decompiler, no
        // javac and no source patch engine, because shipping them would turn
        // every start into a build.
        //
        // Refusing is the only honest answer. Carrying on with the packaged
        // patch set would start a server that quietly ignores the very patches
        // the user pointed at, which is the kind of wrongness that costs an hour
        // to notice.
        var sourcePatches = manifest.patchesRoot();
        if (sourcePatches.isPresent()) {
            LOG.error("[Veltis] This build carries no source patch pipeline"
                + "\n  Patches: " + sourcePatches.get()
                + "\n  Reason: a distributable ships Minecraft's changes as compiled"
                + " bytecode with both hashes on every entry, so it has no decompiler,"
                + " no compiler and no source patch engine to apply a patches/ directory"
                + " with. Those live in the Gradle build."
                + "\n  Fix: rebuild the runtime from your patches -- ./gradlew applyPatches,"
                + " edit build/minecraft/<version>/patched/, then ./gradlew rebuildPatches"
                + " and ./gradlew buildVeltisMC -- and run the jar that produces; or run"
                + " this jar from a directory with no patches/ beside it, which uses the"
                + " patch set packaged inside it.");
            System.exit(1);
            return;
        }

        var runtime = VeltisRuntime.fromPackagedPatches(manifest.workspaceBase(), version,
            VeltisLauncher.class.getClassLoader(), manifest.patchWorkers(),
            Runtime.version().feature());

        try {
            runtime.prepare();
        } catch (RuntimeException e) {
            // The engine's report is already complete: which stage, which patch,
            // which target, which reason. A stack trace on top of it buries the
            // part a user needs, so it is kept for the debugger unless asked for.
            LOG.error(e.getMessage());
            if (Boolean.getBoolean("veltismc.debug")) {
                e.printStackTrace(System.err);
            }
            System.exit(1);
            return;
        }

        try {
            // The one classpath, built by the runtime, in the order that puts the
            // compiled patched classes ahead of the vanilla jar holding the same
            // names. The build-time guard builds this identical loader to prove
            // the ordering is honoured before anything is packaged.
            var classLoader = runtime.newClassLoader(locateOwnJar());

            // Before a single Minecraft class is initialised, and before the
            // server entry point is even loaded.
            LOG.info("[VeltisGuard] {}", runtime.verifyPatchedClasses(classLoader));

            var mainClass = Class.forName("org.veltismc.server.Main", true, classLoader);
            var mainMethod = mainClass.getMethod("main", String[].class);

            var serverArgs = new ArrayList<String>();
            serverArgs.add("--home");
            serverArgs.add(homeDir.toAbsolutePath().toString());
            serverArgs.add("--version");
            serverArgs.add(version.toString());
            // Forward everything the user typed; only our normalized
            // --home/--version replace theirs. Veltis-only flags are stripped
            // by server.Main before vanilla's parser sees them.
            for (int i = 0; i < args.length; i++) {
                if ("--home".equals(args[i]) || "--version".equals(args[i])
                        || "--patches".equals(args[i]) || "--patch-workers".equals(args[i])
                        || "--workspace".equals(args[i])) {
                    i++;
                    continue;
                }
                serverArgs.add(args[i]);
            }

            Thread.currentThread().setContextClassLoader(classLoader);
            mainMethod.invoke(null, (Object) serverArgs.toArray(String[]::new));
            // Minecraft's main returns after a clean shutdown. Nothing is called
            // after this point on purpose: the exit code has to be the server's.
        } catch (Throwable e) {
            var cause = e instanceof InvocationTargetException ite && ite.getCause() != null
                ? ite.getCause() : e;
            LOG.error("Failed to start VeltisMC server", cause);
            System.exit(1);
        }
    }

    /**
     * The Minecraft version to build for.
     *
     * <p>{@code --version} wins, then the {@code veltismc.minecraftVersion}
     * property, then the version this jar was packaged against. The packaged
     * value is a generated resource rather than a constant in this file, so
     * bumping it is the same one-line {@code minecraftVersion} change the build
     * uses and there is no second place to forget.
     */
    private static MinecraftVersion resolveVersion(LauncherManifest manifest) {
        return MinecraftVersion.launcherDefault(manifest.minecraftVersion()
            .orElseGet(() -> System.getProperty("veltismc.minecraftVersion")));
    }

    /**
     * Environment dump for {@code --verbose} only: JVM, platform, memory and
     * logging wiring — nothing that belongs in normal startup output.
     */
    private static void printDiagnostics(Path homeDir, LauncherManifest manifest) {
        LOG.info("Java: {} ({}, {})",
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("os.arch"));
        LOG.info("OS: {} | Processors: {} | Max memory: {} MB",
            System.getProperty("os.name"),
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() / (1024 * 1024));
        LOG.info("Workspace: {} | Patch workers: {}",
            manifest.workspaceBase().toAbsolutePath(), manifest.patchWorkers());
        LOG.info("Patch set: packaged in this jar");
        LOG.info("Home: {}", homeDir.toAbsolutePath());
        LOG.info("Log4j config: {} | JUL bridge: {}",
            System.getProperty("log4j2.configurationFile", "default"),
            System.getProperty("java.util.logging.manager", "default"));
    }

    /**
     * JVM options VeltisMC's own dependencies need and a plain {@code java -jar}
     * does not supply.
     *
     * <p>JNA and JOML both reach into JDK internals. Without these the JVM
     * answers with two messages on stderr — restricted native access, and
     * {@code sun.misc.Unsafe::objectFieldOffset} — and because the JVM writes
     * them, nothing in the server can suppress them after the fact. Redirecting
     * stderr to hide them would hide real failures with them, so the only honest
     * fix is a JVM that was given the options in the first place.
     *
     * <p>The Unsafe option is gated on the running feature release because it
     * arrived in 23. An option an older JVM does not know is a startup failure,
     * which is strictly worse than the warning it was meant to remove.
     */
    private static final String[] REQUIRED_JVM_OPTIONS = {
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    };

    /** Marks a JVM that has already been handed {@link #REQUIRED_JVM_OPTIONS}. */
    private static final String JVM_FLAGS_APPLIED_PROPERTY = "veltismc.jvmFlagsApplied";

    /** The one system property whose value is meaningless across a JVM boundary. */
    private static final String PARENT_ELAPSED_PREFIX = "-Dveltismc.parentElapsedNanos=";

    private static final String RESTARTED_PROPERTY = "-Dveltismc.restarted=true";

    /**
     * The required options the JVM this code is running in was not given.
     *
     * <p>Read from the process's own input arguments rather than from system
     * properties, because that is the only place a user's {@code --add-opens},
     * {@code -Xmx} and agents appear verbatim — and knowing exactly what was
     * passed is what lets a relaunch carry all of it across unchanged instead of
     * reconstructing a partial command line.
     *
     * <p>An option the user already supplied, in any of its accepted forms, is
     * left alone. {@code --sun-misc-unsafe-memory-access=warn} is a deliberate
     * choice and is not overridden by this code's preference for {@code allow};
     * only an option nobody has mentioned is ever added.
     */
    private static List<String> missingJvmOptions() {
        if (Boolean.getBoolean(JVM_FLAGS_APPLIED_PROPERTY)) {
            return List.of();
        }
        var supplied = ManagementFactory.getRuntimeMXBean().getInputArguments();
        var feature = Runtime.version().feature();
        var missing = new ArrayList<String>(REQUIRED_JVM_OPTIONS.length);
        for (int i = 0; i < REQUIRED_JVM_OPTIONS.length; i++) {
            var option = REQUIRED_JVM_OPTIONS[i];
            if (i == 1 && feature < 23) {
                continue;
            }
            var present = supplied.stream()
                .anyMatch(a -> a.equals(option) || a.startsWith(option + "="));
            if (!present) {
                missing.add(option);
            }
        }
        return missing;
    }

    /**
     * What, if anything, forces this JVM to start a second one.
     *
     * @param missingOptions the required JVM options this process was not given
     * @param target         the working directory the server must run in
     * @param moveDirectory  whether the directory differs and still has to be
     *                       changed — {@code false} once
     *                       {@code veltismc.restarted} says the move happened
     */
    private record Relaunch(List<String> missingOptions, Path target, boolean moveDirectory) {
        boolean needed() {
            return !missingOptions.isEmpty() || moveDirectory;
        }
    }

    /**
     * Whether this JVM has to be replaced, decided from the process itself.
     *
     * <p>Deliberately side-effect free and cheap: {@link #main} calls it before
     * anything is logged, because the answer decides how logging is wired, and
     * then passes it on rather than working it out twice.
     */
    private static Relaunch relaunchDecision(Path homeDir) {
        var missingOptions = missingJvmOptions();

        var workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var target = homeDir.toAbsolutePath().normalize();
        var onWindows = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
        var sameDirectory = onWindows
            ? workingDir.toString().equalsIgnoreCase(target.toString())
            : workingDir.toString().equals(target.toString());
        var moveDirectory = !sameDirectory && !Boolean.getBoolean("veltismc.restarted");

        return new Relaunch(missingOptions, target, moveDirectory);
    }

    /**
     * Starts this jar again — once — when something about the current process
     * cannot be changed in place.
     *
     * <p>Two such things exist, and they share one respawn on purpose. Vanilla
     * Minecraft resolves its data (world/, eula.txt, server.properties, logs/)
     * against the process working directory, and java.nio pins that directory
     * when the JVM boots, so an in-process {@code --home} pointing somewhere
     * else can never take effect. And a JVM option that was not given at launch
     * cannot be added afterwards at all. Handling them separately would boot two
     * extra JVMs on a machine that needs both; handling them together boots one.
     *
     * <p>No-op when the working directory already is the home directory and the
     * JVM already has the options, which is the normal case once the flags are
     * on the command line.
     *
     * <p>What the child is started with is what the parent was started with,
     * argument for argument, in the same order — read from the JVM's own input
     * arguments so agents, memory settings and module options survive verbatim —
     * plus the options that were missing. The only thing dropped is the elapsed
     * time, which is replaced with the parent's current value so the single
     * {@code Done} line still measures the whole launch from the first instant.
     *
     * <p>Never returns: it exits with the child's status, so the work this
     * method exists to make possible runs in the child and nothing after the
     * call in {@link #main} belongs to the parent.
     */
    private static void relaunchIfNeeded(String[] args, Relaunch relaunch) {
        var missingOptions = relaunch.missingOptions();
        var target = relaunch.target();
        var needHomeDirectory = relaunch.moveDirectory();

        if (!relaunch.needed()) {
            return;
        }

        if (needHomeDirectory) {
            try {
                Files.createDirectories(target);
            } catch (IOException e) {
                LOG.error("Cannot create server directory {}", target, e);
                System.exit(1);
            }
            LOG.info("Working directory was {}; restarting in {} so server data stays together",
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(), target);
        }
        if (!missingOptions.isEmpty()) {
            LOG.info("Restarting with {} so the JVM that runs VeltisMC is the one that"
                + " accepts it", String.join(" ", missingOptions));
        }

        try {
            var command = new ArrayList<String>();
            command.add(ProcessHandle.current().info().command().orElse("java"));
            // Every JVM option this process was given, in order, minus the one
            // whose value describes this process rather than the next one.
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> !a.startsWith(PARENT_ELAPSED_PREFIX))
                .filter(a -> !a.equals(RESTARTED_PROPERTY))
                .filter(a -> !a.startsWith("-D" + JVM_FLAGS_APPLIED_PROPERTY + "="))
                .forEach(command::add);
            command.addAll(missingOptions);
            command.add(RESTARTED_PROPERTY);
            if (!missingOptions.isEmpty()) {
                command.add("-D" + JVM_FLAGS_APPLIED_PROPERTY + "=true");
            }
            // The child prints Done, but the clock started here. System.nanoTime()
            // has no origin that survives a JVM boundary, so the parent passes the
            // time it has already spent rather than the instant it began, and the
            // child adds it to its own.
            command.add(PARENT_ELAPSED_PREFIX + VeltisStartup.elapsedNanos());

            var launcherLocation = locateOwnJar();
            if (Files.isRegularFile(launcherLocation)) {
                command.add("-jar");
                command.add(launcherLocation.toString());
            } else {
                command.add("-cp");
                command.add(launcherLocation.toString());
                command.add(VeltisLauncher.class.getName());
            }
            // Rewrite --home to the absolute target: the child's working
            // directory already IS the home, so a relative --home would nest a
            // second home directory inside it.
            for (int i = 0; i < args.length; i++) {
                if ("--home".equals(args[i])) {
                    command.add("--home");
                    command.add(target.toString());
                    i++;
                } else {
                    command.add(args[i]);
                }
            }
            var builder = new ProcessBuilder(command).inheritIO();
            if (needHomeDirectory) {
                builder.directory(target.toFile());
            }
            var child = builder.start();
            System.exit(child.waitFor());
        } catch (Exception e) {
            LOG.error("Failed to restart in {}", target, e);
            System.exit(1);
        }
    }

    /**
     * This jar's own location, or the directory its classes were loaded from when
     * it runs from a build tree rather than a packaged jar.
     *
     * <p>On the runtime classpath it is last, after the runtime artifact and
     * Mojang's libraries, so VeltisMC's own modules can never displace a
     * Minecraft class — and a developer running from {@code build/classes} gets
     * the same ordering as a packaged jar.
     */
    static Path locateOwnJar() {
        try {
            return Path.of(locationOf(VeltisLauncher.class).toURI());
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    private static URL locationOf(Class<?> cls) {
        try {
            return cls.getProtectionDomain().getCodeSource().getLocation().toURI().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot determine location of " + cls.getName(), e);
        }
    }
}