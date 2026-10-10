package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses plain text (the {@link io.ohmvir.plugins.jenkinsocr.FormulaOutputType#PURE_TEXT} output) into a
 * {@link NoteDocument}: blank lines separate paragraphs and every other character is kept literally.
 */
final class PlainTextParser {
    private PlainTextParser() {}

    static NoteDocument parse(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        for (String line : MarkdownParser.splitLines(text)) {
            if (line.isBlank()) {
                addParagraph(paragraph, blocks);
            } else {
                paragraph.add(line.stripTrailing());
            }
        }
        addParagraph(paragraph, blocks);
        return new NoteDocument(blocks, List.of());
    }

    private static void addParagraph(List<String> lines, List<Block> blocks) {
        if (!lines.isEmpty()) {
            blocks.add(new Block.Paragraph(List.of(new Inline.Text(String.join("\n", lines)))));
            lines.clear();
        }
    }
}
