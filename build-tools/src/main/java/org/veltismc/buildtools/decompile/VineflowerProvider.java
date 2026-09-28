package org.veltismc.buildtools.decompile;

import org.veltismc.buildtools.context.BuildContext;
import org.veltismc.patchengine.VeltisConsole;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class VineflowerProvider implements DecompilerIntegration {

    private static final Logger LOG = LogManager.getLogger(VineflowerProvider.class);

    @Override
    public String decompilerName() {
        return "Vineflower";
    }

    @Override
    public String decompilerVersion() {
        return "1.12.0";
    }

    @Override
    public DecompileResult decompile(BuildContext context, DecompileSpec spec) {
        var startTime = System.currentTimeMillis();
        try {
            var outputDir = spec.outputSourceDirectory();
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
            // Route the decompiler's own output through Log4j2 so there is only
            // one console format; its chatter is DEBUG, warnings/errors visible.
            builder.logger(new PrintStreamLogger(VeltisConsole.logStream("veltis.decompiler")));
            builder.inputs(spec.inputJar().toFile());

            for (var cpEntry : spec.classpath()) {
                var file = new File(cpEntry);
                if (file.exists()) {
                    builder.libraries(file);
                }
            }

            var decompiler = builder.build();
            decompiler.decompile();

            fixupDecompileErrors(outputDir);
            var fileCount = countJavaFiles(outputDir);
            var elapsed = System.currentTimeMillis() - startTime;
            return DecompileResult.success(outputDir, fileCount, elapsed);
        } catch (Exception e) {
            return DecompileResult.failure(e.getMessage());
        }
    }

    private static final Pattern VAR_NAMELESS = Pattern.compile(
        Pattern.quote("<VAR_NAMELESS_ENCLOSURE>")
    );

    private static void fixupDecompileErrors(Path sourceDir) throws IOException {
        try (var stream = Files.walk(sourceDir)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    var content = Files.readString(file, StandardCharsets.UTF_8);
                    var fixed = VAR_NAMELESS.matcher(content).replaceAll("");
                    if (!fixed.equals(content)) {
                        Files.writeString(file, fixed, StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    LOG.warn("Failed to fixup {}: {}", file, e.getMessage());
                }
            });
        }
    }

    private static int countJavaFiles(Path directory) {
        try (var stream = Files.walk(directory)) {
            return (int) stream.filter(p -> p.toString().endsWith(".java")).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
