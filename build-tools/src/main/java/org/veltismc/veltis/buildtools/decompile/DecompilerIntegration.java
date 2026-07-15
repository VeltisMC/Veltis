package org.veltismc.veltis.buildtools.decompile;

import org.veltismc.veltis.buildtools.context.BuildContext;

import java.nio.file.Path;
import java.util.List;

public interface DecompilerIntegration {

    String decompilerName();

    String decompilerVersion();

    DecompileResult decompile(BuildContext context, DecompileSpec spec) throws Exception;

    record DecompileSpec(
        Path inputJar,
        Path outputSourceDirectory,
        List<String> classpath,
        boolean includeSyntax,
        boolean showDefaultPrefix,
        int maxThreads
    ) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private Path inputJar;
            private Path outputSourceDirectory;
            private final List<String> classpath = new java.util.ArrayList<>();
            private boolean includeSyntax = true;
            private boolean showDefaultPrefix = true;
            private int maxThreads = Runtime.getRuntime().availableProcessors();

            private Builder() {
            }

            public Builder inputJar(Path path) {
                this.inputJar = path;
                return this;
            }

            public Builder outputSourceDirectory(Path path) {
                this.outputSourceDirectory = path;
                return this;
            }

            public Builder classpath(List<String> entries) {
                this.classpath.addAll(entries);
                return this;
            }

            public Builder includeSyntax(boolean value) {
                this.includeSyntax = value;
                return this;
            }

            public Builder showDefaultPrefix(boolean value) {
                this.showDefaultPrefix = value;
                return this;
            }

            public Builder maxThreads(int threads) {
                this.maxThreads = threads;
                return this;
            }

            public DecompileSpec build() {
                if (inputJar == null) {
                    throw new IllegalStateException("inputJar is required");
                }
                if (outputSourceDirectory == null) {
                    throw new IllegalStateException("outputSourceDirectory is required");
                }
                return new DecompileSpec(
                    inputJar, outputSourceDirectory,
                    List.copyOf(classpath),
                    includeSyntax, showDefaultPrefix, maxThreads);
            }
        }
    }

    record DecompileResult(
        boolean success,
        Path outputDirectory,
        int fileCount,
        long durationMs,
        String errorMessage
    ) {

        public static DecompileResult success(
            Path outputDir, int files, long durationMs
        ) {
            return new DecompileResult(
                true, outputDir, files, durationMs, null);
        }

        public static DecompileResult failure(String error) {
            return new DecompileResult(
                false, null, 0, 0, error);
        }
    }
}


