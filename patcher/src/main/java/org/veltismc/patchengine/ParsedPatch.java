package org.veltismc.patchengine;

import java.util.List;

/**
 * A validated parse of one patch file: the files it addresses and the hunks for
 * each of them.
 *
 * <p>Parsing is a separate, side-effect-free step so a patch is validated once
 * and the per-target workers only have to apply hunks. Validation is strict on
 * purpose: hunk header counts are honoured, so a header that claims more lines
 * than the patch provides is a hard error rather than a silently truncated
 * source file.
 */
final class ParsedPatch {

    /** One file's worth of a patch: its target, its header state and its hunks. */
    record FileDiff(String target, String oldTarget, boolean deletion, List<Hunk> hunks) {

        /** True when the patch's own header says it creates the target file. */
        boolean createsFile() {
            return oldTarget != null && oldTarget.equals("/dev/null");
        }
    }

    /** One hunk: the lines to find and the lines that replace them. */
    static final class Hunk {
        final int originalStart;
        final List<String> search;
        final List<String> replace;

        /**
         * For each entry of {@link #replace}, the index of the matching entry in
         * {@link #search}, or -1 when the line is an addition.
         *
         * <p>Context lines are copied from the file, not from the patch: they
         * exist to locate the hunk, and a hunk is allowed to have matched modulo
         * whitespace or with blank lines skipped. Writing the patch's copy back
         * would silently reformat lines the patch never intended to touch.
         *
         * <p>The link is by index rather than by position because deletions and
         * additions interleave differently in the two lists: the first context
         * line of a hunk is at the same offset in both, the second generally is
         * not.
         */
        private final int[] contextSearchIndex;

        Hunk(int originalStart, int oldCount, int newCount) {
            this.originalStart = originalStart;
            this.search = new java.util.ArrayList<>(oldCount);
            this.replace = new java.util.ArrayList<>(newCount);
            this.contextSearchIndex = new int[Math.max(0, newCount)];
            java.util.Arrays.fill(contextSearchIndex, -1);
        }

        /**
         * Records that the line just appended to both lists is context. Called
         * only after the line has been added to {@link #search}, so the index it
         * records is the one it will be matched at.
         */
        void markContext() {
            contextSearchIndex[replace.size() - 1] = search.size() - 1;
        }

        /**
         * Where in {@link #search} the replacement line at {@code replaceIndex}
         * came from, or -1 when it is an addition rather than context. The match
         * position found for that search line is where the file's own bytes are.
         */
        int contextSearchIndex(int replaceIndex) {
            return contextSearchIndex[replaceIndex];
        }
    }

    final List<FileDiff> files;

    ParsedPatch(List<FileDiff> files) {
        this.files = List.copyOf(files);
    }

    /** Every target this patch addresses, in the order the patch declares them. */
    List<String> targets() {
        return files.stream().map(FileDiff::target).distinct().toList();
    }
}
