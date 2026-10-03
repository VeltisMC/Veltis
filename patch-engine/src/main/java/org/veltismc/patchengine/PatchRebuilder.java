package org.veltismc.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns the patched workspace back into the patch set.
 *
 * <p>This is the inverse of {@link VeltisPatcher}: the developer edits files in
 * {@code patched/}, runs {@code ./gradlew rebuildPatches}, and the diff against
 * the pristine {@code source/} tree becomes patch files. That round trip is why
 * the pristine tree is kept at all — it is the baseline a rebuild diffs against,
 * so no third copy of the decompile is needed.
 *
 * <p>The diff comes from {@link DiffGenerator}, the same format the engine reads
 * back, so a regenerated patch is not "close enough to" a hand-written one: it is
 * what the parser accepts, with hunk counts computed from the emitted lines.
 *
 * <h2>Chains</h2>
 *
 * <p>Several patches may address one file — that is a deliberate feature, and a
 * common way to keep an unrelated repair reviewable beside the feature that
 * touches the same file. A rebuild cannot see the intermediate states on disk
 * (only the final tree exists), so it <em>replays</em> the chain with {@link
 * UnifiedDiffPatcher#applyChain}, one patch at a time, from the pristine file.
 * That recovers every intermediate state and answers the only question that
 * matters: what each patch in the chain individually did.
 *
 * <p>The edit then lands in the <em>last</em> patch that still needs the file,
 * because that is the only position from which the final tree is reachable.
 * Every earlier patch regenerates byte-identically, so an edit never rewrites a
 * patch a contributor did not touch. If the file has been returned to pristine,
 * every patch in the chain drops it and the ones left with nothing are deleted.
 *
 * <h2>Numbering</h2>
 *
 * <p>Numbers are an ordering mechanism, not an identity: after every rebuild
 * each category reads {@code 001-}, {@code 002-}, {@code 003-}, … in the order
 * the patches must apply, with no gaps left by a deletion. Descriptions are
 * carried across untouched, and a renumber rewrites the file name only — never
 * the diff inside it. Discovery order (category order, then name) is the order
 * renumbering preserves, and nothing reads directory iteration order.
 *
 * <p>Nothing depends on filesystem iteration order: the patched tree is walked,
 * sorted, and only then assigned to patches.
 */
public final class PatchRebuilder {

    /**
     * Gradle property {@code -PpatchName="Improve Entity Logging"} that names a
     * patch this rebuild is about to create, instead of the name derived from the
     * target path.
     */
    static final String PATCH_NAME_PROPERTY = "veltismc.patchName";

    private static final String STAGING_SUFFIX = ".building";

    private final org.apache.logging.log4j.Logger log =
        org.apache.logging.log4j.LogManager.getLogger(PatchRebuilder.class);

    /** What a rebuild did. */
    public record Result(
        int regenerated,   // existing patches rewritten or renumbered
        int created,       // patches created for files no patch addressed
        int removed,       // patches deleted because nothing differed any more
        int targets        // files the patch set now covers
    ) {
    }

    /** One target's state before and after one patch's contribution to it. */
    private record Section(String before, String after) {
    }

    /**
     * Regenerates the patch set from the current contents of the patched tree.
     *
     * @param workspace   supplies both trees being diffed
     * @param patchesRoot the {@code patches/} directory to write into
     */
    public Result rebuild(VeltisWorkspace workspace, Path patchesRoot, String minecraftVersion) {
        var source = workspace.sourceDirectory();
        var patched = workspace.patchedDirectory();
        if (!Files.isDirectory(patched)) {
            throw new PatchEngineException(
                "[VeltisPatch] No patched source at " + patched
                    + "\n  Reason: apply the patches first, edit the result, then rebuild"
                    + " (./gradlew applyPatches)");
        }

        var existing = PatchDiscovery.discover(patchesRoot, minecraftVersion, new PatchStats());
        requireNoPatchWasDeletedSinceApply(workspace, existing);

        // Which patches address which target, and in which order. This is the
        // chain a replay has to walk; discovery already gives the application
        // order, so nothing has to be re-derived from file names.
        var chains = new LinkedHashMap<String, List<VeltisPatch>>();
        for (var patch : existing) {
            for (var target : patch.targets()) {
                chains.computeIfAbsent(target, t -> new ArrayList<>()).add(patch);
            }
        }

        // One slot per patch, existing or new, in the order they must end up in.
        // Keyed by category *and* name: two categories may legitimately hold a
        // patch with the same name, and collapsing them would drop one.
        var slots = new LinkedHashMap<String, Slot>();
        for (var patch : existing) {
            var slot = new Slot(patch.name(), patch.category(), true);
            slot.description = descriptionOf(patch.name());
            slots.put(key(patch.category(), patch.name()), slot);
        }

        var patcher = new UnifiedDiffPatcher(minecraftVersion);
        var covered = new LinkedHashSet<String>();
        for (var target : listTargets(patched)) {
            // Compare bytes before anything else. Almost every file in the tree is
            // an untouched copy of the pristine decompile — including thousands of
            // binary resources no patch could ever address — and reading those as
            // text would be both slower and wrong.
            var editedBytes = readBytesOrNull(resolveUnder(patched, target));
            if (editedBytes == null) {
                continue;
            }
            var pristineBytes = readBytesOrNull(resolveUnder(source, target));
            if (pristineBytes != null && java.util.Arrays.equals(pristineBytes, editedBytes)) {
                continue;   // identical: nothing to patch, and nothing keeps it
            }
            var edited = decode(target, editedBytes);
            var pristine = pristineBytes == null ? null : decode(target, pristineBytes);
            var chain = chains.get(target);
            if (chain == null) {
                var slot = newSlotFor(target, slots);
                slot.sections.put(target, new Section(pristine, edited));
                covered.add(target);
                continue;
            }
            assignChain(chain, target, pristine, edited, patcher, slots, covered);
        }

        applyRequestedName(slots);
        numberSlots(slots);
        var outcome = writeAll(slots, patchesRoot);
        recordAppliedPatches(workspace, slots, patchesRoot);
        return new Result(outcome.regenerated, outcome.created, outcome.removed, covered.size());
    }

    // ------------------------------------------------------------------
    // Chains
    // ------------------------------------------------------------------

    /**
     * Works out what each patch in a chain must now contain, replaying the chain
     * to recover the intermediate states that do not exist on disk.
     *
     * <p>The rule, in one sentence: keep every patch up to and including the last
     * one whose result still equals the edited file, drop the rest, and if none of
     * them does, put the edit in the last patch of the chain.
     */
    private void assignChain(List<VeltisPatch> chain, String target, String pristine,
                             String edited, UnifiedDiffPatcher patcher, Map<String, Slot> slots,
                             Set<String> covered) {
        if (pristine != null && pristine.equals(edited)) {
            return;   // back to pristine: no patch in the chain needs this file
        }

        // Replay from the pristine file, capturing the state after each patch.
        var states = new ArrayList<String>(chain.size());
        var state = pristine;
        var stats = new PatchStats();
        for (var patch : chain) {
            var entry = new UnifiedDiffPatcher.ChainEntry(patch, fileDiffOf(patch, target));
            var result = patcher.applyChain(target, state, List.of(entry), stats);
            state = result == null ? state : result.content();
            states.add(state);
        }

        // The last patch whose result the edited file still equals, 1-based; -1
        // when it equals none of them.
        var keep = -1;
        for (int i = states.size(); i >= 1; i--) {
            if (edited.equals(states.get(i - 1))) {
                keep = i;
                break;
            }
        }

        for (int i = 0; i < chain.size(); i++) {
            var index = i + 1;
            if (keep >= 1 && index > keep) {
                continue;   // this patch no longer contributes to the file
            }
            var before = i == 0 ? pristine : states.get(i - 1);
            var after = (keep >= 1 || index < chain.size()) ? states.get(i) : edited;
            var patch = chain.get(i);
            slots.get(key(patch.category(), patch.name()))
                .sections.put(target, new Section(before, after));
            covered.add(target);
        }
    }

    /** The one section of {@code patch} that addresses {@code target}. */
    private static ParsedPatch.FileDiff fileDiffOf(VeltisPatch patch, String target) {
        for (var file : patch.parsed().files) {
            if (file.target().equals(target)) {
                return file;
            }
        }
        // Unreachable: targets() is derived from the same list.
        throw new PatchEngineException(
            "[VeltisPatch] Failed to rebuild the patch set"
                + "\n  Patch: " + patch.category().directoryName() + "/" + patch.name()
                + "\n  Target: " + target
                + "\n  Reason: the parsed patch no longer contains a section for that target");
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    /**
     * Refuses to rebuild when a patch file disappeared after this workspace was
     * last patched.
     *
     * <p>{@code patched/} still holds the deleted patch's changes, so a rebuild
     * would diff them straight back into a fresh patch file and the deletion would
     * silently never have happened. Nothing else can detect it: the tree has no
     * memory of which patch produced which line.
     *
     * <p>Skipped when there is no record at all, because a workspace built before
     * this file existed — or never patched — has nothing to compare against, and
     * refusing to rebuild there would only be obstruction.
     */
    private void requireNoPatchWasDeletedSinceApply(VeltisWorkspace workspace,
                                                     List<VeltisPatch> existing) {
        var file = workspace.appliedPatchesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        var present = new HashSet<String>();
        for (var patch : existing) {
            present.add(key(patch.category(), patch.name()));
        }
        List<String> missing;
        try {
            missing = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("\t", -1))
                .filter(parts -> parts.length >= 2 && !present.contains(parts[0] + "/" + parts[1]))
                .map(parts -> "  Missing: " + parts[0] + "/" + parts[1]
                    + (parts.length >= 4 && !parts[3].isBlank()
                        ? "\n    Last known targets: " + parts[3].replace(';', ' ') : ""))
                .toList();
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to read the applied patch record in " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
        if (missing.isEmpty()) {
            return;
        }
        throw new PatchEngineException(
            "[VeltisPatch] A patch file was deleted after this workspace was last patched\n"
                + String.join("\n", missing)
                + "\n  Reason: patched/ still contains that patch's changes, so a rebuild would"
                + " diff them straight back into a new patch file and the deletion would never"
                + " have happened"
                + "\n  Fix: run ./gradlew applyPatches to rebuild patched/ from the patches that"
                + " remain, edit the result if you meant to change more, then rebuild"
                + "\n  Note: nothing was written");
    }

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    /** One patch file being produced: its identity, its name and its sections. */
    private static final class Slot {
        final String oldName;          // null for a patch this rebuild creates
        final PatchCategory category;
        final boolean existing;
        String description;            // "Improve-Command-Logging.patch"
        String finalName;              // assigned by numberSlots()
        String rendered;               // the file's new bytes, or null when unchanged
        Path staging;                  // where those bytes sit until the swap
        final LinkedHashMap<String, Section> sections = new LinkedHashMap<>();

        Slot(String oldName, PatchCategory category, boolean existing) {
            this.oldName = oldName;
            this.category = category;
            this.existing = existing;
        }

        Path file(Path patchesRoot) {
            return patchesRoot.resolve(category.directoryName()).resolve(finalName);
        }

        Path oldFile(Path patchesRoot) {
            return patchesRoot.resolve(category.directoryName()).resolve(oldName);
        }
    }

    private static String key(PatchCategory category, String name) {
        return category.directoryName() + "/" + name;
    }

    /**
     * Creates a slot for a file no patch addressed yet.
     *
     * <p>The category comes from the path, not from a per-run judgement:
     * {@code .java} is a code change, anything under {@code org/veltismc/} is a
     * module change, everything else is a data change. The number is not decided
     * here: a new patch takes whatever position its order puts it at once every
     * category has been renumbered, which is how a patch added after a deletion
     * lands in the gap instead of at the end of a gapped series.
     */
    private Slot newSlotFor(String target, Map<String, Slot> slots) {
        var slot = new Slot(null, inferCategory(target), false);
        slot.description = describeTarget(target);
        // Keyed by position rather than by name: a new slot has no file name yet,
        // and two new patches in one category must not collide on a description
        // they happen to share. Nothing ever looks a new slot up by this key.
        slots.put(slot.category.directoryName() + "/#" + slots.size(), slot);
        return slot;
    }

    /**
     * Honours {@code -PpatchName} by renaming the one patch this rebuild creates.
     *
     * <p>Two different failure modes, deliberately: creating no new patch is only
     * a warning, because a property that names nothing is a harmless habit; but
     * creating several is a hard stop, because guessing which of them the human
     * meant would silently put the name on the wrong file.
     */
    private void applyRequestedName(Map<String, Slot> slots) {
        var requested = System.getProperty(PATCH_NAME_PROPERTY, "").trim();
        if (requested.isEmpty()) {
            return;
        }
        var fresh = slots.values().stream().filter(slot -> !slot.existing).toList();
        if (fresh.isEmpty()) {
            log.warn("[Veltis] -PpatchName=\"{}\" was ignored: this rebuild created no new patch",
                requested);
            return;
        }
        if (fresh.size() != 1) {
            var targets = new ArrayList<String>();
            for (var slot : fresh) {
                targets.addAll(slot.sections.keySet());
            }
            throw new PatchEngineException(
                "[VeltisPatch] -PpatchName=\"" + requested + "\" cannot name this rebuild"
                    + "\n  Reason: a rebuild names exactly one new patch, and this one creates "
                    + fresh.size()
                    + (targets.isEmpty() ? "" : "\n  New targets: " + String.join(", ", targets))
                    + "\n  Fix: rebuild without -PpatchName and rename the file, or make the"
                    + " rebuild create a single new patch"
                    + "\n  Note: nothing was written");
        }
        var slot = fresh.get(0);
        var previous = slot.description;
        slot.description = describe(requested);
        if (slot.description.equals(previous)) {
            return;
        }
        log.info("[Veltis] Naming the new patch {} (was {})", slot.description, previous);
    }

    /** Where a path belongs. Stated once so every rebuild agrees. */
    static PatchCategory inferCategory(String target) {
        if (target.startsWith("org/veltismc/")) {
            return PatchCategory.MODULES;
        }
        if (target.endsWith(".java")) {
            return PatchCategory.CODE;
        }
        return PatchCategory.DATA;
    }

    /** {@code net/minecraft/Foo.java} -> {@code net-minecraft-Foo}, for a patch file name. */
    private static String describeTarget(String target) {
        var withoutExtension = target.endsWith(".java")
            ? target.substring(0, target.length() - ".java".length())
            : target;
        return describe(withoutExtension);
    }

    /**
     * Turns anything a human might supply into the {@code NNN-Description.patch}
     * vocabulary this directory uses.
     *
     * <p>A leading number is stripped so {@code -PpatchName="007-Improve-Logging"}
     * does not become {@code 001-007-Improve-Logging}, and a missing {@code
     * .patch} suffix is added so a description cannot produce a file discovery
     * would never read again.
     */
    private static String describe(String raw) {
        var name = raw.replace('\\', '-').replace('/', '-')
            .replaceAll("[^A-Za-z0-9._-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        if (i > 0 && i < name.length() && name.charAt(i) == '-') {
            name = name.substring(i + 1);
        }
        if (name.isEmpty()) {
            throw new PatchEngineException(
                "[VeltisPatch] The patch description is empty"
                    + "\n  Reason: nothing is left of it once path separators and punctuation"
                    + " are removed, so no patch file could be named");
        }
        return name.endsWith(".patch") ? name : name + ".patch";
    }

    /**
     * The description inside an existing patch's file name, number included or
     * not: {@code 016-Wire-VeltisBootstrap-Integration.patch} keeps {@code
     * Wire-VeltisBootstrap-Integration.patch}.
     */
    private static String descriptionOf(String fileName) {
        int i = 0;
        while (i < fileName.length() && Character.isDigit(fileName.charAt(i))) {
            i++;
        }
        var stripped = (i > 0 && i < fileName.length() && fileName.charAt(i) == '-')
            ? fileName.substring(i + 1) : fileName;
        return stripped.isBlank() ? describeTarget("patch") : stripped;
    }

    /**
     * Gives every slot its final {@code NNN-} number, per category, in the order
     * the slots were discovered and created.
     *
     * <p>Pure arithmetic on insertion order: no directory listing, no timestamps,
     * no worker completion order. The same patch set always numbers the same way,
     * and a deletion pulls everything after it up rather than leaving a hole.
     */
    private static void numberSlots(Map<String, Slot> slots) {
        var next = new LinkedHashMap<PatchCategory, Integer>();
        for (var slot : slots.values()) {
            var number = next.merge(slot.category, 1, Integer::sum);
            slot.finalName = String.format("%03d-%s", number, slot.description);
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Counters accumulated while writing, so one pass reports once. */
    private record Written(int regenerated, int created, int removed) {
    }

    /**
     * Writes every patch that changed, renumbers the ones whose number moved, and
     * deletes the ones that no longer say anything.
     *
     * <p>Three passes rather than one. New content is staged first under a name
     * discovery never reads, then the superseded files are removed, then the
     * staged files move into place — so a renumber can never have a slot write
     * over a file another slot still owns, whatever order their old numbers were
     * in. A patch whose bytes and number are both unchanged is not touched at all,
     * which is what makes a rebuild with no edits produce no version-control diff.
     */
    private Written writeAll(Map<String, Slot> slots, Path patchesRoot) {
        var stage = new ArrayList<Slot>();
        var drop = new ArrayList<Slot>();
        for (var slot : slots.values()) {
            var text = render(slot);
            if (text == null) {
                if (slot.existing) {
                    drop.add(slot);
                }
                continue;
            }
            slot.rendered = text;
            if (!slot.existing || !slot.oldName.equals(slot.finalName)) {
                stage.add(slot);
                continue;
            }
            if (text.equals(readOrNull(slot.file(patchesRoot)))) {
                continue;   // already current in both name and content
            }
            stage.add(slot);
        }

        for (var slot : stage) {
            var staging = slot.file(patchesRoot).resolveSibling(
                slot.finalName + STAGING_SUFFIX);
            try {
                Files.createDirectories(staging.getParent());
                Files.writeString(staging, slot.rendered, StandardCharsets.UTF_8);
                slot.staging = staging;
            } catch (IOException e) {
                throw new PatchEngineException(
                    "[VeltisPatch] Failed to stage " + slot.file(patchesRoot)
                        + "\n  Reason: " + MojangMetadata.rootMessage(e)
                        + "; every other patch file was left unchanged", e);
            }
        }

        var removed = 0;
        try {
            for (var slot : drop) {
                removed += deleteSlot(slot, patchesRoot) ? 1 : 0;
            }
            // Only a slot that changed its number has a file to give up; one whose
            // content changed under the same name moves over its own file instead.
            for (var slot : stage) {
                if (slot.existing && !slot.oldName.equals(slot.finalName)) {
                    deleteSlot(slot, patchesRoot);
                }
            }
            for (var slot : stage) {
                try {
                    Files.move(slot.staging, slot.file(patchesRoot),
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new PatchEngineException(
                        "[VeltisPatch] Failed to write " + slot.file(patchesRoot)
                            + "\n  Reason: " + MojangMetadata.rootMessage(e)
                            + "; every other patch file was left unchanged", e);
                } finally {
                    slot.staging = null;
                }
            }
        } finally {
            for (var slot : stage) {
                if (slot.staging != null) {
                    try {
                        Files.deleteIfExists(slot.staging);
                    } catch (IOException ignored) {
                        // A leftover staging file is inert: discovery reads *.patch only.
                    }
                }
            }
        }

        var regenerated = 0;
        var created = 0;
        for (var slot : stage) {
            if (slot.existing) {
                regenerated++;
                if (!slot.oldName.equals(slot.finalName)) {
                    log.info("[Veltis] Renumbered {}/{} to {}/{}", slot.category.directoryName(),
                        slot.oldName, slot.category.directoryName(), slot.finalName);
                } else {
                    log.info("[Veltis] Regenerated {}/{} ({} file{})",
                        slot.category.directoryName(), slot.finalName,
                        slot.sections.size(), slot.sections.size() == 1 ? "" : "s");
                }
            } else {
                created++;
                log.info("[Veltis] Created {}/{} for {}", slot.category.directoryName(),
                    slot.finalName, String.join(", ", slot.sections.keySet()));
            }
        }
        return new Written(regenerated, created, removed);
    }

    /** Deletes a patch file, reporting whether it was actually there. */
    private boolean deleteSlot(Slot slot, Path patchesRoot) {
        var file = (slot.existing ? slot.oldName : slot.finalName);
        var path = patchesRoot.resolve(slot.category.directoryName()).resolve(file);
        try {
            if (Files.deleteIfExists(path)) {
                log.info("[Veltis] Removed {}/{} (nothing differs any more)",
                    slot.category.directoryName(), file);
                return true;
            }
            return false;
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to remove the now-empty patch " + path
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Renders one patch file's bytes, or {@code null} when it says nothing at all.
     *
     * <p>Sections are sorted by target rather than by the order the walk happened
     * to visit them, so a patch's layout does not depend on directory order.
     */
    private String render(Slot slot) {
        var lines = new ArrayList<String>();
        var targets = new ArrayList<>(slot.sections.keySet());
        targets.sort(Comparator.naturalOrder());
        for (var target : targets) {
            var section = slot.sections.get(target);
            if (section.before() != null && section.before().equals(section.after())) {
                continue;
            }
            var diff = DiffGenerator.diff(target, section.before(), section.after());
            if (diff.isEmpty()) {
                continue;
            }
            if (!lines.isEmpty()) {
                lines.add("");   // one blank line between a patch's file sections
            }
            lines.addAll(diff);
        }
        return lines.isEmpty() ? null : String.join("\n", lines) + "\n";
    }

    /**
     * Rewrites the applied-patch record to what the patch set now is.
     *
     * <p>Without this, closing a numbering gap would make the next rebuild report
     * every renumbered patch as deleted — the record would still hold the old
     * names. The content of {@code patched/} is untouched by a renumber, so
     * writing the new names is simply keeping the record true.
     */
    private void recordAppliedPatches(VeltisWorkspace workspace, Map<String, Slot> slots,
                                      Path patchesRoot) {
        var lines = new ArrayList<String>();
        for (var slot : slots.values()) {
            if (slot.sections.isEmpty()) {
                continue;
            }
            var file = slot.file(patchesRoot);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            var targets = new ArrayList<>(slot.sections.keySet());
            targets.sort(String::compareTo);
            lines.add(slot.category.directoryName() + "\t" + slot.finalName
                + "\t" + VeltisPatch.revisionOf(file) + "\t" + String.join(";", targets));
        }
        lines.sort(String::compareTo);
        var file = workspace.appliedPatchesFile();
        try {
            Files.createDirectories(file.getParent());
            MinecraftDownloader.writeMarker(file, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to record the applied patches in " + file
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    // ------------------------------------------------------------------
    // Filesystem helpers
    // ------------------------------------------------------------------

    /** Regular files under {@code root} as sorted, slash-separated relative paths. */
    private static List<String> listTargets(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to walk " + root
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /** Resolves a slash-separated target under a root, refusing to escape it. */
    private static Path resolveUnder(Path root, String target) {
        return root.resolve(target.replace('/', root.getFileSystem().getSeparator().charAt(0)));
    }

    private static String readOrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to read " + path
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /** A file's bytes, or {@code null} when there is no file there. */
    private static byte[] readBytesOrNull(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            throw new PatchEngineException(
                "[VeltisPatch] Failed to read " + path
                    + "\n  Reason: " + MojangMetadata.rootMessage(e), e);
        }
    }

    /**
     * Decodes a file that is known to differ from its pristine counterpart.
     *
     * <p>Strictly: a unified diff is a line-oriented format, so a file that is not
     * valid UTF-8 has no diff to generate — and silently substituting the
     * malformed bytes would let two different binaries compare equal. Failing here
     * names the file instead, which is the only useful thing that can be said.
     */
    private static String decode(String target, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new PatchEngineException(
                "[VeltisPatch] A changed file cannot be expressed as a text patch"
                    + "\n  Target: " + target
                    + "\n  Reason: the file is not valid UTF-8 text, so it has no line-oriented"
                    + " diff for a rebuild to write"
                    + "\n  Fix: restore the file from the pristine source/, or keep the change as"
                    + " a hand-written patch outside the rebuilt series"
                    + "\n  Note: nothing was written");
        }
    }
}
