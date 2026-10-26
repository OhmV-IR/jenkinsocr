package io.ohmvir.plugins.jenkinsocr.notes.content;

import java.util.List;

/**
 * A note's content in a format-neutral form, parsed once from the OCR model's output and then rendered by each
 * note provider into its native format.
 *
 * @param blocks   the note's blocks, in reading order
 * @param warnings human-readable problems found while parsing, such as constructs that could not be converted
 */
public record NoteDocument(List<Block> blocks, List<String> warnings) {
    public NoteDocument {
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
    }
}
