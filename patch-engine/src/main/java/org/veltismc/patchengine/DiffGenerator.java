package org.veltismc.patchengine;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces git-style unified diffs in exactly the format
 * {@link VeltisPatch#parse} reads back.
 *
 * <p>This is what makes {@code rebuildVeltisPatches} a closed loop: a regenerated
 * patch is consumed by the same parser, the same validator and the same matcher
 * as a hand-written one, so "the rebuild produced something" and "the engine can
 * apply it" cannot drift apart. No {@code git diff} subprocess, no temp files.
 *
 * <p>How a diff is produced:
 *
 * <ol>
 *   <li>Split both texts into lines exactly the way the patcher splits a file,
 *       so a line means the same thing on both sides.</li>
 *   <li>Drop the identical prefix and suffix. A source edit is almost always
 *       local, and trimming turns "diff two 15 000-line files" into "diff two
 *       six-line regions" before any allocation happens.</li>
 *   <li>Run Myers' O(ND) algorithm on what is left, which finds the smallest
 *       edit script. It is bounded by a maximum distance; a region that exceeds
 *       the bound falls back to a single replace of the whole region, which is
 *       ugly but always correct and never runs unboundedly.</li>
 *   <li>Group the edits into hunks with three lines of context, merging hunks
 *       whose context would touch, and emit headers whose counts are computed
 *       from the emitted lines rather than guessed.</li>
 * </ol>
 *
 * <p>Determinism matters more than minimality here: the same pair of inputs
 * always yields byte-identical output, on every platform and at every worker
 * count, so a rebuilt patch produces a clean diff in version control only when
 * something actually changed.
 */
final class DiffGenerator {

    /** Lines of context kept on each side of a change, matching git's default. */
    private static final int CONTEXT = 3;

    /**
     * Largest edit distance Myers may search before falling back to a whole-region
     * replace. Bounds both time and memory: the trace keeps one {@code int[2d+2]}
     * per distance, so this is what keeps a pathological pair of files from
     * allocating gigabytes.
     */
    private static final int MAX_DISTANCE = 1500;

    private DiffGenerator() {
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Diffs two versions of one workspace-relative file.
     *
     * @param target  workspace-relative path, emitted as {@code +++ b/<target>}
     * @param oldText pristine content, or {@code null} when the file is being created
     * @param newText patched content
     * @return the diff lines, or an empty list when the two texts are identical
     */
    static List<String> diff(String target, String oldText, String newText) {
        if (newText == null || (oldText != null && oldText.equals(newText))) {
            // Nothing on either side, or the two sides are the same: no change.
            return List.of();
        }
        if (newText.isEmpty()) {
            // The engine refuses deletions on purpose: a patch that removes a file
            // from the decompiled source would silently drop it from the build.
            throw new PatchEngineException(
                "[VeltisPatch] Failed to rebuild patch\n"
                    + "  Target: " + target
                    + "\n  Reason: the patched file is empty, so the diff would delete it;"
                    + " VeltisMC patches may only create or modify files, never delete"
                    + " them from the decompiled source");
        }

        var oldFile = Side.of(oldText);
        var newFile = Side.of(newText);
        if (oldFile.isEmpty() && newFile.isEmpty()) {
            return List.of();
        }

        var a = oldFile.lines;
        var b = newFile.lines;

        // 1) Identical prefix and suffix are never part of a change.
        int prefix = 0;
        int maxPrefix = Math.min(a.size(), b.size());
        while (prefix < maxPrefix && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        int maxSuffix = Math.min(a.size(), b.size()) - prefix;
        while (suffix < maxSuffix
               && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }

        var midA = a.subList(prefix, a.size() - suffix);
        var midB = b.subList(prefix, b.size() - suffix);

        // 2) Smallest edit script for the differing middle, translated back to
        //    whole-file coordinates.
        var changes = new ArrayList<Change>();
        for (var change : middleChanges(midA, midB)) {
            changes.add(new Change(change.a1() + prefix, change.a2() + prefix,
                change.b1() + prefix, change.b2() + prefix));
        }
        if (changes.isEmpty()) {
            return List.of();
        }

        // 3) Emit.
        var out = new ArrayList<String>();
        out.add("diff --git a/" + target + " b/" + target);
        if (oldText == null || oldFile.isEmpty()) {
            out.add("--- /dev/null");
        } else {
            out.add("--- a/" + target);
        }
        out.add("+++ b/" + target);

        for (var hunk : groupIntoHunks(changes, a.size(), b.size())) {
            emitHunk(out, hunk, a, b, oldFile.endsWithNewline(), newFile.endsWithNewline());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Line handling
    // ------------------------------------------------------------------

    /**
     * One side of a diff: its content lines, and whether it ends with a newline.
     *
     * <p>{@link UnifiedDiffPatcher#splitLines} keeps a trailing empty segment for
     * content that ends in a line break. That segment is a property of the file,
     * not a line, so it is dropped here and re-created at write time by the
     * patcher's own join.
     */
    private record Side(List<String> lines, boolean endsWithNewline) {

        static Side of(String text) {
            if (text == null || text.isEmpty()) {
                return new Side(List.of(), false);
            }
            var all = UnifiedDiffPatcher.splitLines(text);
            boolean trailing = all.get(all.size() - 1).isEmpty();
            return new Side(
                List.copyOf(trailing ? all.subList(0, all.size() - 1) : all), trailing);
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Myers' algorithm
    // ------------------------------------------------------------------

    /** A run of old lines {@code [a1,a2)} replaced by new lines {@code [b1,b2)}. */
    private record Change(int a1, int a2, int b1, int b2) {
    }

    /**
     * Merges the individual delete/insert steps of an edit script into the
     * smallest set of replacements. Adjacent steps collapse into one change, so
     * a modified line becomes a single {@code a1<a2, b1<b2} pair rather than a
     * delete followed by an insert.
     */
    private static List<Change> middleChanges(List<String> a, List<String> b) {
        var out = new ArrayList<Change>();
        if (a.isEmpty() && b.isEmpty()) {
            return out;
        }
        if (a.isEmpty()) {
            return List.of(new Change(0, 0, 0, b.size()));
        }
        if (b.isEmpty()) {
            return List.of(new Change(0, a.size(), 0, 0));
        }

        int n = a.size();
        int m = b.size();
        int bound = Math.min(MAX_DISTANCE, n + m);
        var trace = new ArrayList<int[]>(bound + 1);
        int[] v = new int[2 * bound + 2];
        int offset = bound + 1;

        for (int d = 0; d <= bound; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int ki = k + offset;
                int x;
                if (k == -d || (k != d && v[ki - 1] < v[ki + 1])) {
                    x = v[ki + 1];            // step down: consume a line of b
                } else {
                    x = v[ki - 1] + 1;        // step right: consume a line of a
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[ki] = x;
                if (x >= n && y >= m) {
                    return backtrack(trace, a, b, d, offset, n, m);
                }
            }
        }
        // Distance bound exceeded: correct, deterministic, and obviously a
        // whole-region replace in the diff.
        return List.of(new Change(0, n, 0, m));
    }

    /**
     * Walks the recorded search paths back to the origin and turns the result
     * into replacements. The common prefix was already removed by the caller, so
     * the {@code d = 0} layer is guaranteed to be empty and needs no handling.
     *
     * <p>Backtracking produces the steps in reverse order, so they are reversed
     * once and then merged <em>forwards</em>. Merging is what turns a modified
     * line — a deletion immediately followed by an insertion at the same place —
     * into one replacement instead of two, which is what keeps a hunk's context
     * from overlapping its own edits.
     */
    private static List<Change> backtrack(List<int[]> trace, List<String> a, List<String> b,
                                          int distance, int offset, int n, int m) {
        var backwards = new ArrayList<Change>();
        int x = n;
        int y = m;

        for (int d = distance; d > 0; d--) {
            var v = trace.get(d);
            int k = x - y;
            int ki = k + offset;
            int prevK = (k == -d || (k != d && v[ki - 1] < v[ki + 1])) ? k + 1 : k - 1;
            int prevX = v[prevK + offset];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;   // diagonal step: the line is equal, nothing to record
                y--;
            }
            if (x == prevX) {
                backwards.add(new Change(x, x, y - 1, y));   // insertion
                y--;
            } else {
                backwards.add(new Change(x - 1, x, y, y));   // deletion
                x--;
            }
        }

        var out = new ArrayList<Change>();
        for (int i = backwards.size() - 1; i >= 0; i--) {
            var step = backwards.get(i);
            if (out.isEmpty()) {
                out.add(step);
                continue;
            }
            var prev = out.get(out.size() - 1);
            // Two steps are one replacement when neither side has a gap between
            // them: the second continues exactly where the first stopped.
            if (step.a1() == prev.a2() && step.b1() == prev.b2()) {
                out.set(out.size() - 1, new Change(prev.a1(), step.a2(), prev.b1(), step.b2()));
            } else {
                out.add(step);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Hunk grouping
    // ------------------------------------------------------------------

    /** A change plus the context window around it, in both files. */
    private record Hunk(int aFrom, int aTo, int bFrom, int bTo, List<Change> changes) {
    }

    /**
     * Expands each change by {@link #CONTEXT} lines and merges windows that
     * would overlap or touch, so no two hunks can claim the same source line.
     * Without this a pair of nearby edits would produce two hunks whose context
     * runs collide, and applying them in sequence would double-apply the shared
     * context.
     */
    private static List<Hunk> groupIntoHunks(List<Change> changes, int aLen, int bLen) {
        var hunks = new ArrayList<Hunk>();
        int aFrom = -1;
        int aTo = -1;
        var current = new ArrayList<Change>();

        for (var change : changes) {
            int from = Math.max(0, change.a1() - CONTEXT);
            int to = Math.min(aLen, Math.max(change.a2(), change.a1()) + CONTEXT);
            if (current.isEmpty()) {
                aFrom = from;
                aTo = to;
                current.add(change);
                continue;
            }
            if (from <= aTo) {      // overlapping or adjacent context: one hunk
                aTo = Math.max(aTo, to);
                current.add(change);
                continue;
            }
            hunks.add(finishHunk(aFrom, aTo, current));
            aFrom = from;
            aTo = to;
            current = new ArrayList<>();
            current.add(change);
        }
        if (!current.isEmpty()) {
            hunks.add(finishHunk(aFrom, aTo, current));
        }

        // Translate each hunk's old-side window to the matching new-side window.
        var out = new ArrayList<Hunk>(hunks.size());
        for (var hunk : hunks) {
            var first = hunk.changes().get(0);
            var last = hunk.changes().get(hunk.changes().size() - 1);
            int bFrom = first.b1() - (first.a1() - hunk.aFrom());
            int bTo = last.b2() - (last.a2() - hunk.aTo());
            out.add(new Hunk(hunk.aFrom(), hunk.aTo(),
                Math.max(0, bFrom), Math.min(bLen, bTo), hunk.changes()));
        }
        return out;
    }

    private static Hunk finishHunk(int aFrom, int aTo, List<Change> changes) {
        return new Hunk(aFrom, aTo, -1, -1, List.copyOf(changes));
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private static final int CONTEXT_LINE = 0;
    private static final int REMOVED_LINE = 1;
    private static final int ADDED_LINE = 2;

    private record Op(int kind, String text) {
    }

    /**
     * Emits one hunk. The header counts are taken from the ops that were actually
     * written, not from the change list, so a patch can never claim a line count
     * the parser would then reject.
     */
    private static void emitHunk(List<String> out, Hunk hunk,
                                 List<String> a, List<String> b,
                                 boolean oldEndsWithNewline, boolean newEndsWithNewline) {
        var ops = new ArrayList<Op>();
        int ai = hunk.aFrom();
        for (var change : hunk.changes()) {
            while (ai < change.a1()) {
                ops.add(new Op(CONTEXT_LINE, a.get(ai++)));
            }
            for (int i = change.a1(); i < change.a2(); i++) {
                ops.add(new Op(REMOVED_LINE, a.get(i)));
            }
            for (int j = change.b1(); j < change.b2(); j++) {
                ops.add(new Op(ADDED_LINE, b.get(j)));
            }
            ai = change.a2();
        }
        while (ai < hunk.aTo()) {
            ops.add(new Op(CONTEXT_LINE, a.get(ai++)));
        }

        int oldCount = 0;
        int newCount = 0;
        for (var op : ops) {
            if (op.kind() != ADDED_LINE) {
                oldCount++;
            }
            if (op.kind() != REMOVED_LINE) {
                newCount++;
            }
        }

        out.add("@@ -" + (hunk.aFrom() + 1) + "," + oldCount
            + " +" + (hunk.bFrom() + 1) + "," + newCount + " @@");
        for (var op : ops) {
            out.add(switch (op.kind()) {
                case CONTEXT_LINE -> " " + op.text();
                case REMOVED_LINE -> "-" + op.text();
                default -> "+" + op.text();
            });
        }

        // "\ No newline at end of file" is advisory metadata that the parser
        // ignores. It is still emitted so a rebuilt patch is byte-identical to one
        // produced by git and no information is lost on a round trip. It belongs
        // to whichever side actually stopped short of a line break.
        boolean reachesOldEnd = hunk.aTo() == a.size();
        boolean reachesNewEnd = hunk.bTo() == b.size();
        if (reachesNewEnd && !newEndsWithNewline) {
            out.add("\\ No newline at end of file");
        } else if (reachesOldEnd && !oldEndsWithNewline && !reachesNewEnd) {
            out.add("\\ No newline at end of file");
        }
    }
}
