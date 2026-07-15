package org.veltismc.veltis.patchengine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class UnifiedDiffPatcher {

    public void applyPatch(Path patchFile, Path targetDir) throws PatchEngineException {
        try {
            var lines = Files.readAllLines(patchFile, StandardCharsets.UTF_8);
            applyPatchLines(lines, patchFile.getFileName().toString(), targetDir);
        } catch (PatchEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchEngineException("Failed to apply patch: " + patchFile, e);
        }
    }

    public void applyPatchLines(List<String> patchLines, String patchName, Path targetDir)
            throws PatchEngineException {
        var hunks = parseHunks(patchLines);
        if (hunks.isEmpty()) {
            return;
        }

        var targetPath = resolveTargetPath(patchLines, targetDir);
        if (targetPath == null) {
            throw new PatchEngineException("Could not determine target file from patch: " + patchName);
        }

        try {
            applyHunks(targetPath, hunks);
        } catch (PatchEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new PatchEngineException("Failed to apply patch: " + patchName, e);
        }
    }

    private Path resolveTargetPath(List<String> patchLines, Path targetDir) {
        for (var line : patchLines) {
            if (line.startsWith("+++ b/")) {
                var relativePath = line.substring(6).replace(
                    '/', targetDir.getFileSystem().getSeparator().charAt(0));
                return targetDir.resolve(relativePath).normalize();
            }
        }
        return null;
    }

    private List<Hunk> parseHunks(List<String> lines) {
        var hunks = new ArrayList<Hunk>();
        Hunk current = null;
        boolean inHunk = false;

        for (var line : lines) {
            if (line.startsWith("@@")) {
                if (current != null && inHunk) {
                    hunks.add(current);
                }
                current = new Hunk();
                current.originalStart = parseOriginalLine(line);
                inHunk = true;
            } else if (inHunk && current != null) {
                current.lines.add(line);
            }
        }
        if (current != null && inHunk) {
            hunks.add(current);
        }
        return hunks;
    }

    private static int parseOriginalLine(String header) {
        // Format: @@ -oldStart,oldCount +newStart,newCount @@
        var parts = header.split(" ");
        if (parts.length >= 2) {
            return Integer.parseInt(parts[1].substring(1).split(",")[0]);
        }
        return 0;
    }

    private void applyHunks(Path targetFile, List<Hunk> hunks)
            throws IOException, PatchEngineException {
        if (!Files.exists(targetFile)) {
            writeNewFile(targetFile, hunks);
            return;
        }

        var content = Files.readString(targetFile, StandardCharsets.UTF_8);
        for (var hunk : hunks) {
            content = applyHunk(content, hunk);
        }
        Files.writeString(targetFile, content, StandardCharsets.UTF_8);
    }

    private static void writeNewFile(Path targetFile, List<Hunk> hunks) throws IOException {
        var sb = new StringBuilder();
        for (var hunk : hunks) {
            for (var line : hunk.lines) {
                if (line.startsWith("+")) {
                    sb.append(line.substring(1)).append("\n");
                }
            }
        }
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private String applyHunk(String content, Hunk hunk) throws PatchEngineException {
        var searchLines = new ArrayList<String>();
        var replaceLines = new ArrayList<String>();

        for (var line : hunk.lines) {
            if (line.isEmpty()) {
                searchLines.add("");
                replaceLines.add("");
            } else {
                var c = line.charAt(0);
                if (c == ' ') {
                    var t = line.substring(1);
                    searchLines.add(t);
                    replaceLines.add(t);
                } else if (c == '-') {
                    searchLines.add(line.substring(1));
                } else if (c == '+') {
                    replaceLines.add(line.substring(1));
                }
                // Skip non-standard lines (e.g. "\ No newline at end of file")
            }
        }

        if (searchLines.isEmpty()) {
            return insertAt(content, hunk.originalStart, replaceLines);
        }

        var fileLines = content.split("\\R", -1);

        // Strategy 1: strip leading/trailing whitespace only
        var matchIdx = findSequence(fileLines, searchLines, false);
        // Strategy 2: also collapse internal whitespace sequences
        if (matchIdx < 0) {
            matchIdx = findSequence(fileLines, searchLines, true);
        }
        // Strategy 3: skip blank lines when comparing
        if (matchIdx < 0) {
            matchIdx = findSequenceSkippingBlanks(fileLines, searchLines, true);
        }
        if (matchIdx < 0) {
            throw new PatchEngineException(
                "Failed to find match for hunk near line " + hunk.originalStart);
        }

        var lineSep = content.contains("\r\n") ? "\r\n" : "\n";
        var sb = new StringBuilder();

        for (int i = 0; i < matchIdx; i++) {
            sb.append(fileLines[i]).append(lineSep);
        }
        for (int i = 0; i < replaceLines.size(); i++) {
            sb.append(replaceLines.get(i));
            if (i < replaceLines.size() - 1
                    || matchIdx + searchLines.size() < fileLines.length) {
                sb.append(lineSep);
            }
        }
        for (int i = matchIdx + searchLines.size(); i < fileLines.length; i++) {
            sb.append(fileLines[i]);
            if (i < fileLines.length - 1) {
                sb.append(lineSep);
            }
        }

        return sb.toString();
    }

    private int findSequence(String[] fileLines, List<String> searchLines,
                             boolean collapseInternal) {
        outer:
        for (int i = 0; i <= fileLines.length - searchLines.size(); i++) {
            for (int j = 0; j < searchLines.size(); j++) {
                if (!normalizeEquals(
                        fileLines[i + j], searchLines.get(j), collapseInternal)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private int findSequenceSkippingBlanks(String[] fileLines, List<String> searchLines,
                                            boolean collapseInternal) {
        var nonBlankSearch = new ArrayList<String>();
        for (var l : searchLines) {
            if (!l.isBlank()) {
                nonBlankSearch.add(l);
            }
        }
        if (nonBlankSearch.isEmpty() || nonBlankSearch.size() == searchLines.size()) {
            return -1;
        }

        outer:
        for (int i = 0; i <= fileLines.length - nonBlankSearch.size(); i++) {
            for (int j = 0; j < nonBlankSearch.size(); j++) {
                if (!normalizeEquals(
                        fileLines[i + j], nonBlankSearch.get(j), collapseInternal)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean normalizeEquals(String a, String b,
                                            boolean collapseInternal) {
        var na = a.strip();
        var nb = b.strip();
        if (collapseInternal) {
            na = na.replaceAll("\\s+", " ");
            nb = nb.replaceAll("\\s+", " ");
        }
        return na.equals(nb);
    }

    private static String insertAt(String content, int originalStart, List<String> newLines) {
        if (newLines.isEmpty()) return content;
        var fileLines = content.split("\\R", -1);
        var lineSep = content.contains("\r\n") ? "\r\n" : "\n";
        int insertPos = Math.min(Math.max(0, originalStart), fileLines.length);
        var sb = new StringBuilder();
        for (int i = 0; i < insertPos; i++) {
            sb.append(fileLines[i]).append(lineSep);
        }
        for (int i = 0; i < newLines.size(); i++) {
            sb.append(newLines.get(i));
            if (i < newLines.size() - 1 || insertPos < fileLines.length) {
                sb.append(lineSep);
            }
        }
        for (int i = insertPos; i < fileLines.length; i++) {
            sb.append(fileLines[i]);
            if (i < fileLines.length - 1) {
                sb.append(lineSep);
            }
        }
        return sb.toString();
    }

    private static class Hunk {
        int originalStart;
        List<String> lines = new ArrayList<>();
    }
}
