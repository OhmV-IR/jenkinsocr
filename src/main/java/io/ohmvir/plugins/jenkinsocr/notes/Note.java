package io.ohmvir.plugins.jenkinsocr.notes;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import java.util.List;
import java.util.Objects;

/**
 * A note to upload to a {@link NoteProvider}.
 *
 * @param title  the note's title
 * @param path   the folder to store the note in, as segments separated by {@code /} (e.g. {@code "Math/Algebra"});
 *               empty for the provider's root folder
 * @param text   the note's content
 * @param format the format {@code text} is written in
 */
public record Note(String title, String path, String text, FormulaOutputType format) {
    public Note {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A note title is required");
        }
        title = title.strip();
        path = path == null ? "" : path;
        text = text == null ? "" : text;
        Objects.requireNonNull(format, "format");
    }

    /**
     * The folder path split into its non-blank, trimmed segments.
     */
    public List<String> pathSegments() {
        return NotePaths.segments(path);
    }
}
