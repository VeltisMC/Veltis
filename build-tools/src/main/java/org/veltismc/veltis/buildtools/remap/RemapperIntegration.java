package org.veltismc.veltis.buildtools.remap;

import org.veltismc.veltis.buildtools.context.BuildContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public interface RemapperIntegration {

    String remapperName();

    String remapperVersion();

    RemapResult remap(BuildContext context, RemapSpec spec) throws Exception;

    record RemapSpec(
        Path inputJar,
        Path outputJar,
        List<String> classpath,
        String mappingsFile,
        MappingFormat mappingFormat
    ) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private Path inputJar;
            private Path outputJar;
            private final List<String> classpath = new ArrayList<>();
            private String mappingsFile;
            private MappingFormat mappingFormat = MappingFormat.TINY_V2;

            private Builder() {
            }

            public Builder inputJar(Path path) {
                this.inputJar = path;
                return this;
            }

            public Builder outputJar(Path path) {
                this.outputJar = path;
                return this;
            }

            public Builder classpath(List<String> entries) {
                this.classpath.addAll(entries);
                return this;
            }

            public Builder classpathEntry(String entry) {
                this.classpath.add(entry);
                return this;
            }

            public Builder mappingsFile(String path) {
                this.mappingsFile = path;
                return this;
            }

            public Builder mappingFormat(MappingFormat format) {
                this.mappingFormat = format;
                return this;
            }

            public RemapSpec build() {
                if (inputJar == null) {
                    throw new IllegalStateException("inputJar is required");
                }
                if (outputJar == null) {
                    outputJar = inputJar.resolveSibling(
                        inputJar.getFileName().toString()
                            .replace(".jar", "-remapped.jar"));
                }
                if (mappingsFile == null) {
                    throw new IllegalStateException("mappingsFile is required");
                }
                return new RemapSpec(
                    inputJar, outputJar,
                    List.copyOf(classpath),
                    mappingsFile, mappingFormat);
            }
        }
    }

    record RemapResult(
        boolean success,
        Path outputJar,
        int classCount,
        int methodCount,
        int fieldCount,
        long durationMs,
        String errorMessage
    ) {

        public static RemapResult success(
            Path outputJar, int classes, int methods, int fields, long durationMs
        ) {
            return new RemapResult(
                true, outputJar, classes, methods, fields, durationMs, null);
        }

        public static RemapResult failure(String error) {
            return new RemapResult(
                false, null, 0, 0, 0, 0, error);
        }
    }

    enum MappingFormat {
        TINY_V1,
        TINY_V2,
        SRG,
        CUSTOM
    }
}


