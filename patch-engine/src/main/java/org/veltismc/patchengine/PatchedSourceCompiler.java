package org.veltismc.patchengine;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles the patched Minecraft sources with the JDK's own compiler, in
 * process.
 *
 * <p>This is the step that turns a source-level patch set into a class the
 * running server can load, and it is the one piece of the pipeline that cannot
 * be skipped, faked, or delegated to {@code javac} on a path: the runtime
 * bootstrap needs it and at that point there is no Gradle, no build script and
 * no task graph — only a JVM and a workspace.
 *
 * <p>Three properties matter, and each is a decision rather than an accident:
 *
 * <ul>
 *   <li><b>Only the patch targets are compiled.</b> A decompile of Minecraft is
 *       not a source tree that compiles as a whole — 111 of its 5037 files still
 *       contain a decompiler placeholder, and many more are ambiguous. The
 *       patch set names exactly which files Veltis changed, and those are the
 *       only ones that need replacing; every other class already exists,
 *       correctly, in the widened jar. Handing javac the whole tree would turn
 *       a working patch set into thousands of errors in files nobody patched.</li>
 *
 *   <li><b>The output is the classpath's first entry.</b> The compiled classes
 *       shadow the same classes in the widened jar, which is what makes the
 *       running server execute VeltisMC's implementation rather than vanilla's.
 *       {@link VeltisRuntime} verifies that the loader actually resolved them
 *       from here and not from the jar behind them.</li>
 *
 *   <li><b>A failure is a build failure.</b> Compiler diagnostics are rendered
 *       in the same {@code [Veltis]} shape as everything else and the exception
 *       propagates, so a runtime that failed to compile is never presented as
 *       ready. There is no partial success: either every target compiled or the
 *       runtime is rebuilt next time.</li>
 * </ul>
 */
public final class PatchedSourceCompiler {

    private final int classFileRelease;
    private final org.apache.logging.log4j.Logger log =
        org.apache.logging.log4j.LogManager.getLogger(PatchedSourceCompiler.class);

    /**
     * @param classFileRelease the {@code --release} value for javac. Matched to
     *                         the JVM the server runs on, because a class file
     *                         newer than the running JVM cannot be loaded by it.
     */
    public PatchedSourceCompiler(int classFileRelease) {
        this.classFileRelease = classFileRelease;
    }

