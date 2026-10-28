package org.veltismc.redstone;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forks a real Veltis server child JVM and drives it through stdin console commands.
 *
 * <p>The child is launched as {@code java -Xmx1g [-XX:StartFlightRecording=...] -cp <test
 * classpath> org.veltismc.server.Main --nogui} with its working directory set to a per-harness
 * temp home containing {@code eula.txt} and a fixed-seed flat-world {@code server.properties}.
 * Because the child is a separate process, every assertion goes through the child's own command
 * dispatcher, i.e. the exact code path a player console would exercise.
 *
 * <p>Concurrency contract:
 * <ul>
 *   <li>All console output is captured by a single reader thread appending complete lines to a
 *       lock-guarded buffer; assertion helpers only ever match against whole lines, so a match
 *       can never be assembled from a partially-written line.</li>
 *   <li>{@link #cmd} writes are serialized through one buffered writer.</li>
 *   <li>Determinism: {@link #freeze()} + {@link #step(int)} drives world ticks manually with a
 *       {@code time query gametime} barrier, so tests advance the world by exact game-tick
 *       counts.</li>
 * </ul>
 */
public final class RedstoneServerHarness implements AutoCloseable {

    private static final long BOOT_TIMEOUT_MS = 240_000;
    private static final long COMMAND_TIMEOUT_MS = 15_000;
    /**
     * How long a predicate probe ({@link #blockMatches}, {@link #positionLoaded}) waits for
     * its token. These are the only probes whose token legitimately never arrives: when the
     * predicate is false the {@code run say} branch simply doesn't execute, so a false
     * answer costs the full wait. A true answer cannot lag: the child drains its whole
     * console queue on every server tick ({@code DedicatedServer#handleConsoleInputs} loops
     * until empty) and executes each line synchronously inside that drain, so the token is
     * spoken within one tick (~50ms) of the line being read — command backlog is drained in
     * the same loop iteration. The 3s only has to absorb log shipping and OS scheduling,
     * and keeps a full-tick scan of a 17-candidate fingerprint bounded at ~51s worst case
     * instead of ~4min per diverging position.
     */
    private static final long PREDICATE_GRACE_MS = 3_000;
    private static final AtomicInteger HOMES = new AtomicInteger();

    private final Path home;
    private final Process process;
    private final BufferedWriter stdin;
    private final StringBuilder captured;
    private final Thread reader;
    private final AtomicInteger checkIds = new AtomicInteger();
    private volatile boolean exited;

    private RedstoneServerHarness(Path home, Process process, BufferedWriter stdin,
            StringBuilder captured, Thread reader) {
        this.home = home;
        this.process = process;
        this.stdin = stdin;
        this.captured = captured;
        this.reader = reader;
    }

    /** Boots a server in a fresh temp home (no JFR, no extra JVM args). */
    public static RedstoneServerHarness start(String name) throws IOException, InterruptedException {
        return start(name, false);
    }

    /** Boots a server in a fresh temp home with JFR enabled. */
    public static RedstoneServerHarness start(String name, boolean jfr)
            throws IOException, InterruptedException {
        return start(name, jfr, new String[0]);
    }

    /**
     * Boots a server in a fresh temp home. When {@code jfr} is true the child is started with
     * {@code -XX:StartFlightRecording=...,dumponexit=true} writing {@code bench.jfr} into the
     * home directory; the file is complete once {@link #close()} returns. Extra JVM arguments
     * (for example {@code -Dveltis.parallelRedstone=false}) are inserted before {@code -cp}.
     */
    public static RedstoneServerHarness start(String name, boolean jfr, String... extraJvmArgs)
            throws IOException, InterruptedException {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "veltis-ac-harness",
                name + "-" + HOMES.incrementAndGet());
        if (Files.exists(home)) {
            deleteRecursively(home);
        }
        Files.createDirectories(home);
        Files.writeString(home.resolve("eula.txt"), "eula=true\n", StandardCharsets.US_ASCII);
        // A unique server port per harness so multiple children can coexist.
        int port = 25565 + 17 * HOMES.get();
        Files.writeString(home.resolve("server.properties"), String.join("\n",
                "level-name=world",
                "level-type=minecraft:flat",
                "generator-settings={\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
                        + "{\"block\":\"minecraft:stone\",\"height\":1}],\"biome\":\"minecraft:plains\"}",
                // Flat worlds still generate structures (e.g. trial chambers) when this is
                // left at its default of true. A structure intersecting the build band
                // corrupts circuits (blocks replaced, surface probe returning the
                // structure roof instead of the stone layer) and makes placement
                // seed-dependent. Tests build their own circuits on the assumption of
                // a bare two-layer flat world, so structures must stay off.
                "generate-structures=false",
                "online-mode=false",
                "spawn-protection=0",
                "view-distance=4",
                "simulation-distance=4",
                "max-tick-time=-1",
                // 26.3 pauses the server after 60s empty; that would freeze our manual
                // tick stepping mid-suite.
                "pause-when-empty-seconds=0",
                "server-port=" + port,
                "motd=ac harness",
                ""), StandardCharsets.US_ASCII);

        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java").toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xmx1g");
        if (jfr) {
            command.add("-XX:StartFlightRecording=filename=" + home.resolve("bench.jfr")
                    + ",settings=profile,dumponexit=true");
        }
        for (String arg : extraJvmArgs) {
            command.add(arg);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.veltismc.server.Main");
        command.add("--nogui");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(home.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder captured = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (captured) {
                        captured.append(line).append('\n');
                        captured.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // process ended; fall through
            }
            synchronized (captured) {
                captured.notifyAll();
            }
        }, "ac-harness-reader");
        reader.setDaemon(true);
        reader.start();

        BufferedWriter stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        RedstoneServerHarness h = new RedstoneServerHarness(
                home, process, stdin, captured, reader);

        int mark = h.mark();
        try {
            if (!h.await(mark, "Done \\(", BOOT_TIMEOUT_MS)) {
                throw new IllegalStateException("server failed to boot within "
                        + BOOT_TIMEOUT_MS + "ms; tail:\n" + h.tail(4000));
            }
            // Quiet, deterministic world: no mobs, no day cycle, no weather. (26.3 renamed
            // the gamerules to snake_case.) The trailing say marker guarantees these
            // commands were dispatched before start() returns.
            h.cmd("gamerule spawn_mobs false");
            h.cmd("gamerule advance_time false");
            h.cmd("gamerule advance_weather false");
            h.cmd("time set day");
            String marker = "BOOT" + HOMES.get() + "DONE";
            if (!h.sayMarker(marker)) {
                throw new IllegalStateException(
                        "bootstrap commands not dispatched; tail:\n" + h.tail(2000));
            }
        } catch (IOException | RuntimeException e) {
            // Never leave an orphaned child: the caller has no handle yet.
            h.close();
            throw e;
        }
        return h;
    }

    public Path home() {
        return home;
    }

    // ------------------------------------------------------------------ io

    /** Sends one console command. Serialized; never blocks on the reader. */
    public synchronized void cmd(String command) throws IOException {
        // MC 26.3 rejects quoted block-state arguments ("Unknown block type 'minecraft:'").
        // No command this harness sends legitimately needs quotes, so strip them centrally
        // as a safety net; call sites should also avoid them for readable failures.
        stdin.write(command.replace("\"", ""));
        stdin.write('\n');
        stdin.flush();
    }

    /** Current capture position; pass to {@link #await} to ignore pre-existing output. */
    public int mark() {
        synchronized (captured) {
            return captured.length();
        }
    }

    /** Waits until the captured output contains {@code regex} at/after {@code mark}. */
    public boolean await(int mark, String regex, long timeoutMs) {
        Pattern p = Pattern.compile(regex, Pattern.MULTILINE);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (captured) {
            while (true) {
                Matcher m = p.matcher(captured);
                if (m.find(mark)) {
                    return true;
                }
                if (exited || !process.isAlive()) {
                    return false;
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    captured.wait(Math.min(remainingMs, 250));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /** Returns captured output after {@code mark}, once it has been quiet for {@code quietMs}. */
    public String textSinceQuiet(int mark, long quietMs, long maxMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMs);
        long lastSize = -1;
        long lastChange = System.nanoTime();
        synchronized (captured) {
            while (true) {
                int size = captured.length();
                long now = System.nanoTime();
                if (size != lastSize) {
                    lastSize = size;
                    lastChange = now;
                }
                if (now - lastChange >= TimeUnit.MILLISECONDS.toNanos(quietMs)) {
                    return captured.substring(Math.min(mark, size));
                }
                if (now >= deadline) {
                    return captured.substring(Math.min(mark, size));
                }
                try {
                    captured.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return captured.substring(Math.min(mark, size));
                }
            }
        }
    }

    public String tail(int maxChars) {
        synchronized (captured) {
            int len = captured.length();
            return captured.substring(Math.max(0, len - maxChars));
        }
    }

    // ------------------------------------------------------ command helpers

    /**
     * {@code say}'s a unique token and waits for it to come back; true = token observed.
     * Matches only the {@code [Server] <token>} chat broadcast line, because the console
     * also echoes the raw command (which contains the token) as
     * {@code Server issued server command: ...}.
     */
    public boolean sayMarker(String token) throws IOException {
        int mark = mark();
        cmd("say " + token);
        return await(mark, "\\[Server\\] " + Pattern.quote(token), COMMAND_TIMEOUT_MS);
    }

    /**
     * Asserts a block predicate through the child's own dispatcher:
     * {@code execute if block <condition> run say <token>}. Returns true iff the predicate held
     * (i.e. the token came back as chat output, not merely as a command echo).
     */
    public boolean blockMatches(String condition) throws IOException {
        String token = "CHk" + checkIds.incrementAndGet() + "OK";
        int mark = mark();
        cmd("execute if block " + condition + " run say " + token);
        return await(mark, "\\[Server\\] " + Pattern.quote(token), PREDICATE_GRACE_MS);
    }

    /** True iff the given position is in a loaded chunk (per the child's own check). */
    public boolean positionLoaded(int x, int y, int z) throws IOException {
        String token = "LD" + checkIds.incrementAndGet() + "OK";
        int mark = mark();
        cmd("execute if loaded " + x + " " + y + " " + z + " run say " + token);
        return await(mark, "\\[Server\\] " + Pattern.quote(token), PREDICATE_GRACE_MS);
    }

    public void awaitLoaded(int x, int y, int z) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!positionLoaded(x, y, z)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("position " + x + "," + y + "," + z
                        + " not loaded within 60s; tail:\n" + tail(1500));
            }
            Thread.sleep(200);
        }
    }

    /**
     * Marks the chunks in the given CHUNK-coordinate range as force-loaded, translating to
     * whatever coordinate space this server's {@code /forceload} interprets (detected once at
     * boot by the suite via {@link #detectForceloadChunkCoords()}).
     */
    private boolean forceloadChunkCoords;

    public void configureForceloadChunkCoords(boolean chunkCoords) {
        this.forceloadChunkCoords = chunkCoords;
    }

    public boolean forceloadUsesChunkCoords() {
        return forceloadChunkCoords;
    }

    public void forceLoadChunks(int cx1, int cz1, int cx2, int cz2) throws IOException {
        if (forceloadChunkCoords) {
            cmd("forceload add " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        } else {
            cmd("forceload add " + (cx1 * 16) + " " + (cz1 * 16) + " " + ((cx2 + 1) * 16 - 1)
                    + " " + ((cz2 + 1) * 16 - 1));
        }
    }

    /**
     * Detects whether /forceload takes chunk or block coordinates. Sends an if/unless pair:
     * one of the two tokens always fires, so both outcomes are observable without waiting for
     * a negative-check timeout.
     */
    public boolean detectForceloadChunkCoords() throws IOException, InterruptedException {
        // Force with chunk coords (0,0)-(1,1): under block-coords semantics that only
        // covers chunk (0,0); under chunk-coords semantics it covers chunks (0,0)-(1,1).
        cmd("forceload add 0 0 1 1");
        Thread.sleep(1500);
        int mark = mark();
        String yes = "FC" + checkIds.incrementAndGet() + "YES";
        String no = "FC" + checkIds.incrementAndGet() + "NO";
        cmd("execute if loaded 20 2 20 run say " + yes);
        cmd("execute unless loaded 20 2 20 run say " + no); // chunk (1,1): loaded iff chunk-coords
        if (!await(mark, "\\[Server\\] FC\\d+(?:YES|NO)", COMMAND_TIMEOUT_MS)) {
            throw new IllegalStateException("forceload probe not acknowledged; tail:\n" + tail(1500));
        }
        return textAfter(mark).contains("[Server] " + yes);
    }

    /**
     * Finds the highest non-air y in {@code [from..to]} (inclusive, descending) at (x, z).
     * Every candidate is probed with {@code execute unless block ... run say} so every probe
     * fires unconditionally — no per-candidate timeout on air — and the answers are scanned in
     * one pass after a done marker. Returns {@link Integer#MIN_VALUE} if everything is air.
     */
    public int highestNonAir(int x, int z, int from, int to) throws IOException, InterruptedException {
        int mark = mark();
        String done = "SOLDONE" + checkIds.incrementAndGet();
        for (int cand = from; cand >= to; cand--) {
            String token = "SOL" + checkIds.incrementAndGet() + "_" + cand + "OK";
            cmd("execute unless block " + x + " " + cand + " " + z
                    + " minecraft:air run say " + token);
        }
        cmd("say " + done);
        if (!await(mark, "\\[Server\\] " + Pattern.quote(done), 60_000)) {
            throw new IllegalStateException("surface scan not acknowledged; tail:\n" + tail(1500));
        }
        int best = Integer.MIN_VALUE;
        Matcher m = Pattern.compile("\\[Server\\] SOL\\d+_-?\\d+OK").matcher(textAfter(mark));
        while (m.find()) {
            String token = m.group();
            int cand = Integer.parseInt(token.substring(token.lastIndexOf('_') + 1, token.lastIndexOf("OK")));
            if (cand > best) {
                best = cand;
            }
        }
        return best;
    }

    /** Current game time in ticks, read via {@code time query gametime}. */
    public long gameTime() throws IOException {
        int mark = mark();
        cmd("time query gametime");
        if (!await(mark, "The game time is \\d+ tick\\(s\\)", COMMAND_TIMEOUT_MS)) {
            throw new IllegalStateException("no gametime response; tail:\n" + tail(1500));
        }
        Matcher m = Pattern.compile("The game time is (\\d+) tick\\(s\\)").matcher(textAfter(mark));
        long last = -1;
        while (m.find()) {
            last = Long.parseLong(m.group(1));
        }
        if (last < 0) {
            throw new IllegalStateException("unparseable gametime output");
        }
        return last;
    }

    private String textAfter(int mark) {
        synchronized (captured) {
            return captured.substring(Math.min(mark, captured.length()));
        }
    }

    public void freeze() throws IOException {
        cmd("tick freeze");
    }

    public void unfreeze() throws IOException {
        cmd("tick unfreeze");
    }

    /**
     * Advances the frozen world by exactly {@code gameTicks} game ticks and blocks until the
     * child's game time confirms it. Safe whether {@code tick step} executes synchronously in
     * the command or asynchronously.
     */
    public void step(int gameTicks) throws IOException, InterruptedException {
        long before = gameTime();
        cmd("tick step " + gameTicks + "t");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (gameTime() < before + gameTicks) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("tick step " + gameTicks + "t did not advance game time from "
                        + before + " within 30s; tail:\n" + tail(1500));
            }
            Thread.sleep(100);
        }
    }

    /** Raw {@code tick query} output (avg + p50/p95/p99 ms/tick). */
    public String tickQuery() throws IOException {
        int mark = mark();
        cmd("tick query");
        return textSinceQuiet(mark, 800, 15_000);
    }

    public void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // ------------------------------------------------------------- lifecycle

    /** Asks the child to stop and waits for exit; returns the exit code. */
    public int stopAndExit() {
        try {
            if (process.isAlive()) {
                cmd("stop");
            }
        } catch (IOException ignored) {
            // stdin gone; process is likely exiting already
        }
        return waitForExit(60_000);
    }

    public int waitForExit(long timeoutMs) {
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
                return process.isAlive() ? -1 : process.exitValue();
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public void close() {
        int code = stopAndExit();
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
        if (code != 0) {
            System.err.println("harness: child exited with code " + code + "; tail:\n" + tail(2000));
        }
    }

    // ------------------------------------------------------------- internals

    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
