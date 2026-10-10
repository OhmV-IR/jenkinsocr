package io.ohmvir.plugins.jenkinsocr.notes;

import java.util.Arrays;
import java.util.List;

/**
 * Helpers for the {@code /}-separated folder paths used to categorise notes.
 */
public final class NotePaths {
    private NotePaths() {}

    /**
     * Splits a path into its trimmed, non-blank segments.
     */
    public static List<String> segments(String path) {
        if (path == null) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .toList();
    }

    /**
     * Formats folder paths as an indented bullet tree for the OCR model's prompt, for example:
     *
     * <pre>
     * - Math (Path: Math)
     *   - Algebra (Path: Math/Algebra)
     * </pre>
     */
    public static String formatTree(List<String> paths) {
        if (paths.isEmpty()) {
            return "(no folders exist yet)\n";
        }
        StringBuilder tree = new StringBuilder();
        for (String path : paths) {
            List<String> segments = segments(path);
            if (segments.isEmpty()) {
                continue;
            }
            tree.append("  ".repeat(segments.size() - 1))
                    .append("- ")
                    .append(segments.get(segments.size() - 1))
                    .append(" (Path: ")
                    .append(String.join("/", segments))
                    .append(")\n");
        }
        return tree.toString();
    }
}
