package io.ohmvir.plugins.jenkinsocr.notes.content;

import io.ohmvir.plugins.jenkinsocr.FormulaOutputType;
import java.util.Objects;

/**
 * Parses the text produced by the OCR model into a {@link NoteDocument}, according to the format the model was
 * asked to produce.
 */
public final class NoteContentParser {
    private NoteContentParser() {}

    /**
     * @param text   the note text
     * @param format the format the text is written in
     * @return the parsed document
     */
    public static NoteDocument parse(String text, FormulaOutputType format) {
        Objects.requireNonNull(format, "format");
        String source = text == null ? "" : text;
        return switch (format) {
            case PURE_TEXT -> PlainTextParser.parse(source);
            case KATEX -> MarkdownParser.parse(source);
            case LATEX -> LatexParser.parse(source);
        };
    }
}