    /**
     * Compiles the given sources against the workspace and writes the classes
     * into {@link VeltisWorkspace#classesDirectory()}.
     *
     * @param sources the patched {@code .java} files to compile
     * @return the number of class files written
     * @throws PatchEngineException if no compiler is available or any source
     *                              fails to compile, with the diagnostics
     */
    public int compile(VeltisWorkspace workspace, List<Path> sources) {
        if (sources.isEmpty()) {
            return 0;
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // This is the one failure a user can actually fix by changing how they
            // started the server, so it says so rather than reporting a
            // NoClassDefFoundError from somewhere inside the compiler API.
            throw new PatchEngineException(
                "[Veltis] Cannot compile the patched Minecraft sources"
                    + "\n  Reason: this Java installation has no compiler"
                    + " (ToolProvider.getSystemJavaCompiler() returned null)"
                    + "\n  Java home: " + System.getProperty("java.home")
                    + "\n  Fix: run the server on a JDK rather than a JRE. Set JAVA_HOME to a"
                    + " JDK " + classFileRelease + "+ installation, or delete " + workspace.runtimeMarker()
                    + " and re-run ./gradlew buildVeltisMC to prebuild the runtime on a"
                    + " machine that has one.");
        }

        for (var source : sources) {
            if (!Files.isRegularFile(source)) {
                throw new PatchEngineException(
                    "[Veltis] The patch set targets a file that does not exist: " + source
                        + "\n  Reason: the patched workspace is incomplete;"
                        + " re-run the patch step to rebuild it");
            }
        }

        var outputDirectory = workspace.classesDirectory();
        // Clean, then write. `classes/` is derived output, and everything that
        // reads it — the compile-against-itself classpath below, the guard, and
        // the packaging step that copies it into the jar — assumes it holds
        // exactly what this run produced. A stale class file from a patch that
        // has since been removed would otherwise be packaged into the runtime
        // jar and guarded as if a current patch had produced it.
        try {
            if (Files.exists(outputDirectory)) {
                VeltisWorkspace.deleteTree(outputDirectory);
            }
            Files.createDirectories(outputDirectory);
        } catch (Exception e) {
            throw new PatchEngineException(
                "[Veltis] Failed to reset the class output directory " + outputDirectory
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }

        long started = System.nanoTime();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        // try-with-resources on the file manager: javac holds an open cache of
        // every file it read, and a leaked one keeps handles on the whole
        // workspace, which on Windows is what makes a later rebuild fail to
        // delete it.
        try (StandardJavaFileManager files =
                 compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            var classpath = compilerClasspath(workspace);
            var options = new ArrayList<String>(List.of(
                "-classpath", classpath,
                "-d", outputDirectory.toString(),
                "--release", Integer.toString(classFileRelease),
                "-encoding", "UTF-8",
                // Annotation processing is off because the only processors on any
                // classpath here belong to Mojang's libraries, and running them
                // over decompiled sources generates output nobody asked for. The
                // same setting keeps a library's processor from changing what
                // javac emits between the build and the runtime.
                "-proc:none",
                // Warnings from a decompile are noise: a patch author needs the
                // errors. Overwhelming them with unchecked/serial warnings from
                // 5000 lines of vanilla code is how a real error gets missed.
                "-nowarn"));

            var units = files.getJavaFileObjectsFromFiles(
                sources.stream().map(Path::toFile).toList());

            var result = compiler.getTask(null, files, diagnostics, options, null, units).call();
            if (result == null || !result) {
                throw new PatchEngineException(renderFailure(workspace, diagnostics, sources));
            }
        } catch (IOException e) {
            throw new PatchEngineException(
                "[Veltis] Failed to compile the patched Minecraft sources"
                    + "\n  Output: " + outputDirectory
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }

        int written = countClasses(outputDirectory);
        log.info("[Veltis] Compiled {} patched source{} into {} class file{} in {}",
            sources.size(), sources.size() == 1 ? "" : "s",
            written, written == 1 ? "" : "s",
            VeltisConsole.formatDuration(System.nanoTime() - started));
        return written;
    }

    /**
     * The compile classpath, in the order that makes the patched classes win.
     *
     * <p>Three entries, and the first is not optional: a patched class that
     * references another patched class must resolve against the freshly compiled
     * output, not against the vanilla class of the same name sitting in the
     * widened jar. A patch that changes a method's signature and a second patch
     * that calls it is exactly that case, and {@code patches/code/004} chains
     * with {@code 016} on the same file for the same reason.
     */
    private String compilerClasspath(VeltisWorkspace workspace) {
        var entries = new ArrayList<String>();
        entries.add(workspace.classesDirectory().toString());
        entries.add(workspace.widenedServerJar().toString());
        for (var library : MinecraftDownloader.libraryJars(workspace)) {
            entries.add(library.toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    /**
     * Renders javac's diagnostics as a Veltis report.
     *
     * <p>Capped, because a patch that breaks a widely-referenced signature
     * produces one error per file that mentions it and printing 4000 of them
     * buries the one line that says what to fix. The first errors are the ones
     * nearest the cause, in the order javac reported them.
     */
    private String renderFailure(VeltisWorkspace workspace, DiagnosticCollector<JavaFileObject> diagnostics,
                                 List<Path> sources) {
        var errors = new ArrayList<String>();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            var source = diagnostic.getSource() == null
                ? "<unknown>"
                : relative(workspace, Path.of(diagnostic.getSource().getName()));
            var line = diagnostic.getLineNumber();
            errors.add("  " + source + (line > 0 ? ":" + line : "") + " "
                + diagnostic.getMessage(Locale.ROOT));
        }

        var report = new StringBuilder()
            .append("[Veltis] Failed to compile the patched Minecraft sources")
            .append("\n  Sources: ").append(sources.size())
            .append("\n  Output: ").append(workspace.classesDirectory())
            .append("\n  Reason: ").append(errors.isEmpty()
                ? "javac reported failure without naming an error"
                : errors.size() + " error(s)");
        for (int i = 0; i < Math.min(errors.size(), 20); i++) {
            report.append('\n').append(errors.get(i));
        }
        if (errors.size() > 20) {
            report.append("\n  ... and ").append(errors.size() - 20).append(" more");
        }
        report.append("\n  The runtime was not built; the server cannot start until this compiles.");
        return report.toString();
    }

    private static String relative(VeltisWorkspace workspace, Path path) {
        try {
            return workspace.patchedDirectory().relativize(path.toAbsolutePath()).toString()
                .replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.getFileName().toString();
        }
    }

    private static int countClasses(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(p -> p.toString().endsWith(".class")).count();
        } catch (IOException e) {
            return 0;
        }
    }

}
