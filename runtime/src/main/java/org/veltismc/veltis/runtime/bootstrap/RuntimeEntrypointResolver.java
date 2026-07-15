package org.veltismc.veltis.runtime.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

public final class RuntimeEntrypointResolver {

    public Optional<String> resolveEntrypoint(Path jarPath) {
        var fromMetaInf = readMetaInfMainClass(jarPath);
        if (fromMetaInf.isPresent()) {
            return fromMetaInf;
        }
        return readManifestMainClass(jarPath);
    }

    private Optional<String> readMetaInfMainClass(Path jarPath) {
        try (var zf = new ZipFile(jarPath.toFile())) {
            var entry = zf.getEntry("META-INF/main-class");
            if (entry == null) return Optional.empty();
            try (var reader = new BufferedReader(
                    new InputStreamReader(zf.getInputStream(entry), StandardCharsets.UTF_8))) {
                var line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return Optional.of(line.trim());
                }
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    private Optional<String> readManifestMainClass(Path jarPath) {
        try (var jf = new JarFile(jarPath.toFile())) {
            var manifest = jf.getManifest();
            if (manifest == null) return Optional.empty();
            var mainClass = manifest.getMainAttributes()
                .getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass != null && !mainClass.isBlank()) {
                return Optional.of(mainClass.trim());
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }
}


