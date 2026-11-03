package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A throwaway workspace plus a patch set, laid out exactly like a real build.
 *
 * <p>Tests use the production entry points ({@link PatchDiscovery}, {@link
 * VeltisPatcher#mirrorPristineSource}, {@link VeltisPatcher#apply}) rather than
 * poking at internals, so what a test proves is what the pipeline does. The only
 * thing faked is the decompile: {@code source/} is filled in directly, because
 * nothing under test depends on how those bytes got there.
 *
 * <p>The layout is the real one — {@code <root>/minecraft/26.3/} for the
 * pristine decompile and {@code <root>/minecraft/workspace/26.3/{patched,build}}
 * for the workspace, plus {@code <root>/server/Shulker/{code,data,modules}} — so
 * a test that asserts "no temporary directories are produced" is asserting it
 * about the real layout.
 */
final class TestWorkspace {

    static final MinecraftVersion VERSION = MinecraftVersion.parse("26.3");
    static final String VERSION_ID = "26.3";

    private final VeltisWorkspace workspace;
    private final Map<String, String> pristine = new LinkedHashMap<>();
    private final Map<String, String> patchFiles = new LinkedHashMap<>();

    private TestWorkspace(VeltisWorkspace workspace) {
        this.workspace = workspace;
    }

    /** Creates a workspace under {@code root}, which is not itself modified. */
    static TestWorkspace create(Path root) {
        var workspace = VeltisWorkspace.of(root, VERSION).createDirectories();
        return new TestWorkspace(workspace);
    }

    VeltisWorkspace workspace() {
        return workspace;
    }

    Path patchedRoot() {
        return workspace.patchedDirectory();
    }

    // ------------------------------------------------------------------
    // Building the fixture
    // ------------------------------------------------------------------

    /** Declares a pristine source file and returns this fixture for chaining. */
    TestWorkspace source(String relativePath, String content) {
        pristine.put(relativePath, content);
        return this;
    }

    /** Declares a patch file in a category. */
    TestWorkspace patch(PatchCategory category, String fileName, String content) {
        patchFiles.put(category.directoryName() + "/" + fileName, content);
        return this;
    }

    /** Writes the declared pristine sources, patches and mirror onto disk. */
    TestWorkspace materialize() {
        for (var entry : pristine.entrySet()) {
            write(workspace.sourceDirectory().resolve(entry.getKey()), entry.getValue());
        }
        for (var entry : patchFiles.entrySet()) {
            write(workspace.shulkerDirectory().resolve(entry.getKey()), entry.getValue());
        }
        VeltisPatcher.mirrorPristineSource(workspace.sourceDirectory(),
            workspace.patchedDirectory());
        return this;
    }

    // ------------------------------------------------------------------
    // Running the pipeline
    // ------------------------------------------------------------------

    /**
     * Discovers the declared patch set.
     *
     * <p>Requires {@link #materialize()} first: discovery deliberately goes
     * through the filesystem, so a patch set has to exist as files for this to
     * mean anything. Callers that want both write and discover chain them.
     */
    List<VeltisPatch> discover() {
        return PatchDiscovery.discover(workspace.shulkerDirectory(), VERSION_ID, new PatchStats());
    }

    /** Mirrors, discovers and applies with the given worker count, for chaining. */
    TestWorkspace apply(int workers) {
        applyReporting(workers);
        return this;
    }

    /** As {@link #apply(int)}, but hands back the run's statistics. */
    PatchStats applyReporting(int workers) {
        materialize();
        var patches = discover();
        return new VeltisPatcher(workers, VERSION_ID).apply(workspace, patches);
    }

    // ------------------------------------------------------------------
    // Inspecting the result
    // ------------------------------------------------------------------

    /** The patched tree: relative slash path to exact file content, sorted. */
    Map<String, String> patchedTree() {
        var tree = new TreeMap<String, String>();
        try (var walk = Files.walk(patchedRoot())) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                tree.put(patchedRoot().relativize(file).toString().replace('\\', '/'),
                    read(file));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the patched tree", e);
        }
        return tree;
    }

    String patched(String relativePath) {
        return read(workspace.patchedDirectory().resolve(relativePath));
    }

    String pristine(String relativePath) {
        return read(workspace.sourceDirectory().resolve(relativePath));
    }

    /** Every directory under the workspace, slash-separated and sorted. */
    List<String> directoriesUnderRoot() {
        var dirs = new ArrayList<String>();
        try (var walk = Files.walk(workspace.root())) {
            for (var dir : walk.filter(Files::isDirectory).toList()) {
                dirs.add(workspace.root().relativize(dir).toString().replace('\\', '/'));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk the workspace", e);
        }
        dirs.sort(String::compareTo);
        return dirs;
    }

    /** A patch whose only section changes {@code oldText} into {@code newText}. */
    static String modifyPatch(String target, String oldText, String newText) {
        var oldLines = UnifiedDiffPatcher.splitLines(oldText);
        var newLines = UnifiedDiffPatcher.splitLines(newText);
        if (oldLines.get(oldLines.size() - 1).isEmpty()) {
            oldLines.remove(oldLines.size() - 1);
        }
        if (newLines.get(newLines.size() - 1).isEmpty()) {
            newLines.remove(newLines.size() - 1);
        }
        var sb = new StringBuilder();
        sb.append("--- a/").append(target).append('\n');
        sb.append("+++ b/").append(target).append('\n');
        sb.append("@@ -1,").append(oldLines.size())
          .append(" +1,").append(newLines.size()).append(" @@\n");
        for (var line : oldLines) {
            sb.append('-').append(line).append('\n');
        }
        for (var line : newLines) {
            sb.append('+').append(line).append('\n');
        }
        return sb.toString();
    }

    /** A patch creating a new file with the given lines (excluding a final newline). */
    static String createPatch(String target, String... lines) {
        var sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(target).append('\n');
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (var line : lines) {
            sb.append('+').append(line).append('\n');
        }
        return sb.toString();
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
