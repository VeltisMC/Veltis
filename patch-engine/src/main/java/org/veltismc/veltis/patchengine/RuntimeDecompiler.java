package org.veltismc.veltis.patchengine;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decompiles Minecraft server jar using Vineflower.
 */
public class RuntimeDecompiler {

    /**
     * Decompiles a jar file to source code.
     *
     * @param jarPath the path to the jar file to decompile
     * @param outputDir the directory where decompiled sources should be written
     * @throws PatchEngineException if decompilation fails
     */
    public void decompile(Path jarPath, Path outputDir) throws PatchEngineException {
        decompile(jarPath, outputDir, List.of());
    }

    /**
     * Decompiles a jar file to source code with library classpath.
     * Libraries help the decompiler resolve external types for better output.
     *
     * @param jarPath the path to the jar file to decompile
     * @param outputDir the directory where decompiled sources should be written
     * @param libraries library jars to include on the decompiler classpath
     * @throws PatchEngineException if decompilation fails
     */
    public void decompile(Path jarPath, Path outputDir, List<Path> libraries) throws PatchEngineException {
        try {
            if (!Files.exists(jarPath)) {
                throw new PatchEngineException("Jar file not found: " + jarPath);
            }

            Files.createDirectories(outputDir);

            var builder = Decompiler.builder();
            builder.option(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
            builder.option(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
            builder.option(IFernflowerPreferences.REMOVE_BRIDGE, "1");
            builder.option(IFernflowerPreferences.LOG_LEVEL, "warn");
            builder.option(IFernflowerPreferences.MAX_PROCESSING_METHOD, "5");
            builder.option(IFernflowerPreferences.INDENT_STRING, "    ");
            builder.option(IFernflowerPreferences.UNIT_TEST_MODE, "0");
            builder.option(IFernflowerPreferences.PATTERN_MATCHING, "1");
            builder.option(IFernflowerPreferences.SWITCH_EXPRESSIONS, "1");
            builder.option(IFernflowerPreferences.EXPLICIT_GENERIC_ARGUMENTS, "1");
            builder.option(IFernflowerPreferences.INLINE_SIMPLE_LAMBDAS, "1");

            builder.output(new DirectoryResultSaver(outputDir.toFile()));
            builder.logger(new PrintStreamLogger(System.out));
            builder.inputs(jarPath.toFile());

            for (var lib : libraries) {
                if (Files.exists(lib)) {
                    builder.libraries(lib.toFile());
                }
            }

            var decompiler = builder.build();
            decompiler.decompile();

            fixupDecompileErrors(outputDir);

        } catch (Exception e) {
            throw new PatchEngineException("Failed to decompile jar: " + jarPath, e);
        }
    }

    private static final Pattern VAR_NAMELESS = Pattern.compile(
        Pattern.quote("<VAR_NAMELESS_ENCLOSURE>")
    );

    private static void fixupDecompileErrors(Path sourceDir) {
        try (var stream = Files.walk(sourceDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    var content = Files.readString(file, StandardCharsets.UTF_8);
                    var fixed = VAR_NAMELESS.matcher(content).replaceAll("");
                    if (!fixed.equals(content)) {
                        Files.writeString(file, fixed, StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    System.err.println("WARN: Failed to fixup " + file + ": " + e.getMessage());
                }
            });
        } catch (Exception ignored) {
        }
    }
}
